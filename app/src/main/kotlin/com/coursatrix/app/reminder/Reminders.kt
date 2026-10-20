package com.coursatrix.app.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.coursatrix.app.CoursatrixApplication
import com.coursatrix.app.MainActivity
import com.coursatrix.app.R
import com.coursatrix.app.widget.TodayWidget
import com.coursatrix.core.model.AppData
import com.coursatrix.core.schedule.Reminder
import com.coursatrix.core.schedule.ScheduleCalculator
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/** Device-local reminder settings; deliberately not part of backups, since permissions differ per device. */
data class ReminderSettings(val enabled: Boolean = false, val minutesBefore: Int = 15)

object Reminders {
    private const val CHANNEL = "class_reminders"
    private const val PREFS = "reminders"
    private const val ACTION_FIRE = "com.coursatrix.app.action.CLASS_REMINDER"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_TEXT = "text"
    val minuteChoices = listOf(5, 10, 15, 20, 30, 45, 60)

    fun settings(context: Context): ReminderSettings = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
        ReminderSettings(it.getBoolean("enabled", false), it.getInt("minutes", 15).takeIf { m -> m in minuteChoices } ?: 15)
    }

    fun save(context: Context, settings: ReminderSettings) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
        putBoolean("enabled", settings.enabled)
        putInt("minutes", settings.minutesBefore)
    }

    fun notificationsAllowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun exactAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "课前提醒", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "上课前按设置的时间提醒" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun next(context: Context, data: AppData?): Reminder? {
        val settings = settings(context)
        if (!settings.enabled || data == null) return null
        return ScheduleCalculator.nextReminder(data, LocalDateTime.now(), settings.minutesBefore)
    }

    /** Keeps exactly one pending alarm: the next reminder, or none. */
    fun schedule(context: Context, data: AppData?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val next = next(context, data)
        if (next == null) {
            PendingIntent.getBroadcast(context, 0, baseIntent(context), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                ?.let(alarms::cancel)
            return
        }
        val minutes = settings(context).minutesBefore
        val first = next.sessions.first()
        val title = "$minutes 分钟后上课：" + next.sessions.joinToString("、") { it.course.name }
        val text = "${first.start}–${first.end} · 第 ${first.meeting.startPeriod}–${first.meeting.endPeriod} 节" +
            next.sessions.mapNotNull { it.meeting.location.ifBlank { null } }.distinct().joinToString("、").let { if (it.isEmpty()) "" else " · $it" }
        val intent = baseIntent(context).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val at = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun baseIntent(context: Context) = Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE)

    internal fun post(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE || !settings(context).enabled || !notificationsAllowed(context)) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(intent.getStringExtra(EXTRA_TITLE) ?: "即将上课")
            .setContentText(intent.getStringExtra(EXTRA_TEXT).orEmpty())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(1, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and posting; nothing else to do.
        }
    }

    /** Re-reads the database off the main thread, then reschedules reminders and refreshes widgets. */
    internal fun refresh(context: Context, result: BroadcastReceiver.PendingResult) {
        val app = context.applicationContext as CoursatrixApplication
        app.scope.launch {
            try {
                val data = app.repository.snapshot()
                schedule(app, data)
                TodayWidget.update(app, data)
            } finally {
                result.finish()
            }
        }
    }
}

/** Fires the scheduled reminder; not exported, so other apps cannot fake notifications. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.post(context, intent)
        Reminders.refresh(context, goAsync())
    }
}

/** Alarms are cleared on reboot and drift on clock/time-zone changes; reschedule from stored data. */
class RescheduleReceiver : BroadcastReceiver() {
    private val actions = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED,
        "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED") // AlarmManager constant, API 31+

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in actions) Reminders.refresh(context, goAsync())
    }
}
