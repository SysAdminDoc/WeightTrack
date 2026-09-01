package com.weighttrack.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.MeasurementType
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.io.WeightCsvExporter
import com.weighttrack.data.testSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Recording a whole set of measurements at once.
 *
 * Thirteen sites retyped every time is why people stop measuring, so a set starts filled in from
 * the last one and only the values somebody changes count as measured. The rest are written too,
 * because a chart of one site full of holes is no use, but they are marked: a carried value is a
 * fact about the last time somebody got the tape out, not about today.
 */
@RunWith(RobolectricTestRunner::class)
class MeasurementSetTest {

    private lateinit var database: WeightTrackDatabase
    private lateinit var profiles: ProfileRepository
    private lateinit var measurements: MeasurementRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        val settings = testSettingsRepository()
        val deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao())
        profiles = ProfileRepository(
            database.profileDao(),
            settings,
            deletions,
            database.weightEntryDao(),
        )
        measurements = MeasurementRepository(database.measurementDao(), profiles, deletions)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `a set with nothing measured writes nothing at all`() = runTest {
        profiles.ensureDefault()

        val written = measurements.addSet(
            measured = emptyMap(),
            carried = mapOf(
                MeasurementType.WAIST to 880,
                MeasurementType.CHEST to 1_020,
            ),
        )

        assertThat(written).isEqualTo(0)
        assertThat(measurements.observeAll().first()).isEmpty()
    }

    @Test
    fun `changing one site writes the whole set, and says which one was measured`() = runTest {
        profiles.ensureDefault()

        measurements.addSet(
            measured = mapOf(MeasurementType.WAIST to 865),
            carried = mapOf(
                MeasurementType.CHEST to 1_020,
                MeasurementType.HIPS to 990,
                MeasurementType.NECK to 380,
            ),
        )

        val saved = measurements.observeAll().first().associateBy { it.type }
        assertThat(saved.keys).containsExactly(
            MeasurementType.WAIST,
            MeasurementType.CHEST,
            MeasurementType.HIPS,
            MeasurementType.NECK,
        )
        assertThat(saved.getValue(MeasurementType.WAIST).carried).isFalse()
        assertThat(saved.getValue(MeasurementType.CHEST).carried).isTrue()
        assertThat(saved.getValue(MeasurementType.HIPS).carried).isTrue()
        // All on the same day, or they are not a set.
        assertThat(saved.values.map { it.localDate }.distinct()).hasSize(1)
    }

    @Test
    fun `a value nobody has ever measured is not invented`() = runTest {
        profiles.ensureDefault()

        measurements.addSet(
            measured = mapOf(MeasurementType.WAIST to 865),
            carried = mapOf(MeasurementType.CHEST to 0),
        )

        val saved = measurements.observeAll().first()
        assertThat(saved.map { it.type }).containsExactly(MeasurementType.WAIST)
    }

    @Test
    fun `a single measurement is still not carried`() = runTest {
        profiles.ensureDefault()

        measurements.add(type = MeasurementType.WAIST, valueMm = 870)

        assertThat(measurements.observeAll().first().single().carried).isFalse()
    }

    @Test
    fun `the export says which values were carried`() = runTest {
        profiles.ensureDefault()
        measurements.addSet(
            measured = mapOf(MeasurementType.WAIST to 865),
            carried = mapOf(MeasurementType.CHEST to 1_020),
        )

        val csv = WeightCsvExporter.measurementsToCsv(measurements.observeAll().first())
        val rows = csv.trim().lines()

        assertThat(rows.first()).endsWith("carried")
        val waist = rows.first { it.contains("WAIST") }
        val chest = rows.first { it.contains("CHEST") }
        assertThat(waist).endsWith(",")
        assertThat(chest).endsWith("yes")
    }
}
