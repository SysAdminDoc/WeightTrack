package com.weighttrack.core.sync

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.WeightUnit
import org.junit.Test

class WearSyncTest {

    private val summary = WearSummary(
        trendGrams = 82_500,
        latestGrams = 83_100,
        weekChangeGrams = -420.0,
        goalGrams = 78_000,
        weightUnit = WeightUnit.LB,
        lastLoggedEpochDay = 20_000,
        entryCount = 214,
    )

    @Test
    fun `a summary survives the trip to the watch`() {
        assertThat(WearSync.decodeSummary(WearSync.encode(summary))).isEqualTo(summary)
    }

    @Test
    fun `a reading survives the trip to the phone`() {
        val log = WearWeightLog(
            grams = 82_450,
            timestampUtcMillis = 1_800_000_000_000,
            clientRecordId = "watch-1",
        )
        assertThat(WearSync.decodeWeightLog(WearSync.encode(log))).isEqualTo(log)
    }

    @Test
    fun `a payload this build cannot read is ignored rather than thrown`() {
        // The watch and the phone are updated separately, so they will not always match.
        assertThat(WearSync.decodeSummary(null)).isNull()
        assertThat(WearSync.decodeSummary(ByteArray(0))).isNull()
        assertThat(WearSync.decodeSummary("not json".encodeToByteArray())).isNull()
        assertThat(WearSync.decodeWeightLog("{}".encodeToByteArray())).isNull()
    }

    @Test
    fun `a field added by a newer phone does not stop the watch reading the rest`() {
        val fromNewerBuild = """{"trendGrams":82500,"entryCount":3,"somethingNew":"x"}"""
        val decoded = WearSync.decodeSummary(fromNewerBuild.encodeToByteArray())

        assertThat(decoded).isNotNull()
        assertThat(decoded!!.trendGrams).isEqualTo(82_500)
        assertThat(decoded.weightUnit).isEqualTo(WeightUnit.KG)
    }

    @Test
    fun `each reading gets its own path so two queued together both survive`() {
        val first = WearSync.logPath("a")
        val second = WearSync.logPath("b")

        assertThat(first).isNotEqualTo(second)
        assertThat(WearSync.isLogPath(first)).isTrue()
        assertThat(WearSync.isLogPath(second)).isTrue()
        // The prefix on its own is not a reading, and neither is the summary.
        assertThat(WearSync.isLogPath(WearSync.PATH_LOG_WEIGHT)).isFalse()
        assertThat(WearSync.isLogPath(WearSync.PATH_SUMMARY)).isFalse()
        assertThat(WearSync.isLogPath(null)).isFalse()
    }

    @Test
    fun `a locked summary carries no weight at all`() {
        val locked = WearSummary(weightUnit = WeightUnit.KG, hidden = true)
        val decoded = WearSync.decodeSummary(WearSync.encode(locked))!!

        assertThat(decoded.hasData).isFalse()
        assertThat(decoded.startingGrams).isNull()
    }

    @Test
    fun `the picker opens on the trend, falling back to the last reading`() {
        assertThat(summary.startingGrams).isEqualTo(82_500)
        assertThat(summary.copy(trendGrams = null).startingGrams).isEqualTo(83_100)
        assertThat(summary.copy(trendGrams = null, latestGrams = null).startingGrams).isNull()
    }
}
