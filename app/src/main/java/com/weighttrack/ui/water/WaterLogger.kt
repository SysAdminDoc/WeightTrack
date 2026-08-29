package com.weighttrack.ui.water

import com.weighttrack.data.repo.WaterRepository
import com.weighttrack.health.HealthConnectSync
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * The one place a drink gets recorded.
 *
 * Shared by the water screen and the home screen widget so the widget's one-tap add is not a
 * lesser path: it lands on the same day, stores the same identifier, and reaches Health
 * Connect the same way.
 */
object WaterLogger {

    /**
     * Records [millilitres] against [onDate].
     *
     * A drink added to today is stamped with the current time. One added to an earlier day is
     * stamped at midday on that day, so it counts towards the day the person picked instead of
     * silently landing on today, which is what happens if you always use the current instant.
     *
     * The local row is written first and always. Health Connect is best effort: the drink is
     * already saved, and a refused permission must not turn a tap into a lost record.
     */
    suspend fun log(
        millilitres: Int,
        onDate: LocalDate,
        waterRepository: WaterRepository,
        healthConnect: HealthConnectSync,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): Long {
        if (millilitres <= 0) return -1
        val timestamp = timestampFor(onDate, zone, now)
        val clientRecordId = "water:${UUID.randomUUID()}"

        val id = waterRepository.add(millilitres = millilitres, timestamp = timestamp, zone = zone)
        if (id <= 0) return id

        val written = healthConnect.writeHydration(millilitres, timestamp, clientRecordId)
        if (written) waterRepository.markSyncedToHealthConnect(id, clientRecordId)
        return id
    }

    internal fun timestampFor(
        onDate: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): Instant = if (onDate == now.atZone(zone).toLocalDate()) {
        now
    } else {
        // Midday, so the entry cannot slip either side of the date boundary once daylight
        // saving or a timezone change is applied to it.
        onDate.atTime(LocalTime.NOON).atZone(zone).toInstant()
    }
}
