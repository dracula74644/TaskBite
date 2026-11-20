package com.dracula.taskbite

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NotificationSoundTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Test fun existingPhoneChimeIsSuppressedForEveryReminderType() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.createNotificationChannel(NotificationChannel(Reminders.CHANNEL, "Task reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        })
        Reminders.createChannel(context)
        // Android keeps an existing channel's sound on upgrade. Each notice must mute it.
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, manager.getNotificationChannel(Reminders.CHANNEL).sound)
        val task = Task(title = "Keep the voice, lose the chime", dueAt = System.currentTimeMillis())
        Reminders.notify(context, task, "A human recording.")
        Reminders.notify(context, task, "A human recording.", awayTitle = "While away")
        Reminders.notifyPlanning(context, "Plan the day.")
        Reminders.notifyPlanning(context, "Empty list.", followUp = true)
        Reminders.notifyPlanning(context, "Test recording.", test = true)
        assertEquals(5, manager.activeNotifications.size)
        manager.activeNotifications.forEach { posted ->
            val n = posted.notification
            assertNull(n.sound)
            assertEquals(0, n.defaults and Notification.DEFAULT_SOUND)
            assertEquals(Notification.GROUP_ALERT_SUMMARY, n.groupAlertBehavior)
            assertNotNull(n.group)
            assertEquals(0, n.flags and Notification.FLAG_GROUP_SUMMARY)
            assertNotNull(n.contentIntent); assertNotNull(n.deleteIntent)
        }
    }
    @Test fun newChannelIsSilentAndPreviouslyBlockedChannelsStayBlocked() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Reminders.createChannel(context)
        assertNull(manager.getNotificationChannel(Reminders.CHANNEL).sound)
        val channel = manager.getNotificationChannel(Reminders.CHANNEL)
        channel.importance = NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(channel)
        Reminders.createChannel(context)
        assertFalse(Reminders.allowed(context))
        assertNull(Reminders.notifyPlanning(context, "No permission, no notification."))
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
