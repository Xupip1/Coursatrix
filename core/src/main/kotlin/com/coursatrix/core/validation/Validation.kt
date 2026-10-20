package com.coursatrix.core.validation

import com.coursatrix.core.model.AppData
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

object Limits {
    const val FILE_BYTES = 4 * 1024 * 1024
    const val COURSES = 1000
    const val MEETINGS = 5000
    const val TASKS = 5000
    const val WEEKS = 60
    const val PERIODS = 48
    const val TEXT = 2000
}

object Validation {
    fun errors(data: AppData): List<String> = buildList {
        fun check(ok: Boolean, message: String) { if (!ok && size < 100) add(message) }
        fun text(value: String, field: String, required: Boolean = false, max: Int = Limits.TEXT) {
            check(value.length <= max && '\u0000' !in value && (!required || value.isNotBlank()), "$field 为空或过长（最多 $max 字符）")
        }
        fun ids(values: List<String>, type: String) {
            check(values.distinct().size == values.size, "$type 标识重复")
            values.forEach { text(it, "$type 标识", true, 80) }
        }
        text(data.semester.id, "学期标识", true, 80)
        text(data.semester.name, "学期名称", true, 100)
        text(data.semester.school, "学校", max = 100)
        check(data.semester.weekCount in 1..Limits.WEEKS, "学期周数须在 1–${Limits.WEEKS} 之间")
        val monday = runCatching { LocalDate.parse(data.semester.firstMonday) }.getOrNull()
        check(monday?.dayOfWeek == DayOfWeek.MONDAY && data.semester.firstMonday.matches(Regex("\\d{4}-\\d{2}-\\d{2}")),
            "学期起始日期必须是有效的周一（YYYY-MM-DD）")
        check(data.periods.size in 1..Limits.PERIODS, "每天节数须在 1–${Limits.PERIODS} 之间")
        check(data.periods.map { it.number } == (1..data.periods.size).toList(), "节次必须从 1 连续排列")
        var previousEnd: LocalTime? = null
        data.periods.forEach { p ->
            val start = runCatching { LocalTime.parse(p.start) }.getOrNull()
            val end = runCatching { LocalTime.parse(p.end) }.getOrNull()
            check(start != null && end != null && start < end && p.start.matches(Regex("\\d{2}:\\d{2}")) &&
                p.end.matches(Regex("\\d{2}:\\d{2}")), "第 ${p.number} 节起止时间无效，应使用 HH:mm 且结束晚于开始")
            check(start == null || previousEnd == null || start >= previousEnd, "第 ${p.number} 节与上一节时间重叠或未按时间排列")
            previousEnd = end
        }
        check(data.courses.size <= Limits.COURSES, "课程超过 ${Limits.COURSES} 门")
        check(data.meetings.size <= Limits.MEETINGS, "上课安排超过 ${Limits.MEETINGS} 条")
        check(data.tasks.size <= Limits.TASKS, "计划超过 ${Limits.TASKS} 条")
        ids(data.courses.map { it.id }, "课程")
        ids(data.meetings.map { it.id }, "安排")
        ids(data.tasks.map { it.id }, "计划")
        val courseIds = data.courses.map { it.id }.toSet()
        data.courses.forEach { c ->
            text(c.name, "课程名称", true, 160)
            text(c.teacher, "教师", max = 160)
            text(c.note, "课程备注")
            check(c.semesterId == data.semester.id, "课程 ${c.name} 不属于当前学期")
            check(c.color.matches(Regex("#[0-9a-fA-F]{6}")), "课程 ${c.name} 的颜色应为 #RRGGBB")
            check(data.meetings.any { it.courseId == c.id }, "课程 ${c.name} 至少需要一条上课安排")
        }
        data.meetings.forEach { m ->
            check(m.courseId in courseIds, "上课安排引用了不存在的课程")
            check(m.weekday in 1..7, "星期须在 1–7 之间")
            check(m.startPeriod in 1..data.periods.size && m.endPeriod in m.startPeriod..data.periods.size,
                "安排的起止节次超出作息范围或顺序错误")
            check(m.weeks.isNotEmpty() && m.weeks.size <= Limits.WEEKS && m.weeks.distinct().size == m.weeks.size &&
                m.weeks.all { it in 1..data.semester.weekCount }, "安排的周次为空、重复或超出学期范围")
            text(m.location, "教室", max = 200)
        }
        data.tasks.forEach { t ->
            text(t.title, "计划标题", true, 160)
            text(t.note, "计划备注")
            check(t.courseId == null || t.courseId in courseIds, "计划引用了不存在的课程")
            check(t.dueDate == null || runCatching { LocalDate.parse(t.dueDate) }.isSuccess, "计划截止日期无效")
        }
    }

    fun requireValid(data: AppData) {
        val errors = errors(data)
        require(errors.isEmpty()) { errors.joinToString("\n") }
    }
}

object WeekPattern {
    fun parse(text: String, weekCount: Int): List<Int> {
        require(weekCount in 1..Limits.WEEKS) { "学期周数无效" }
        var input = text.trim().replace("，", ",").replace("周", "").replace(" ", "")
            .replace("（", "(").replace("）", ")").replace("(单)", "单").replace("(双)", "双")
        val parity = when {
            input.endsWith("单") -> 1
            input.endsWith("双") -> 0
            else -> null
        }
        if (parity != null) input = input.dropLast(1)
        require(input.isNotBlank() && input.length <= 500) { "请填写周次，例如 1-16 或 1,3,5" }
        val result = input.split(',').flatMap { part ->
            val match = Regex("(\\d{1,2})(?:-(\\d{1,2}))?").matchEntire(part)
            requireNotNull(match) { "无法识别周次：$part" }
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: start
            require(start in 1..weekCount && end in start..weekCount) { "周次须在 1–$weekCount 之间且顺序正确" }
            (start..end).filter { parity == null || it % 2 == parity }
        }.distinct().sorted()
        require(result.isNotEmpty()) { "该规则没有选中任何周" }
        return result
    }

    fun format(weeks: List<Int>): String {
        val sorted = weeks.distinct().sorted()
        if (sorted.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var end = start
        for (week in sorted.drop(1)) {
            if (week == end + 1) end = week
            else {
                parts += if (start == end) "$start" else "$start-$end"
                start = week
                end = week
            }
        }
        parts += if (start == end) "$start" else "$start-$end"
        return parts.joinToString(",")
    }
}
