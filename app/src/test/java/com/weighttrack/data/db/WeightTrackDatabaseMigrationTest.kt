package com.weighttrack.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The upgrade path from every version that has shipped.
 *
 * Losing someone's weight history on an app update is the worst thing this app could do, and a
 * destructive fallback would do exactly that in silence. This builds a real database at an old
 * version from the schema Room itself exported, puts rows in it, then opens it with the current
 * Room definition so the whole real auto-migration chain runs, and checks the rows are still
 * there afterwards.
 *
 * Every shipped version gets its own case. Only testing the oldest one leaves the intermediate
 * steps unproven: a phone updating from 3 to 4 never runs the 1 to 2 migration at all.
 *
 * Driving the old tables from the exported `<version>.json` rather than hand-written DDL means
 * the test cannot drift away from what those versions actually shipped.
 */
@RunWith(RobolectricTestRunner::class)
class WeightTrackDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "migration-test.db"
    private var database: WeightTrackDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    private fun schemaFile(version: Int): File {
        val relative = "schemas/com.weighttrack.data.db.WeightTrackDatabase/$version.json"
        // Unit tests run from the module directory, but tolerate the repo root too.
        return listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?: error("Could not find the exported Room schema for version $version")
    }

    /** Recreates an old database exactly as Room described it. */
    private fun createDatabaseAtVersion(version: Int): File {
        val schema = Json.parseToJsonElement(schemaFile(version).readText())
            .jsonObject.getValue("database").jsonObject

        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        schema.getValue("entities").jsonArray.forEach { entity ->
            val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
            val createSql = entity.jsonObject.getValue("createSql").jsonPrimitive.content
            db.execSQL(createSql.replace("\${TABLE_NAME}", table))
            // Room validates indices as well as columns, so a table without them is not a
            // faithful old database and the migration check fails for the wrong reason.
            entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                val indexSql = index.jsonObject.getValue("createSql").jsonPrimitive.content
                db.execSQL(indexSql.replace("\${TABLE_NAME}", table))
            }
        }
        // Room refuses to open a database whose recorded identity hash it does not recognise,
        // so the setup queries that stamp it are part of being a genuine old database.
        schema["setupQueries"]?.jsonArray?.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
        db.close()
        return file
    }

    private fun withOldDatabase(block: (SQLiteDatabase) -> Unit) {
        val db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        block(db)
        db.close()
    }

    /** The three tables that have existed since version 1. */
    private fun seedCoreRows() = withOldDatabase { db ->
        db.execSQL(
            """
            INSERT INTO weight_entries
            (timestampUtcMillis, zoneOffsetSeconds, localDate, grams, bodyFatPercent, note,
             tags, source, clientRecordId, healthConnectId, updatedAtUtcMillis)
            VALUES (1700000000000, 0, '2023-11-14', 82500, 21.5, 'a note', 'FASTED',
                    'MANUAL', 'client-1', NULL, 0)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO measurements
            (timestampUtcMillis, localDate, type, valueMm, note, updatedAtUtcMillis)
            VALUES (1700000000000, '2023-11-14', 'WAIST', 880, NULL, 0)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO goals
            (direction, startGrams, targetGrams, startDate, targetDate, milestoneStepGrams,
             active, createdAtUtcMillis)
            VALUES ('LOSE', 90000, 80000, '2023-11-01', NULL, 2000, 1, 0)
            """.trimIndent(),
        )
    }

    /** Opens with the current definition, which is what actually runs the migration. */
    private fun openCurrent(): WeightTrackDatabase =
        Room.databaseBuilder(context, WeightTrackDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
            .also { database = it }

    private suspend fun assertCoreRowsSurvived(db: WeightTrackDatabase) {
        val entry = db.weightEntryDao().latest()
        assertThat(entry).isNotNull()
        assertThat(entry!!.grams).isEqualTo(82_500)
        assertThat(entry.note).isEqualTo("a note")
        assertThat(entry.clientRecordId).isEqualTo("client-1")
        assertThat(entry.bodyFatPercent).isWithin(1e-9).of(21.5)

        assertThat(db.measurementDao().latestPerType().single().valueMm).isEqualTo(880)
        assertThat(db.goalDao().active()!!.targetGrams).isEqualTo(80_000)
    }

    @Test
    fun `version 1 data survives the upgrade`() = runTest {
        createDatabaseAtVersion(1)
        seedCoreRows()

        assertCoreRowsSurvived(openCurrent())
    }

    @Test
    fun `version 2 data survives the upgrade`() = runTest {
        createDatabaseAtVersion(2)
        seedCoreRows()
        withOldDatabase { db ->
            db.execSQL(
                """
                INSERT INTO water_entries
                (timestampUtcMillis, localDate, millilitres, healthConnectId, updatedAtUtcMillis)
                VALUES (1700000000000, '2023-11-14', 330, NULL, 0)
                """.trimIndent(),
            )
        }

        val db = openCurrent()
        assertCoreRowsSurvived(db)
        assertThat(db.waterDao().totalForDate("2023-11-14")).isEqualTo(330)
    }

    @Test
    fun `version 3 data survives the upgrade`() = runTest {
        createDatabaseAtVersion(3)
        seedCoreRows()
        withOldDatabase { db ->
            db.execSQL(
                """
                INSERT INTO water_entries
                (timestampUtcMillis, localDate, millilitres, healthConnectId, updatedAtUtcMillis)
                VALUES (1700000000000, '2023-11-14', 330, NULL, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO fasts
                (startUtcMillis, endUtcMillis, targetMinutes, note, updatedAtUtcMillis)
                VALUES (1700000000000, 1700057600000, 960, NULL, 0)
                """.trimIndent(),
            )
        }

        val db = openCurrent()
        assertCoreRowsSurvived(db)
        assertThat(db.waterDao().totalForDate("2023-11-14")).isEqualTo(330)
        assertThat(db.fastDao().observeCompleted().first().single().targetMinutes).isEqualTo(960)
    }

    @Test
    fun `the water table exists and works after the upgrade`() = runTest {
        createDatabaseAtVersion(1)
        seedCoreRows()

        val db = openCurrent()
        val dao = db.waterDao()
        assertThat(dao.totalForDate("2026-01-01")).isEqualTo(0)

        dao.insert(
            WaterEntryEntity(
                timestampUtcMillis = 1_800_000_000_000,
                localDate = "2026-01-01",
                millilitres = 250,
                healthConnectId = null,
                updatedAtUtcMillis = 0,
            ),
        )
        assertThat(dao.totalForDate("2026-01-01")).isEqualTo(250)
    }

    @Test
    fun `the fasting table exists and works after the upgrade`() = runTest {
        createDatabaseAtVersion(1)
        seedCoreRows()

        val db = openCurrent()
        val dao = db.fastDao()
        assertThat(dao.active()).isNull()

        val id = dao.startFast(startUtcMillis = 1_800_000_000_000, targetMinutes = 16 * 60)
        assertThat(id).isNotNull()
        assertThat(id!!).isGreaterThan(0)
        val active = dao.active()
        assertThat(active).isNotNull()
        assertThat(active!!.targetMinutes).isEqualTo(16 * 60)
        assertThat(active.endUtcMillis).isNull()
    }

    @Test
    fun `the progress photo table exists and works after the upgrade`() = runTest {
        createDatabaseAtVersion(1)
        seedCoreRows()

        val db = openCurrent()
        val dao = db.progressPhotoDao()
        dao.insert(
            ProgressPhotoEntity(
                timestampUtcMillis = 1_800_000_000_000,
                localDate = "2026-01-01",
                fileName = "photo-1.jpg",
                weightGrams = 82_500,
                note = null,
            ),
        )
        assertThat(dao.observeAll().first().single().fileName).isEqualTo("photo-1.jpg")
    }

    @Test
    fun `an empty version 1 database upgrades cleanly`() = runTest {
        createDatabaseAtVersion(1)

        val db = openCurrent()
        assertThat(db.weightEntryDao().count()).isEqualTo(0)
        assertThat(db.waterDao().totalForDate("2026-01-01")).isEqualTo(0)
    }
}
