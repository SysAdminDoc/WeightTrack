package com.weighttrack.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/**
 * Upgrading somebody's database without losing what is in it.
 *
 * The worst thing this app could do is lose years of weigh-ins on an update, and a destructive
 * fallback is deliberately not configured, so a migration that does not fit crashes on launch
 * rather than quietly emptying the table. That makes these worth having: Room checks the shape of
 * a migration at build time, but nothing checks that the rows come through, or that the code
 * which runs afterwards does what it was written to do.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    /**
     * Reads the exported schemas from the test app's assets, which is why this lives here rather
     * than beside the unit tests: a unit test has no merged asset folder to read them from, and
     * shipping three hundred kilobytes of schema inside the app so that a test could find them
     * would be the wrong way round.
     */
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WeightTrackDatabase::class.java,
    )

    /** The one number a query returns. */
    private fun SupportSQLiteDatabase.count(sql: String): Long =
        query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "no row from: $sql" }
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.text(sql: String): String =
        query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "no row from: $sql" }
            cursor.getString(0)
        }

    @Test
    fun readingFromBeforeProfilesSurvivesEveryStep() {
        // A reading written before profiles existed survives every step.
        // Version one: no profiles, no names that travel, none of the rest of it.
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO weight_entries " +
                    "(timestampUtcMillis, zoneOffsetSeconds, localDate, grams, bodyFatPercent, " +
                    "note, tags, source, clientRecordId, healthConnectId, updatedAtUtcMillis) " +
                    "VALUES (1700000000000, 0, '2023-11-14', 84200, NULL, NULL, '', 'MANUAL', " +
                    "'old-reading', NULL, 1700000000000)",
            )
        }

        helper.runMigrationsAndValidate(NAME, LATEST, true).use { db ->
            assertThat(db.count("SELECT grams FROM weight_entries")).isEqualTo(84_200)
            // Handed to the profile the migration creates rather than left belonging to nobody.
            assertThat(db.count("SELECT profileId FROM weight_entries")).isEqualTo(1)
            assertThat(db.count("SELECT COUNT(*) FROM profiles")).isEqualTo(1)
        }
    }

    @Test
    fun everyRowGetsANameOfItsOwn() {
        // Every row gets a name of its own rather than all of them sharing one.
        // The thing that would be catastrophic and silent: rows sharing a name look like one
        // record to the merge, and a first sync collapses a whole history into a single reading.
        helper.createDatabase(NAME, 8).use { db ->
            for (index in 1..5) {
                db.execSQL(
                    "INSERT INTO water_entries " +
                        "(profileId, timestampUtcMillis, localDate, millilitres, healthConnectId, " +
                        "updatedAtUtcMillis) VALUES (1, ${1_700_000_000_000 + index}, " +
                        "'2023-11-14', 250, NULL, 1700000000000)",
                )
            }
        }

        helper.runMigrationsAndValidate(NAME, LATEST, true).use { db ->
            assertThat(db.count("SELECT COUNT(*) FROM water_entries")).isEqualTo(5)
            assertThat(db.count("SELECT COUNT(DISTINCT syncId) FROM water_entries")).isEqualTo(5)
            assertThat(db.count("SELECT COUNT(*) FROM water_entries WHERE syncId = ''"))
                .isEqualTo(0)
        }
    }

    @Test
    fun foodDiaryComesThroughWithNamesAndTimes() {
        // A food diary written before sync existed comes through with names and times.
        helper.createDatabase(NAME, 10).use { db ->
            db.execSQL(
                "INSERT INTO foods (name, brand, barcode, kcalPer100g, proteinPer100g, " +
                    "carbsPer100g, fatPer100g, fibrePer100g, sugarPer100g, saltPer100g, " +
                    "servingGrams, origin, favourite, lastUsedAtUtcMillis, updatedAtUtcMillis) " +
                    "VALUES ('Oats', NULL, NULL, 379.0, NULL, NULL, NULL, NULL, NULL, NULL, " +
                    "NULL, 'CUSTOM', 0, 0, 1700000000000)",
            )
            db.execSQL(
                "INSERT INTO food_log_entries (profileId, localDate, meal, foodId, name, grams, " +
                    "kcal, proteinG, carbsG, fatG, loggedAtUtcMillis) " +
                    "VALUES (1, '2023-11-14', 'BREAKFAST', 1, 'Oats', 100.0, 379.0, NULL, NULL, " +
                    "NULL, 1700000000000)",
            )
        }

        helper.runMigrationsAndValidate(NAME, LATEST, true).use { db ->
            assertThat(db.text("SELECT syncId FROM foods")).isNotEmpty()
            assertThat(db.text("SELECT syncId FROM food_log_entries")).isNotEmpty()
            // When it was eaten, rather than a zero that would make anything arriving from
            // another device look newer than it.
            assertThat(db.count("SELECT updatedAtUtcMillis FROM food_log_entries"))
                .isEqualTo(1_700_000_000_000)
        }
    }

    @Test
    fun goalKeepsTheTimeItWasMade() {
        // A goal keeps the time it was made rather than starting from nothing.
        helper.createDatabase(NAME, 8).use { db ->
            db.execSQL(
                "INSERT INTO goals (profileId, direction, startGrams, targetGrams, startDate, " +
                    "targetDate, milestoneStepGrams, active, createdAtUtcMillis) " +
                    "VALUES (1, 'LOSE', 90000, 80000, '2023-11-01', NULL, 2000, 1, 1700000000000)",
            )
        }

        helper.runMigrationsAndValidate(NAME, LATEST, true).use { db ->
            // A zero would make any goal arriving from another device look newer than this one.
            assertThat(db.count("SELECT updatedAtUtcMillis FROM goals"))
                .isEqualTo(1_700_000_000_000)
        }
    }

    private companion object {
        const val LATEST = 11
        const val NAME = "migration-test.db"
    }
}
