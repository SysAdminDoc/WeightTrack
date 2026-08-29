package com.weighttrack.data.food

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.weighttrack.core.nutrition.Food
import com.weighttrack.core.nutrition.FoodOrigin
import com.weighttrack.core.nutrition.Nutrients
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A shelf of common products that ships inside the app.
 *
 * Built by `tools/build_offline_foods.py` from the Open Food Facts export, ranked by how often
 * each product actually gets scanned, with a quota per market so the set is not simply the French
 * supermarket that Open Food Facts grew out of.
 *
 * The point is a scanner that works in a shop with no signal, and a search box that answers
 * instantly instead of after a round trip. It is not a replacement for the online lookup: it holds
 * a few tens of thousands of products against several million, so anything not on it still goes
 * out to the network as before.
 *
 * Read only, and never written to. A product from here becomes an ordinary food the moment
 * somebody keeps or eats it, and after that it is theirs to edit.
 */
@Singleton
class OfflineFoodStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Opened lazily and kept.
     *
     * SQLite needs a path on the filesystem and an asset only exists inside the APK, so it is
     * copied out once. The copy is keyed on the shelf's digest, so a build that ships a new one
     * gets a new copy and a build that does not never pays for it again.
     */
    private var opened: SQLiteDatabase? = null

    /**
     * Tried again after a failure rather than given up on.
     *
     * The copy can fail because the phone has no room left. Remembering that forever would leave
     * the shelf switched off until the app was killed, long after somebody had cleared space and
     * wondered why the scanner still needed a signal.
     */
    @Synchronized
    private fun database(): SQLiteDatabase? {
        opened?.let { if (it.isOpen) return it }
        opened = runCatching { open() }.getOrNull()
        return opened
    }

    /** Whether the shelf is there at all. A build without the asset simply has no offline set. */
    val available: Boolean get() = database() != null

    private fun open(): SQLiteDatabase? {
        // Written beside the shelf by the build script: a digest and a length. Asking the asset
        // manager for the size instead would not work, because a compressed asset has no length
        // until it is unpacked, and unpacking it is the expensive thing being avoided.
        val stamp = context.assets.open(STAMP).use { it.readBytes() }
            .decodeToString().trim().split(" ")
        val digest = stamp.firstOrNull().orEmpty()
        val bytes = stamp.getOrNull(1)?.toLongOrNull() ?: -1L
        if (digest.isEmpty() || bytes <= 0) return null

        val file = File(context.filesDir, "offline_foods.$digest.db")
        if (file.length() != bytes) {
            // Any other copy is a previous build's shelf and is only taking up room.
            context.filesDir.listFiles { candidate ->
                candidate.name.startsWith("offline_foods.") && candidate != file
            }?.forEach { it.delete() }
            val partial = File(context.filesDir, "offline_foods.partial")
            context.assets.open(ASSET).use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            // A copy cut short by a phone running out of room is not a database. Better to have
            // no shelf and go to the network than to open half of one.
            if (partial.length() != bytes) {
                partial.delete()
                return null
            }
            // Named only once it is whole, so an interrupted copy is never opened.
            file.delete()
            if (!partial.renameTo(file)) {
                partial.delete()
                return null
            }
        }
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    /** The product with this barcode, if it is one of the common ones. */
    suspend fun byBarcode(barcode: String): Food? = withContext(Dispatchers.IO) {
        val code = barcode.trim()
        if (code.isEmpty()) return@withContext null
        val db = database() ?: return@withContext null
        runCatching {
            db.rawQuery("$COLUMNS WHERE barcode = ? LIMIT 1", arrayOf(code)).use { cursor ->
                if (cursor.moveToFirst()) cursor.toFood() else null
            }
        }.getOrNull()
    }

    /**
     * Products matching every word typed, in any order.
     *
     * Word by word rather than as one string, so "kellogg corn" finds the cornflakes without
     * anybody having to remember how the packet words it. Blank and very short queries return
     * nothing: the whole shelf is not a useful answer, and this list is shown beside somebody's
     * own foods, which should stay first.
     */
    suspend fun search(query: String, limit: Int = SEARCH_LIMIT): List<Food> =
        withContext(Dispatchers.IO) {
            val words = query.trim().lowercase().split(" ").filter { it.isNotEmpty() }
            if (words.isEmpty() || query.trim().length < MIN_QUERY) return@withContext emptyList()
            val db = database() ?: return@withContext emptyList()
            // The escape clause belongs to each LIKE, not to the statement. Written once at
            // the end it binds to the last one only, and every earlier pattern goes to SQLite
            // still carrying the backslashes, hunting for a literal backslash no product name
            // contains. A search for "0% fat" then answers nothing at all.
            val where = words.joinToString(" AND ") { "search LIKE ? ESCAPE '\\'" }
            val arguments = words.map { "%${it.escapeForLike()}%" } + limit.toString()
            runCatching {
                db.rawQuery(
                    "$COLUMNS WHERE $where ORDER BY scans DESC, name LIMIT ?",
                    arguments.toTypedArray(),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.toFood())
                    }
                }
            }.getOrDefault(emptyList())
        }

    /** A percent sign in a product name would otherwise match everything. */
    private fun String.escapeForLike(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun android.database.Cursor.value(index: Int): Double? =
        if (isNull(index)) null else getDouble(index)

    private fun android.database.Cursor.toFood(): Food = Food(
        // Not in anybody's food table yet, and a zero says so. Logging one copies the numbers
        // across, and keeping one gives it a real identity.
        id = 0,
        name = getString(1),
        brand = if (isNull(2)) null else getString(2),
        barcode = getString(0),
        per100g = Nutrients(
            kcal = getDouble(3),
            proteinG = value(4),
            carbsG = value(5),
            fatG = value(6),
            fibreG = value(7),
            sugarG = value(8),
            saltG = value(9),
        ),
        servingGrams = value(10),
        // It is Open Food Facts data wherever it was read from, and the licence follows it.
        origin = FoodOrigin.OPEN_FOOD_FACTS,
    )

    companion object {
        const val ASSET = "offline_foods.db"

        /** A digest and a length, written next to the shelf so the copy can be keyed on them. */
        const val STAMP = "offline_foods.db.id"
        const val SEARCH_LIMIT = 25

        /** Below this a search matches half the shelf and answers nothing. */
        const val MIN_QUERY = 3

        private const val COLUMNS =
            "SELECT barcode, name, brand, kcal, protein, carbs, fat, fibre, sugar, salt, serving " +
                "FROM offline_food"
    }
}
