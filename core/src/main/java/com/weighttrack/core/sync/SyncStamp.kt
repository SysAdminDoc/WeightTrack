package com.weighttrack.core.sync

/**
 * When a version of a record was made, and on which device.
 *
 * The millisecond alone is not enough to order edits across phones. Two devices can write in the
 * same millisecond, and one of them can have its clock set wrong, so an ordering built on the
 * wall clock alone is neither total nor stable. This pairs the time with the device that made the
 * edit, which gives every version of every record one place in one order that every device works
 * out identically.
 *
 * The device is the one that made this version, not the one whose file it arrived in. A record
 * relayed by a third phone keeps the name of the phone that wrote it, which is what stops the
 * answer depending on which files happened to be in the folder.
 */
data class SyncStamp(val millis: Long, val deviceId: String) : Comparable<SyncStamp> {
    override fun compareTo(other: SyncStamp): Int =
        compareValuesBy(this, other, { it.millis }, { it.deviceId })
}

/**
 * The clock the app stamps its edits with.
 *
 * A hybrid of the wall clock and a logical one: it reads the phone's time, but it never goes
 * backwards and it never falls behind anything it has been shown. That is the whole of what makes
 * "newest wins" safe on devices that do not share a clock.
 *
 * Without it, two ordinary things break sync. A phone whose clock is ten minutes slow holds a
 * stale edit in place, because its later correction still carries an earlier time than the
 * version it was meant to replace. And a phone that jumps backwards, which happens when a
 * network time update lands or somebody sets the date by hand, writes edits that sort before
 * edits it already made, so its own history stops being in order.
 *
 * [observe] is how it catches up: every stamp read out of somebody else's file raises the floor,
 * so the next local edit is later than everything this device has seen. That is the property that
 * makes a correction beat the thing it corrects, however far apart the two clocks are.
 *
 * A peer whose clock is wildly ahead is not followed all the way. Taking its word would drag this
 * device's clock years into the future and keep it there, and every edit made here afterwards
 * would be stamped with a date nobody can explain. [MAX_DRIFT_MILLIS] is how far ahead of the
 * phone's own time this will go; beyond that the peer simply wins the ordering, which is the
 * lesser of the two problems and the honest one.
 */
class HybridClock(initialState: Long = 0) {

    private var state: Long = initialState

    /** A stamp for an edit made here, now. Strictly later than every stamp issued or seen. */
    @Synchronized
    fun next(physicalNow: Long): Long {
        state = maxOf(physicalNow, state + 1)
        return state
    }

    /**
     * Raises the floor to a stamp read from somewhere else.
     *
     * Capped, so one peer with a broken clock cannot move this device's clock permanently.
     */
    @Synchronized
    fun observe(seen: Long, physicalNow: Long) {
        val ceiling = physicalNow + MAX_DRIFT_MILLIS
        val usable = minOf(seen, ceiling)
        if (usable > state) state = usable
    }

    /** What the clock holds, for storing between runs. */
    @Synchronized
    fun state(): Long = state

    companion object {
        /**
         * How far ahead of this phone's own time another device's stamp is believed.
         *
         * A day is generous for a timezone or a daylight-saving mistake and far short of the
         * years a phone with no battery reports when it starts at the epoch, or the decades a
         * mistyped date produces.
         */
        const val MAX_DRIFT_MILLIS: Long = 24L * 60 * 60 * 1000
    }
}

fun SyncProfile.stamp() = SyncStamp(updatedAtUtcMillis, stampDeviceId)
fun SyncWeight.stamp() = SyncStamp(updatedAtUtcMillis, stampDeviceId)
fun SyncMeasurement.stamp() = SyncStamp(updatedAtUtcMillis, stampDeviceId)
fun SyncWater.stamp() = SyncStamp(updatedAtUtcMillis, stampDeviceId)
fun SyncFast.stamp() = SyncStamp(updatedAtUtcMillis, stampDeviceId)
fun SyncGoal.stamp() = SyncStamp(updatedAtUtcMillis, stampDeviceId)
fun SyncMacroTarget.stamp() = SyncStamp(updatedAtUtcMillis, stampDeviceId)
fun SyncFood.stamp() = SyncStamp(updatedAtUtcMillis, stampDeviceId)
fun SyncRecipe.stamp() = SyncStamp(updatedAtUtcMillis, stampDeviceId)
fun SyncRecipeItem.stamp() = SyncStamp(updatedAtUtcMillis, stampDeviceId)
fun SyncFoodLogEntry.stamp() = SyncStamp(updatedAtUtcMillis, stampDeviceId)
fun SyncSettings.stamp() = SyncStamp(updatedAtUtcMillis, stampDeviceId)
fun SyncMedicationDose.stamp() = SyncStamp(updatedAtUtcMillis, stampDeviceId)
fun SyncSideEffect.stamp() = SyncStamp(updatedAtUtcMillis, stampDeviceId)
