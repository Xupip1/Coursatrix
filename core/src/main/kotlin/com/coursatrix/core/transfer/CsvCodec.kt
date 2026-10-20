package com.coursatrix.core.transfer

import com.coursatrix.core.model.*
import com.coursatrix.core.validation.Limits
import com.coursatrix.core.validation.Validation
import com.coursatrix.core.validation.WeekPattern

private data class CsvRow(val line: Int, val cells: List<String>)
data class CsvResult(val data: AppData, val errors: List<String>, val duplicates: Int)

object CsvCodec {
    val headers = listOf("课程名称", "教师", "教室", "星期", "开始节次", "结束节次", "周次", "颜色", "备注")
    private val required = setOf("课程名称", "星期", "开始节次", "结束节次", "周次")

    fun encode(data: AppData): String {
        Validation.requireValid(data)
        val courses = data.courses.associateBy { it.id }
        val rows = data.meetings.map { m ->
            val c = courses.getValue(m.courseId)
            listOf(c.name, c.teacher, m.location, m.weekday.toString(), m.startPeriod.toString(),
                m.endPeriod.toString(), WeekPattern.format(m.weeks), c.color, c.note)
        }
        val encoded = 0xFEFF.toChar().toString() + (listOf(headers) + rows).joinToString("\r\n") { row ->
            row.joinToString(",") { value -> "\"${protect(value).replace("\"", "\"\"")}\"" }
        } + "\r\n"
        require(encoded.toByteArray(Charsets.UTF_8).size <= Limits.FILE_BYTES) { "CSV 导出超过 4 MiB，请减少数据后重试" }
        return encoded
    }

    fun decode(text: String, current: AppData): CsvResult {
        require(text.toByteArray(Charsets.UTF_8).size <= Limits.FILE_BYTES) { "CSV 文件超过 4 MiB" }
        val rows = readRows(text.removePrefix(0xFEFF.toChar().toString())).filter { row -> row.cells.any { it.isNotBlank() } }
        require(rows.isNotEmpty()) { "CSV 为空" }
        val header = rows.first().cells.map { it.trim() }
        require(header.distinct().size == header.size) { "CSV 表头有重复列" }
        require(required.all { it in header }) { "缺少表头：${(required - header.toSet()).joinToString()}。请使用 Coursatrix 模板" }
        val errors = mutableListOf<String>()
        val courses = linkedMapOf<Pair<String, String>, Course>()
        val meetings = mutableListOf<Meeting>()
        val signatures = mutableSetOf<String>()
        var duplicates = 0
        rows.drop(1).forEach { row ->
            if (errors.size >= 100) return@forEach
            try {
                require(row.cells.size == header.size) { "列数与表头不符" }
                fun value(name: String): String = header.indexOf(name).takeIf { it >= 0 }
                    ?.let { unprotect(row.cells[it]) } ?: ""
                val name = value("课程名称").trim()
                val teacher = value("教师").trim()
                require(name.isNotBlank()) { "课程名称不能为空" }
                val dayText = value("星期").trim()
                val day = dayText.toIntOrNull() ?: weekdayNames.indexOf(dayText).let { index ->
                    if (index >= 0) index + 1 else listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
                        .indexOf(dayText).let { if (it >= 0) it + 1 else 0 }
                }
                require(day in 1..7) { "星期应为 1–7、周一至周日" }
                val start = value("开始节次").trim().toIntOrNull() ?: error("开始节次不是整数")
                val end = value("结束节次").trim().toIntOrNull() ?: error("结束节次不是整数")
                require(start in 1..current.periods.size && end in start..current.periods.size) { "节次超出当前作息范围或顺序错误" }
                val weeks = WeekPattern.parse(value("周次"), current.semester.weekCount)
                val color = value("颜色").trim().ifBlank { courseColors[courses.size % courseColors.size] }.uppercase()
                require(color.matches(Regex("#[0-9A-F]{6}"))) { "颜色应为 #RRGGBB" }
                val note = value("备注")
                val key = name to teacher
                val existing = courses[key]
                require(existing == null || (existing.note == note && (value("颜色").isBlank() || existing.color == color))) {
                    "同名同教师课程的颜色或备注不一致，请统一后导入"
                }
                val course = existing ?: Course(semesterId = current.semester.id, name = name,
                    teacher = teacher, color = color, note = note)
                val meeting = Meeting(courseId = course.id, weekday = day, startPeriod = start,
                    endPeriod = end, weeks = weeks, location = value("教室").trim())
                require(name.length <= 160 && teacher.length <= 160 && meeting.location.length <= 200) { "课程名、教师或教室过长" }
                courses[key] = course
                if (signatures.add(meeting.signature())) meetings += meeting else duplicates++
            } catch (e: IllegalArgumentException) {
                errors += "第 ${row.line} 行：${e.message}"
            } catch (e: IllegalStateException) {
                errors += "第 ${row.line} 行：${e.message}"
            }
        }
        require(rows.size > 1) { "CSV 只有表头，没有课程" }
        val data = current.copy(courses = courses.values.toList(), meetings = meetings, tasks = emptyList())
        errors += Validation.errors(data).take((100 - errors.size).coerceAtLeast(0))
        return CsvResult(data, errors, duplicates)
    }

    private fun needsProtection(value: String): Boolean = value.startsWith("'") ||
        value.firstOrNull() in listOf('\t', '\r', '\n') || value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')
    private fun protect(value: String) = if (needsProtection(value)) "'$value" else value
    private fun unprotect(value: String) = if (value.startsWith("'") && needsProtection(value.drop(1))) value.drop(1) else value

    private fun readRows(text: String): List<CsvRow> {
        val rows = mutableListOf<CsvRow>()
        val cells = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var line = 1
        var startLine = 1
        var i = 0
        fun cell() { cells += field.toString(); field.setLength(0); closed = false }
        fun row() {
            cell()
            rows += CsvRow(startLine, cells.toList())
            cells.clear()
            require(rows.size <= Limits.MEETINGS + 1) { "CSV 超过 ${Limits.MEETINGS} 条记录" }
        }
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ }
                    else { quoted = false; closed = true }
                } else {
                    field.append(c)
                    if (c == '\n') line++
                }
            } else {
                require(!closed || c == ',' || c == '\n' || c == '\r') { "第 $line 行：闭合引号后存在多余字符" }
                when (c) {
                    '"' -> { require(field.isEmpty() && !closed) { "第 $line 行：未转义的引号" }; quoted = true }
                    ',' -> { cell(); require(cells.size <= 32) { "CSV 列数过多" } }
                    '\r', '\n' -> {
                        row()
                        if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                        line++
                        startLine = line
                    }
                    else -> field.append(c)
                }
            }
            require(field.length <= Limits.TEXT) { "第 $line 行：字段超过 ${Limits.TEXT} 字符" }
            i++
        }
        require(!quoted) { "第 $startLine 行：引号未闭合" }
        if (field.isNotEmpty() || cells.isNotEmpty() || closed) row()
        return rows
    }
}

internal fun Meeting.signature(): String = listOf(courseId, weekday, startPeriod, endPeriod,
    weeks.distinct().sorted().joinToString(","), location).joinToString("\u0000")
