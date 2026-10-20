package com.coursatrix.core.model

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

@Serializable
data class Semester(
    val id: String = newId(),
    val school: String = "",
    val name: String = "我的学期",
    val firstMonday: String = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
    val weekCount: Int = 20,
) {
    val startDate: LocalDate get() = LocalDate.parse(firstMonday)
}

@Serializable
data class Period(val number: Int, val start: String, val end: String) {
    val startTime: LocalTime get() = LocalTime.parse(start)
    val endTime: LocalTime get() = LocalTime.parse(end)
}

@Serializable
data class Course(
    val id: String = newId(),
    val semesterId: String,
    val name: String,
    val teacher: String = "",
    val color: String = "#6960CE",
    val note: String = "",
)

@Serializable
data class Meeting(
    val id: String = newId(),
    val courseId: String,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val weeks: List<Int>,
    val location: String = "",
)

@Serializable
enum class TaskKind(val label: String) { TASK("任务"), HOMEWORK("作业"), EXAM("考试") }

@Serializable
data class PlanTask(
    val id: String = newId(),
    val title: String,
    val kind: TaskKind = TaskKind.TASK,
    val dueDate: String? = null,
    val completed: Boolean = false,
    val courseId: String? = null,
    val note: String = "",
)

@Serializable
enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

/** App accent colour; [DYNAMIC] follows the wallpaper on Android 12+ and falls back to [VIOLET] elsewhere. */
@Serializable
enum class AccentColor(val label: String) { VIOLET("雾紫"), BLUE("海蓝"), TEAL("青绿"), ORANGE("暖橙"), PINK("樱粉"), DYNAMIC("跟随壁纸") }

@Serializable
enum class CardStyle(val label: String) { SOFT("浅色"), SOLID("实色") }

@Serializable
data class Preferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val showWeekend: Boolean = true,
    /** Show courses not held in the viewed week, greyed out, in otherwise empty timetable slots. */
    val showInactive: Boolean = true,
    val accent: AccentColor = AccentColor.VIOLET,
    val cardStyle: CardStyle = CardStyle.SOFT,
    val showLocation: Boolean = true,
    val showTeacher: Boolean = false,
    /** Start/end times under each period number in the timetable's side column. */
    val showPeriodTimes: Boolean = true,
    val showGridLines: Boolean = true,
)

@Serializable
data class AppData(
    val semester: Semester = Semester(),
    val periods: List<Period> = defaultPeriods(),
    val courses: List<Course> = emptyList(),
    val meetings: List<Meeting> = emptyList(),
    val tasks: List<PlanTask> = emptyList(),
    val preferences: Preferences = Preferences(),
)

fun defaultPeriods(): List<Period> = listOf(
    "08:00" to "08:45", "08:55" to "09:40", "10:00" to "10:45", "10:55" to "11:40",
    "11:50" to "12:35", "14:00" to "14:45", "14:55" to "15:40", "16:00" to "16:45",
    "16:55" to "17:40", "19:00" to "19:45", "19:55" to "20:40", "20:50" to "21:35",
).mapIndexed { index, (start, end) -> Period(index + 1, start, end) }

val courseColors = listOf("#6960CE", "#397E75", "#B46A39", "#A44D75", "#4F76B5", "#82703F")
val weekdayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
