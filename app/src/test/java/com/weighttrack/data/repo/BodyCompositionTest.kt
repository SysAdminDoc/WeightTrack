package com.weighttrack.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.BodyComposition
import com.weighttrack.core.model.CompositionQuality
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.sync.SyncDocument
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.io.BackupCodec
import com.weighttrack.data.io.BackupFile
import com.weighttrack.data.io.WeightCsvExporter
import com.weighttrack.data.sync.SyncStore
import com.weighttrack.data.testSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * What a body-composition scale sends, kept.
 *
 * The parsers have always read muscle mass, fat-free mass, soft lean mass, body water, muscle
 * percentage, impedance and basal metabolism, the scale screen has always shown them, and saving
 * kept the weight and the body-fat percentage and dropped the rest without a word. Somebody
 * watching their muscle mass on the scale's own display had no way to keep it and nothing told
 * them so.
 */
@RunWith(RobolectricTestRunner::class)
class BodyCompositionTest {

    private lateinit var database: WeightTrackDatabase
    private lateinit var profiles: ProfileRepository
    private lateinit var weights: WeightRepository

    /** Everything a full capture carries, with nothing left null. */
    private val full = BodyComposition(
        muscleMassGrams = 34_200,
        fatFreeMassGrams = 58_400,
        softLeanMassGrams = 55_100,
        bodyWaterMassGrams = 42_800,
        musclePercent = 42.75,
        impedanceOhms = 512.5,
        basalMetabolismKcal = 1_684.0,
        scaleBmi = 24.3,
        scaleUserId = 2,
        device = "Renpho ES-CS20M",
        protocol = "RENPHO",
        quality = CompositionQuality.REPORTED_BY_SCALE,
    )

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
        weights = WeightRepository(database.weightEntryDao(), profiles, deletions)
    }

    @After
    fun tearDown() = database.close()

    private suspend fun record(composition: BodyComposition?): Long {
        profiles.ensureDefault()
        return weights.add(
            grams = 80_000,
            timestamp = Instant.ofEpochMilli(1_800_000_000_000),
            bodyFatPercent = 22.5,
            source = EntrySource.SCALE,
            composition = composition,
        )
    }

    @Test
    fun `every field a scale sent comes back off the row`() = runTest {
        record(full)

        val stored = weights.observeEntries().first().single()

        // Field by field, not as a lump: a mapper that dropped one of these is exactly what this
        // is about, and a count would not see it.
        assertThat(stored.composition).isEqualTo(full)
    }

    @Test
    fun `a scale that only said its name recorded no composition`() = runTest {
        // What a plain Bluetooth scale gives: a weight, and the name it advertises. Treating
        // "it told us what it is called" as composition wrote three columns and shipped them to
        // every other device for a reading that has none.
        record(BodyComposition(device = "Plain scale", protocol = "STANDARD"))

        val stored = weights.observeEntries().first().single()

        assertThat(stored.composition).isNull()
    }

    @Test
    fun `a reading that carried only the scale's user slot is kept`() = runTest {
        // A broadcast from a family scale can carry nothing but which slot it filed the weight
        // under, and that is the one thing that says who stood on it.
        record(BodyComposition(scaleUserId = 3))

        val stored = weights.observeEntries().first().single()

        assertThat(stored.composition?.scaleUserId).isEqualTo(3)
    }

    @Test
    fun `the height the scale used is kept as well`() = runTest {
        // A standards-compliant scale reports it beside the composition, and the parser has
        // always read it. It is what every figure the scale worked out was worked out from.
        record(full.copy(heightMm = 1_803))

        val stored = weights.observeEntries().first().single()

        assertThat(stored.composition?.heightMm).isEqualTo(1_803)
    }

    @Test
    fun `a scale that only weighs records a complete reading`() = runTest {
        record(null)

        val stored = weights.observeEntries().first().single()

        // Not a failure and not a partial capture. A plain scale sends a weight, and a row with
        // no composition has to be visibly different from one whose figures went missing.
        assertThat(stored.grams).isEqualTo(80_000)
        assertThat(stored.composition).isNull()
    }

    @Test
    fun `a half-empty capture keeps what it had and claims nothing else`() = runTest {
        val partial = BodyComposition(
            impedanceOhms = 480.0,
            device = "Some scale",
            protocol = "STANDARD",
        )

        record(partial)

        val stored = checkNotNull(weights.observeEntries().first().single().composition)
        assertThat(stored.impedanceOhms).isEqualTo(480.0)
        assertThat(stored.muscleMassGrams).isNull()
        assertThat(stored.hasAnything).isTrue()
    }

    @Test
    fun `it travels to the other device whole`() = runTest {
        record(full)
        val other = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()

        try {
            val document = SyncStore(database, database.syncDao(), database.deletionDao())
                .snapshot("phone", System.currentTimeMillis())
            SyncStore(other, other.syncDao(), other.deletionDao()).apply(
                checkNotNull(SyncDocument.decode(SyncDocument.encode(document))),
                System.currentTimeMillis(),
            )

            val there = other.syncDao().weights().single()
            assertThat(there.muscleMassGrams).isEqualTo(34_200)
            assertThat(there.fatFreeMassGrams).isEqualTo(58_400)
            assertThat(there.softLeanMassGrams).isEqualTo(55_100)
            assertThat(there.bodyWaterMassGrams).isEqualTo(42_800)
            assertThat(there.musclePercent).isEqualTo(42.75)
            assertThat(there.impedanceOhms).isEqualTo(512.5)
            assertThat(there.basalMetabolismKcal).isEqualTo(1_684.0)
            assertThat(there.scaleBmi).isEqualTo(24.3)
            assertThat(there.scaleUserId).isEqualTo(2)
            assertThat(there.compositionDevice).isEqualTo("Renpho ES-CS20M")
            assertThat(there.compositionProtocol).isEqualTo("RENPHO")
            assertThat(there.compositionQuality).isEqualTo("REPORTED_BY_SCALE")
        } finally {
            other.close()
        }
    }

    @Test
    fun `it survives the backup file`() = runTest {
        record(full)
        val document = SyncStore(database, database.syncDao(), database.deletionDao())
            .snapshot("backup", System.currentTimeMillis())

        val text = BackupCodec.encode(
            BackupFile(exportedAtUtcMillis = 1_800_000_000_000, document = document),
        )
        val back = checkNotNull(BackupCodec.decode(text)).document

        assertThat(back?.weights?.single()?.muscleMassGrams).isEqualTo(34_200)
        assertThat(back?.weights?.single()?.impedanceOhms).isEqualTo(512.5)
        assertThat(back?.weights?.single()?.compositionQuality).isEqualTo("REPORTED_BY_SCALE")
    }

    @Test
    fun `the export carries it in the app's own units and says what it is worth`() = runTest {
        record(full)
        val entries = weights.observeEntries().first()

        val csv = WeightCsvExporter.toCsv(entries)

        // Kilograms, because that is what the rest of the file is in. A scale that reported
        // pounds and an export that repeated them would put two units in one column.
        val row = csv.lines()[1]
        assertThat(csv.lines().first()).contains("muscle_mass_kg")
        assertThat(row).contains("34.200")
        assertThat(row).contains("512.5")
        assertThat(row).contains("Renpho ES-CS20M")
        assertThat(row).contains("REPORTED_BY_SCALE")
    }

    @Test
    fun `an export of a weight-only reading leaves those columns empty`() = runTest {
        record(null)
        val entries = weights.observeEntries().first()

        val csv = WeightCsvExporter.toCsv(entries)

        // Empty, not zero. Nought grams of muscle is a claim; nothing measured is a fact.
        val header = csv.lines().first().split(",")
        val row = csv.lines()[1].split(",")
        assertThat(row[header.indexOf("muscle_mass_kg")]).isEmpty()
        assertThat(row[header.indexOf("impedance_ohms")]).isEmpty()
        assertThat(row[header.indexOf("composition_quality")]).isEmpty()
    }
}
