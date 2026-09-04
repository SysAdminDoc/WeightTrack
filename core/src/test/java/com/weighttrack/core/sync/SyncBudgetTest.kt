package com.weighttrack.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * How much a peer is allowed to ask this phone to hold.
 *
 * A file in a shared folder is written by something outside this app and a WebDAV server is
 * somebody else's machine, so "it will be a sensible size" was a hope rather than a fact. Nothing
 * bounded what came back: not the bytes, not the number of records, not the length of one string.
 * A hundred megabytes of anything at all was enough to take the app down before a single line of
 * it had been parsed.
 */
class SyncBudgetTest {

    private fun bytes(count: Int) = ByteArrayInputStream(ByteArray(count) { 'x'.code.toByte() })

    private fun document(
        weights: List<SyncWeight> = emptyList(),
        profiles: List<SyncProfile> = emptyList(),
        foods: List<SyncFood> = emptyList(),
    ) = SyncDocument(
        deviceId = "peer",
        writtenAtUtcMillis = 1_800_000_000_000,
        profiles = profiles,
        weights = weights,
        foods = foods,
    )

    @Test
    fun `a file naming a crowd of devices is refused`() {
        // Every name here is something the tombstone rule then waits for before it will forget
        // a deletion, and a device that never publishes a file is waited for for ever. A file
        // full of invented names therefore stops every phone in the house forgetting anything,
        // with no way back short of erasing all the data.
        val crowd = (0..SyncBudget.MAX_PEERS).map { SyncPeer(deviceId = "device-" + it) }

        val problem = SyncBudget.problemWith(document().copy(peers = crowd))

        assertThat(problem).isNotNull()
        assertThat(problem).contains("devices")
    }

    @Test
    fun `a file naming a crowd of device positions is refused`() {
        val crowd = (0..SyncBudget.MAX_PEERS).map {
            SyncObservation(deviceId = "device-" + it, throughMillis = 1_800_000_000_000)
        }

        assertThat(SyncBudget.problemWith(document().copy(observed = crowd))).isNotNull()
    }

    @Test
    fun `a household's worth of devices is fine`() {
        val house = (1..8).map { SyncPeer(deviceId = "device-" + it) }

        assertThat(SyncBudget.problemWith(document().copy(peers = house))).isNull()
    }

    @Test
    fun `the name a file signs itself with is measured too`() {
        // It is on no row, and it reaches the peer table by its own door: the merge notes a peer
        // for every document it accepts. Left unmeasured, this phone persists the name, puts it
        // in its own list, and every other device then refuses this phone's file for good.
        val shouting = document().copy(deviceId = "d".repeat(SyncBudget.MAX_STRING + 1))

        assertThat(SyncBudget.problemWith(shouting)).isNotNull()
    }

    @Test
    fun `the device stamped on an ordinary row is measured too`() {
        val stamped = weight("w-1").copy(stampDeviceId = "d".repeat(SyncBudget.MAX_STRING + 1))

        assertThat(SyncBudget.problemWith(document(weights = listOf(stamped)))).isNotNull()
    }

    @Test
    fun `a device name nobody could have typed is refused`() {
        val shouting = SyncPeer(deviceId = "d".repeat(SyncBudget.MAX_STRING + 1))

        assertThat(SyncBudget.problemWith(document().copy(peers = listOf(shouting)))).isNotNull()
    }

    @Test
    fun `an injection note nobody could have typed is refused`() {
        // The injection log arrived after this budget was written and was never added to it, so
        // one dose carrying twenty million characters passed every check there was.
        val dose = SyncMedicationDose(
            syncId = "d-1",
            profileSyncId = "p",
            timestampUtcMillis = 1_800_000_000_000,
            localDate = "2026-09-04",
            drug = "TIRZEPATIDE",
            milligrams = 5.0,
            site = "ABDOMEN_LEFT",
            note = "n".repeat(SyncBudget.MAX_STRING + 1),
            updatedAtUtcMillis = 1_800_000_000_000,
        )

        assertThat(SyncBudget.problemWith(document().copy(medicationDoses = listOf(dose))))
            .isNotNull()
    }

    private fun profile() = SyncProfile(
        syncId = "p",
        name = "Me",
        position = 0,
        createdAtUtcMillis = 1_800_000_000_000,
        updatedAtUtcMillis = 1_800_000_000_000,
    )

    private fun weight(name: String, note: String? = null) = SyncWeight(
        syncId = name,
        profileSyncId = "p",
        timestampUtcMillis = 1_800_000_000_000,
        zoneOffsetSeconds = 0,
        localDate = "2026-08-29",
        grams = 80_000,
        note = note,
        source = "MANUAL",
        updatedAtUtcMillis = 1_800_000_000_000,
    )

    @Test
    fun `something the size of a real document reads`() {
        val text = SyncBudget.readBounded(bytes(1_024))

        assertThat(text).hasLength(1_024)
    }

    @Test
    fun `something past the ceiling reads as nothing at all`() {
        // Null rather than a truncated string. Half a document that parses is worse than none:
        // it looks like a device that has lost most of its history.
        val text = SyncBudget.readBounded(bytes(2_048), limit = 1_024)

        assertThat(text).isNull()
    }

    @Test
    fun `the ceiling is checked while reading, not from a length somebody reported`() {
        // A stream that says nothing about its length at all, which is what an HTTP body with no
        // content-length is. The bound has to hold anyway.
        val endless = object : java.io.InputStream() {
            var served = 0
            override fun read(): Int {
                served++
                return 'x'.code
            }
        }

        assertThat(SyncBudget.readBounded(endless, limit = 4_096)).isNull()
        // And it stopped rather than reading for ever.
        assertThat(endless.served).isLessThan(1_000_000)
    }

    @Test
    fun `an ordinary document is accepted`() {
        val ordinary = document(weights = (0 until 5_000).map { weight("w-$it") })

        assertThat(SyncBudget.problemWith(ordinary)).isNull()
    }

    @Test
    fun `a collection nobody could have filled is refused, and says which`() {
        val absurd = document(
            weights = (0..SyncBudget.MAX_RECORDS).map { weight("w-$it") },
        )

        val problem = SyncBudget.problemWith(absurd)

        assertThat(problem).isNotNull()
        assertThat(problem).contains("weights")
    }

    @Test
    fun `a note the length of a book is refused`() {
        val absurd = document(weights = listOf(weight("w-1", note = "x".repeat(50_000))))

        val problem = SyncBudget.problemWith(absurd)

        assertThat(problem).isNotNull()
        assertThat(problem).contains("characters")
    }

    @Test
    fun `a long-ish note that somebody might really write is accepted`() {
        val ordinary = document(weights = listOf(weight("w-1", note = "x".repeat(500))))

        assertThat(SyncBudget.problemWith(ordinary)).isNull()
    }

    @Test
    fun `a food name the length of a book is refused too`() {
        val absurd = document(
            foods = listOf(
                SyncFood(
                    syncId = "f-1",
                    name = "x".repeat(20_000),
                    kcalPer100g = 100.0,
                    origin = "CUSTOM",
                    updatedAtUtcMillis = 1_800_000_000_000,
                ),
            ),
        )

        assertThat(SyncBudget.problemWith(absurd)).isNotNull()
    }

    @Test
    fun `a list inside one row is a collection too`() {
        // Thirty megabytes of tags on a single weigh-in fits inside every other limit there is,
        // and lands as one database cell that every list rendering it then has to split.
        val absurd = document(
            weights = listOf(weight("w-1").copy(tags = (0..SyncBudget.MAX_TAGS).map { "t$it" })),
        )

        val problem = SyncBudget.problemWith(absurd)

        assertThat(problem).isNotNull()
        assertThat(problem).contains("tags")
    }

    @Test
    fun `a tag the length of a book is refused as well`() {
        val absurd = document(
            weights = listOf(weight("w-1").copy(tags = listOf("x".repeat(50_000)))),
        )

        assertThat(SyncBudget.problemWith(absurd)).isNotNull()
    }

    @Test
    fun `a name nobody typed is refused wherever it appears`() {
        // A list of the interesting fields goes stale the moment somebody adds one, so every
        // string a document carries is checked rather than a handful chosen by hand.
        val long = "x".repeat(50_000)

        assertThat(
            SyncBudget.problemWith(
                document(profiles = listOf(profile().copy(reminderDays = long))),
            ),
        ).isNotNull()
        assertThat(
            SyncBudget.problemWith(document(weights = listOf(weight("w-1").copy(source = long)))),
        ).isNotNull()
    }

    @Test
    fun `a handful of tags on a reading is perfectly ordinary`() {
        val ordinary = document(
            weights = listOf(weight("w-1").copy(tags = listOf("MORNING", "AFTER_EXERCISE"))),
        )

        assertThat(SyncBudget.problemWith(ordinary)).isNull()
    }

    @Test
    fun `the boundary itself is allowed`() {
        // One past is refused, exactly on it is not: a limit that refuses its own value makes
        // every message about it off by one and the argument unwinnable.
        val exactly = document(weights = listOf(weight("w-1", note = "x".repeat(SyncBudget.MAX_STRING))))

        assertThat(SyncBudget.problemWith(exactly)).isNull()
        assertThat(SyncBudget.readBounded(bytes(1_024), limit = 1_024)).hasLength(1_024)
    }
}
