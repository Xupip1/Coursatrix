package com.coursatrix.core.transfer.layout

import com.coursatrix.core.validation.Limits

/** One course read from a timetable cell. [weeks] is null when the source has no week information. */
data class ParsedCourse(val name: String, val teacher: String, val location: String, val weeks: List<Int>?, val issues: List<String>)

/**
 * Reads the text of one timetable cell. Understands
 * - academic-system cells: `课程名` / `教师【5-16周】` / `教室`, possibly wrapped over several lines;
 * - timetable-app exports: `课程名@地点[教室]` (no teacher or weeks);
 * several courses in one cell are separated by a line of dots (`………`).
 * Problems are reported in [ParsedCourse.issues] rather than guessed away.
 */
object CourseCellParser {
    private val separator = Regex("^[.。·…．\\-—_=]{3,}$")
    private val weekPart = Regex("(\\d{1,2})(?:-(\\d{1,2}))?(?:\\((单|双)\\))?")

    fun parse(lines: List<String>): List<ParsedCourse> {
        val groups = mutableListOf(mutableListOf<String>())
        lines.map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            if (separator.matches(line.replace(" ", ""))) groups += mutableListOf<String>() else groups.last() += line
        }
        return groups.filter { it.isNotEmpty() }.map(::parseOne)
    }

    private fun parseOne(lines: List<String>): ParsedCourse {
        val issues = mutableListOf<String>()
        val full = lines.joinToString("\n")
        val open = full.indexOf('【')
        val at = full.indexOfFirst { it == '@' || it == '＠' }
        val course = when {
            open >= 0 -> {
                val close = full.indexOf('】', open)
                if (close < 0) issues += "周次缺少「】」"
                val inside = full.substring(open + 1, if (close < 0) full.length else close).filterNot { it.isWhitespace() }
                val weeks = try { parseWeeks(inside) } catch (e: IllegalArgumentException) { issues += e.message ?: "无法识别周次"; null }
                // The teacher is the text right before 【; if 【 starts a line (wrapped), it is the line above.
                val before = full.substring(0, open).split('\n')
                var teacher = before.last().trim()
                var nameLines = before.dropLast(1)
                if (teacher.isEmpty()) {
                    teacher = nameLines.lastOrNull()?.trim().orEmpty()
                    nameLines = nameLines.dropLast(1)
                }
                var name = joinWrapped(nameLines)
                if (name.isEmpty()) { name = teacher; teacher = "" }
                val location = if (close < 0) "" else joinWrapped(full.substring(close + 1).split('\n'))
                ParsedCourse(name, teacher, location, weeks, issues)
            }
            at >= 0 -> {
                val joined = joinWrapped(lines)
                val split = joined.indexOfFirst { it == '@' || it == '＠' }
                issues += "来源中没有周次和教师信息"
                ParsedCourse(joined.substring(0, split).trim(), "", joined.substring(split + 1).trim(), null, issues)
            }
            else -> {
                issues += "未能区分课程名、教师和地点，请核对"
                ParsedCourse(lines.first(), "", joinWrapped(lines.drop(1)), null, issues)
            }
        }
        val extra = mutableListOf<String>()
        if (course.name.isBlank()) extra += "缺少课程名称"
        if (isTruncated(course.name)) extra += "课程名称被截断，请补全"
        return if (extra.isEmpty()) course else course.copy(issues = course.issues + extra)
    }

    fun isTruncated(name: String) = name.endsWith("...") || name.endsWith("…") || name.endsWith("..")

    /** "5-8,10-10,12周", "6-16(双)周", "9-15（单）" → explicit week list; parity applies per part. */
    fun parseWeeks(text: String): List<Int> {
        val value = text.replace("周", "").replace("，", ",").replace("（", "(").replace("）", ")").replace(" ", "")
        val weeks = sortedSetOf<Int>()
        for (part in value.split(',')) {
            val match = requireNotNull(weekPart.matchEntire(part)) { "无法识别周次规则：$part" }
            val first = match.groupValues[1].toInt()
            val last = match.groupValues[2].ifEmpty { match.groupValues[1] }.toInt()
            require(first in 1..last && last <= Limits.WEEKS) { "周次不在 1–${Limits.WEEKS} 范围或顺序错误：$part" }
            val parity = when (match.groupValues[3]) { "单" -> 1; "双" -> 0; else -> null }
            weeks += (first..last).filter { parity == null || it % 2 == parity }
        }
        require(weeks.isNotEmpty()) { "周次规则没有选中任何周：$text" }
        return weeks.toList()
    }
}
