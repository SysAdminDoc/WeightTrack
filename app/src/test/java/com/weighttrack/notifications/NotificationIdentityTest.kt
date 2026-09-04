package com.weighttrack.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which notification is posted under which number.
 *
 * Two notifications sharing one identifier is not two notifications: the later one replaces the
 * earlier in the shade, silently. The test notification was posted at the reminder base plus
 * one, which is exactly the number the first profile's own reminder uses, so pressing "send a
 * test notification" while that reminder was showing answered "do reminders arrive?" by making
 * the one that had arrived disappear.
 */
class NotificationIdentityTest {

    @Test
    fun `the test notification is nobody's reminder`() {
        val everyone = (1L..64L).map { Notifications.reminderIdFor(it) }

        assertThat(everyone).doesNotContain(Notifications.TEST_NOTIFICATION_ID)
    }

    @Test
    fun `two people do not share a reminder`() {
        val everyone = (1L..64L).map { Notifications.reminderIdFor(it) }

        assertThat(everyone.toSet()).hasSize(64)
    }

    @Test
    fun `the weekly summary is nobody's reminder either`() {
        val everyone = (1L..64L).map { Notifications.reminderIdFor(it) }

        assertThat(everyone).doesNotContain(WeeklySummaryScheduler.NOTIFICATION_ID)
        assertThat(Notifications.TEST_NOTIFICATION_ID)
            .isNotEqualTo(WeeklySummaryScheduler.NOTIFICATION_ID)
    }
}
