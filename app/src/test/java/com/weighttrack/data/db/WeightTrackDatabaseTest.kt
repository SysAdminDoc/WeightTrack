package com.weighttrack.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.model.EntryTag
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.core.model.MeasurementType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class WeightTrackDatabaseTest {

    private lateinit var database: WeightTrackDatabase
    private lateinit var weightDao: WeightEntryDao
    private lateinit var measurementDao: MeasurementDao
    private lateinit var goalDao: GoalDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        weightDao = database.weightEntryDao()
        measurementDao = database.measurementDao()
        goalDao = database.goalDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun entry(
        day: Int,
        grams: Int,
        clientRecordId: String = "client-$day-$grams",
        healthConnectId: String? = null,
        hourOfDay: Int = 8,
    ): WeightEntryEntity {
        val date = LocalDate.of(2026, 1, 1).plusDays(day.toLong())
        return WeightEntryEntity(
            timestampUtcMillis = date.atStartOfDay().plusHours(hourOfDay.toLong())
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli(),
            zoneOffsetSeconds = 0,
            localDate = date.toString(),
            grams = grams,
            bodyFatPercent = null,
            note = null,
            tags = "",
            source = EntrySource.MANUAL.name,
            clientRecordId = clientRecordId,
            healthConnectId = healthConnectId,
            updatedAtUtcMillis = 0,
        )
    }

    @Test
    fun `an inserted reading comes back`() = runTest {
        weightDao.insert(entry(day = 0, grams = 80_000))
        val all = weightDao.observeAll().first()
        assertThat(all).hasSize(1)
        assertThat(all.single().grams).isEqualTo(80_000)
    }

    @Test
    fun `readings are listed newest first`() = runTest {
        weightDao.insert(entry(day = 0, grams = 80_000))
        weightDao.insert(entry(day = 5, grams = 79_000))
        weightDao.insert(entry(day = 2, grams = 79_500))
        assertThat(weightDao.observeAll().first().map { it.grams })
            .containsExactly(79_000, 79_500, 80_000)
            .inOrder()
    }

    @Test
    fun `daily averages collapse several readings on one day`() = runTest {
        weightDao.insert(entry(day = 0, grams = 80_000, hourOfDay = 7))
        weightDao.insert(entry(day = 0, grams = 81_000, hourOfDay = 20))
        weightDao.insert(entry(day = 1, grams = 79_000))

        val rows = weightDao.observeDailyAverages().first()
        assertThat(rows).hasSize(2)
        assertThat(rows.first().localDate).isEqualTo("2026-01-01")
        assertThat(rows.first().grams).isWithin(0.001).of(80_500.0)
    }

    @Test
    fun `latest and earliest bracket the log`() = runTest {
        weightDao.insert(entry(day = 3, grams = 79_000))
        weightDao.insert(entry(day = 0, grams = 80_000))
        weightDao.insert(entry(day = 7, grams = 78_000))
        assertThat(weightDao.latest()!!.grams).isEqualTo(78_000)
        assertThat(weightDao.earliest()!!.grams).isEqualTo(80_000)
    }

    @Test
    fun `a repeated sync of the same record does not duplicate it`() = runTest {
        // Health Connect sync runs over overlapping windows, so the same record arrives again
        // and again. Identity upsert is what keeps the log from growing a copy every pass.
        val incoming = entry(day = 0, grams = 80_000, clientRecordId = "hc-1", healthConnectId = "uid-1")
        val firstId = weightDao.upsertByIdentity(incoming)
        val secondId = weightDao.upsertByIdentity(incoming.copy(grams = 80_200))
        val thirdId = weightDao.upsertByIdentity(incoming.copy(grams = 80_200))

        assertThat(weightDao.count()).isEqualTo(1)
        assertThat(firstId).isEqualTo(secondId)
        assertThat(secondId).isEqualTo(thirdId)
        assertThat(weightDao.latest()!!.grams).isEqualTo(80_200)
    }

    @Test
    fun `a record recognised only by its health connect id still updates in place`() = runTest {
        weightDao.insert(entry(day = 0, grams = 80_000, clientRecordId = "ours", healthConnectId = "uid-9"))
        // A later read hands back the same Health Connect record under a different client id.
        weightDao.upsertByIdentity(
            entry(day = 0, grams = 80_500, clientRecordId = "theirs", healthConnectId = "uid-9"),
        )
        assertThat(weightDao.count()).isEqualTo(1)
        assertThat(weightDao.latest()!!.grams).isEqualTo(80_500)
    }

    @Test
    fun `distinct records still both land`() = runTest {
        weightDao.upsertByIdentity(entry(day = 0, grams = 80_000, clientRecordId = "a"))
        weightDao.upsertByIdentity(entry(day = 1, grams = 79_000, clientRecordId = "b"))
        assertThat(weightDao.count()).isEqualTo(2)
    }

    @Test
    fun `entries can be found by day, id and client record id`() = runTest {
        val id = weightDao.insert(entry(day = 4, grams = 79_000, clientRecordId = "find-me"))
        assertThat(weightDao.byId(id)!!.grams).isEqualTo(79_000)
        assertThat(weightDao.byClientRecordId("find-me")!!.id).isEqualTo(id)
        assertThat(weightDao.byLocalDate("2026-01-05")).hasSize(1)
        assertThat(weightDao.byLocalDate("2026-01-06")).isEmpty()
    }

    @Test
    fun `search matches notes and tags`() = runTest {
        weightDao.insert(
            entry(day = 0, grams = 80_000).copy(note = "after a long flight", tags = EntryTag.TRAVEL.name),
        )
        weightDao.insert(entry(day = 1, grams = 79_000).copy(note = "normal morning"))

        assertThat(weightDao.search("flight").first()).hasSize(1)
        assertThat(weightDao.search("TRAVEL").first()).hasSize(1)
        assertThat(weightDao.search("").first()).hasSize(2)
        assertThat(weightDao.search("nothing here").first()).isEmpty()
    }

    @Test
    fun `bulk delete removes exactly the chosen rows`() = runTest {
        val first = weightDao.insert(entry(day = 0, grams = 80_000))
        val second = weightDao.insert(entry(day = 1, grams = 79_500))
        weightDao.insert(entry(day = 2, grams = 79_000))

        weightDao.deleteByIds(listOf(first, second))
        assertThat(weightDao.count()).isEqualTo(1)
        assertThat(weightDao.latest()!!.grams).isEqualTo(79_000)
    }

    @Test
    fun `body fat lookup finds the newest reading that carries one`() = runTest {
        weightDao.insert(entry(day = 0, grams = 80_000).copy(bodyFatPercent = 22.0))
        weightDao.insert(entry(day = 5, grams = 79_000))
        assertThat(weightDao.latestWithBodyFat()!!.bodyFatPercent).isWithin(1e-9).of(22.0)
    }

    @Test
    fun `latest measurement per type ignores older readings`() = runTest {
        val base = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        suspend fun measure(type: MeasurementType, mm: Int, dayOffset: Int) {
            measurementDao.insert(
                MeasurementEntity(
                    timestampUtcMillis = base + dayOffset * 86_400_000L,
                    localDate = LocalDate.of(2026, 1, 1).plusDays(dayOffset.toLong()).toString(),
                    type = type.name,
                    valueMm = mm,
                    note = null,
                    updatedAtUtcMillis = 0,
                ),
            )
        }
        measure(MeasurementType.WAIST, 900, 0)
        measure(MeasurementType.WAIST, 880, 10)
        measure(MeasurementType.NECK, 380, 0)

        val latest = measurementDao.latestPerType().associateBy { it.type }
        assertThat(latest).hasSize(2)
        assertThat(latest[MeasurementType.WAIST.name]!!.valueMm).isEqualTo(880)
        assertThat(latest[MeasurementType.NECK.name]!!.valueMm).isEqualTo(380)
    }

    @Test
    fun `setting a goal retires the previous one`() = runTest {
        goalDao.replaceActive(
            GoalEntity(
                direction = GoalDirection.LOSE.name,
                startGrams = 100_000,
                targetGrams = 90_000,
                startDate = "2026-01-01",
                targetDate = null,
                milestoneStepGrams = 2_000,
                active = true,
                createdAtUtcMillis = 1,
            ),
        )
        goalDao.replaceActive(
            GoalEntity(
                direction = GoalDirection.LOSE.name,
                startGrams = 95_000,
                targetGrams = 85_000,
                startDate = "2026-02-01",
                targetDate = null,
                milestoneStepGrams = 2_000,
                active = true,
                createdAtUtcMillis = 2,
            ),
        )

        assertThat(goalDao.active()!!.targetGrams).isEqualTo(85_000)
        assertThat(goalDao.observeAll().first()).hasSize(2)
        assertThat(goalDao.observeAll().first().count { it.active }).isEqualTo(1)
    }

    @Test
    fun `no goal means no active goal`() = runTest {
        assertThat(goalDao.active()).isNull()
        assertThat(goalDao.observeActive().first()).isNull()
    }
}
