package com.coursatrix.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.toColorInt
import com.coursatrix.app.MainActivity
import com.coursatrix.app.R
import com.coursatrix.app.reminder.Reminders
import com.coursatrix.core.model.AppData
import com.coursatrix.core.model.weekdayNames
import com.coursatrix.core.schedule.ScheduleCalculator
import java.time.LocalDateTime

/** Home-screen widget: today's remaining classes, or tomorrow's once today is over. Refreshes every 30 minutes. */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Reminders.refresh(context, goAsync())
    }

    companion object {
        private const val MAX_ROWS = 4

        fun update(context: Context, data: AppData?) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, data, LocalDateTime.now()))
        }

        private fun views(context: Context, data: AppData?, now: LocalDateTime): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_today)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            views.removeAllViews(R.id.widget_rows)
            if (data == null) {
                views.setTextViewText(R.id.widget_title, "Coursatrix")
                views.setTextViewText(R.id.widget_empty, "打开应用以加载课表")
                views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                views.setViewVisibility(R.id.widget_more, View.GONE)
                return views
            }
            val today = now.toLocalDate()
            val remaining = ScheduleCalculator.onDate(data, today).filter { it.end.isAfter(now.toLocalTime()) }
            val showToday = remaining.isNotEmpty()
            val date = if (showToday) today else today.plusDays(1)
            val sessions = if (showToday) remaining else ScheduleCalculator.onDate(data, date)
            val week = ScheduleCalculator.weekOn(data.semester, date)
            views.setTextViewText(R.id.widget_title, listOf(if (showToday) "今天" else "明天", weekdayNames[date.dayOfWeek.value - 1],
                if (week in 1..data.semester.weekCount) "第 $week 周" else "不在学期内").joinToString(" · "))
            views.setTextViewText(R.id.widget_empty, "今天的课已结束，明天没有课程")
            views.setViewVisibility(R.id.widget_empty, if (sessions.isEmpty()) View.VISIBLE else View.GONE)
            sessions.take(MAX_ROWS).forEach { session ->
                val row = RemoteViews(context.packageName, R.layout.widget_row)
                row.setInt(R.id.widget_row_color, "setBackgroundColor", session.course.color.toColorInt())
                row.setTextViewText(R.id.widget_row_time, "${session.start}\n${session.end}")
                row.setTextViewText(R.id.widget_row_name, session.course.name)
                row.setTextViewText(R.id.widget_row_place, listOf(session.meeting.location.ifBlank { "未填写教室" },
                    "第 ${session.meeting.startPeriod}–${session.meeting.endPeriod} 节").joinToString(" · "))
                views.addView(R.id.widget_rows, row)
            }
            val more = sessions.size - MAX_ROWS
            views.setTextViewText(R.id.widget_more, "还有 $more 节课，点击查看")
            views.setViewVisibility(R.id.widget_more, if (more > 0) View.VISIBLE else View.GONE)
            return views
        }
    }
}
