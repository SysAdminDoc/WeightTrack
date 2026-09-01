package com.weighttrack.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.weighttrack.data.db.FoodEntity
import com.weighttrack.data.db.FoodLogEntryEntity
import com.weighttrack.data.db.MeasurementEntity
import com.weighttrack.data.db.WeightEntryEntity
import com.weighttrack.data.db.WeightTrackDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fills the database with the history the performance fixture is measured against.
 *
 * Twenty years of daily weigh-ins, five years of meals and twenty years of monthly measurements.
 * That is well past what anybody has, which is the point: the number worth defending is what the
 * app does with a history it was never sized for, not what it does with the fortnight the
 * developer happens to have.
 *
 * Only compiled into the `benchmark` build type, which nobody installs. It exists in the app
 * rather than in the benchmark module because the benchmark drives the app from another process
 * and cannot reach its database.
 */
class FixtureSeeder : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Access {
        fun database(): WeightTrackDatabase
        fun settings(): com.weighttrack.data.prefs.SettingsRepository
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val access = EntryPointAccessors
            .fromApplication(context.applicationContext, Access::class.java)

        // Pending, so the shell broadcast that sent this waits for the rows to land rather than
        // returning while a benchmark starts measuring an empty database. Off the main thread,
        // because thirteen thousand rows on it is an ANR rather than a fixture.
        val finished = goAsync()
        Thread {
            val seeded = runCatching { runBlocking { seed(access) } }.isSuccess
            finished.resultCode = if (seeded) SEEDED else FAILED
            finished.finish()
        }.start()
    }

    private suspend fun seed(access: Access) {
        val database = access.database()
        // Past the welcome screens, or every measurement here is of onboarding rather than of a
        // history. A fresh install has never been through them, and the fixture is always a
        // fresh install.
        access.settings().setOnboardingComplete(true)
        // Cleared first. A fixture that grows every time it is run measures a different history
        // on every pass, which is the one thing a regression budget cannot survive.
        database.clearAllTables()
        database.profileDao().insert(
            com.weighttrack.data.db.ProfileEntity(
                id = WeightTrackDatabase.DEFAULT_PROFILE_ID,
                name = WeightTrackDatabase.DEFAULT_PROFILE_NAME,
                position = 0,
                createdAtUtcMillis = 0,
            ),
        )

        val today = LocalDate.now()
        // Deterministic. The same history every run, or two runs are not comparable.
        val noise = Random(20_260_831)

        database.profileDao().insertWeightEntries(weights(today, noise))
        database.profileDao().insertMeasurements(measurements(today))
        val food = database.foodDao().insert(staple())
        database.profileDao().insertFoodLog(meals(today, food, noise))
    }

    /** Twenty years of mornings, drifting down with a seasonal swing and a kilo of water noise. */
    private fun weights(today: LocalDate, noise: Random): List<WeightEntryEntity> =
        (0 until WEIGH_IN_DAYS).map { back ->
            val date = today.minusDays(back.toLong())
            val at = date.atTime(7, 15).toInstant(ZoneOffset.UTC).toEpochMilli()
            val years = back / 365.0
            val grams = 78_000 + (years * 400).toInt() +
                (sin(back / 58.0) * 900).toInt() +
                noise.nextInt(-700, 700)
            WeightEntryEntity(
                profileId = WeightTrackDatabase.DEFAULT_PROFILE_ID,
                timestampUtcMillis = at,
                zoneOffsetSeconds = 0,
                localDate = date.toString(),
                grams = grams,
                bodyFatPercent = if (back % 4 == 0) 22.0 + noise.nextDouble(-1.5, 1.5) else null,
                note = null,
                tags = "",
                source = "MANUAL",
                clientRecordId = "bench-w-$back",
                healthConnectId = null,
                updatedAtUtcMillis = at,
            )
        }

    /** One set of tape measurements a month, for the whole twenty years. */
    private fun measurements(today: LocalDate): List<MeasurementEntity> =
        (0 until MEASUREMENT_MONTHS).flatMap { back ->
            val date = today.minusMonths(back.toLong())
            val at = date.atTime(8, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
            listOf("WAIST" to 880 - back, "CHEST" to 1_020 - back, "HIP" to 990 - back)
                .map { (type, mm) ->
                    MeasurementEntity(
                        profileId = WeightTrackDatabase.DEFAULT_PROFILE_ID,
                        timestampUtcMillis = at,
                        localDate = date.toString(),
                        type = type,
                        valueMm = mm,
                        note = null,
                        updatedAtUtcMillis = at,
                    )
                }
        }

    /** Five years of eating, three meals a day. */
    private fun meals(today: LocalDate, foodId: Long, noise: Random): List<FoodLogEntryEntity> =
        (0 until DIARY_DAYS).flatMap { back ->
            val date = today.minusDays(back.toLong())
            listOf("BREAKFAST" to 480.0, "LUNCH" to 720.0, "DINNER" to 910.0)
                .mapIndexed { index, (meal, kcal) ->
                    val at = date.atTime(8 + index * 5, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
                    FoodLogEntryEntity(
                        profileId = WeightTrackDatabase.DEFAULT_PROFILE_ID,
                        localDate = date.toString(),
                        meal = meal,
                        foodId = foodId,
                        name = "Fixture meal",
                        grams = 250.0,
                        kcal = kcal + noise.nextInt(-90, 90),
                        proteinG = 30.0,
                        carbsG = 60.0,
                        fatG = 20.0,
                        loggedAtUtcMillis = at,
                        updatedAtUtcMillis = at,
                    )
                }
        }

    private fun staple() = FoodEntity(
        name = "Fixture staple",
        brand = null,
        barcode = null,
        kcalPer100g = 210.0,
        proteinPer100g = 12.0,
        carbsPer100g = 24.0,
        fatPer100g = 8.0,
        fibrePer100g = 3.0,
        sugarPer100g = 4.0,
        saltPer100g = 0.6,
        servingGrams = 250.0,
        origin = "MANUAL",
        updatedAtUtcMillis = 0,
    )

    companion object {
        const val ACTION = "com.weighttrack.benchmark.SEED"

        /** The result the shell broadcast reads, so a silent failure cannot look like success. */
        const val SEEDED = 42

        /** Anything went wrong. A benchmark against an empty database is worse than none. */
        const val FAILED = 13

        /** Twenty years of daily weigh-ins. */
        const val WEIGH_IN_DAYS = 7_300

        /** Five years of meals. */
        const val DIARY_DAYS = 1_825

        /** Twenty years of monthly measurements. */
        const val MEASUREMENT_MONTHS = 240
    }
}
