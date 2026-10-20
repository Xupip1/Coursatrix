package com.coursatrix.app.ui.profile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.coursatrix.app.reminder.ReminderSettings
import com.coursatrix.app.reminder.Reminders
import com.coursatrix.app.ui.CoursatrixViewModel
import com.coursatrix.app.ui.components.ChoiceField
import com.coursatrix.app.ui.components.SectionTitle
import com.coursatrix.core.model.AppData

@Composable
fun ReminderPanel(data: AppData, model: CoursatrixViewModel) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(Reminders.settings(context)) }
    var notificationsAllowed by remember { mutableStateOf(Reminders.notificationsAllowed(context)) }
    var exactAllowed by remember { mutableStateOf(Reminders.exactAllowed(context)) }
    // Permissions may change in system settings while the app is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsAllowed = Reminders.notificationsAllowed(context)
        exactAllowed = Reminders.exactAllowed(context)
    }
    fun update(value: ReminderSettings) {
        settings = value
        Reminders.save(context, value)
        Reminders.schedule(context, data)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = Reminders.notificationsAllowed(context)
        if (granted) update(settings.copy(enabled = true))
        else model.notify("未获得通知权限，无法发送课前提醒。可在系统设置中为 Coursatrix 开启通知后再试。")
    }
    SectionTitle("课前提醒")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("上课前提醒我", Modifier.weight(1f))
        Switch(settings.enabled, { on ->
            if (on && Build.VERSION.SDK_INT >= 33 && !Reminders.notificationsAllowed(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else update(settings.copy(enabled = on))
        })
    }
    if (!settings.enabled) return
    ChoiceField("提前时间", "提前 ${settings.minutesBefore} 分钟", Reminders.minuteChoices.map { it to "提前 $it 分钟" }) {
        update(settings.copy(minutesBefore = it))
    }
    val next = remember(data, settings) { Reminders.next(context, data) }
    Text(next?.let { r -> "下一次提醒：${r.at.monthValue} 月 ${r.at.dayOfMonth} 日 ${r.at.toLocalTime()} · " +
        r.sessions.joinToString("、") { it.course.name } } ?: "本学期剩余时间内没有需要提醒的课程。",
        style = MaterialTheme.typography.bodySmall)
    if (!notificationsAllowed) {
        Text("系统已关闭本应用的通知，提醒不会显示。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        OutlinedButton({
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }, Modifier.fillMaxWidth()) { Text("打开通知设置") }
    }
    if (!exactAllowed && Build.VERSION.SDK_INT >= 31) {
        OutlinedButton({
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
        }, Modifier.fillMaxWidth()) { Text("允许准时提醒") }
    }
}
