package com.weighttrack.data.sync

import com.weighttrack.core.sync.HybridClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The clock every edit that leaves this phone is stamped with.
 *
 * A [HybridClock] with somewhere to keep its state between runs. Without that it would restart at
 * the phone's own time on every launch, which is exactly the value that cannot be trusted: a
 * device whose clock went backwards while the app was closed would come back and stamp its next
 * correction earlier than the thing it is correcting.
 *
 * Where it writes itself down is a pair of functions rather than the preferences file, so that a
 * test, or anything running without a settings store, can have a clock without one.
 */
@Singleton
class SyncClock(
    private val load: suspend () -> Long,
    private val store: suspend (Long) -> Unit,
) {

    @Inject
    constructor(preferences: SyncPreferences) : this(
        load = { preferences.clockState() },
        store = { preferences.setClockState(it) },
    )

    private val loading = Mutex()

    @Volatile
    private var clock: HybridClock? = null

    private suspend fun clock(): HybridClock = clock ?: loading.withLock {
        clock ?: HybridClock(load()).also { clock = it }
    }

    /**
     * The stamp to give an edit whose own recorded time is [preferred].
     *
     * The recorded time is kept whenever it is already later than everything this device has
     * seen, which is the ordinary case and leaves a healthy phone's timestamps exactly as they
     * were. It is only replaced when keeping it would put the edit behind something it happened
     * after: a clock that has gone backwards, or one that was always slow.
     */
    suspend fun stampFor(preferred: Long, physicalNow: Long): Long {
        val clock = clock()
        if (preferred > clock.state()) {
            clock.observe(preferred, physicalNow)
            return preferred
        }
        return clock.next(physicalNow)
    }

    /** Raises the clock past a stamp read out of somebody else's file. */
    suspend fun observe(seen: Long, physicalNow: Long) {
        clock().observe(seen, physicalNow)
    }

    /** Writes the clock down, so a restart does not start it again from the phone's own time. */
    suspend fun persist() {
        val state = clock?.state() ?: return
        store(state)
    }

    companion object {
        /** A clock with nowhere to write itself down. */
        fun inMemory(initial: Long = 0): SyncClock {
            var held = initial
            return SyncClock(load = { held }, store = { held = it })
        }
    }
}
