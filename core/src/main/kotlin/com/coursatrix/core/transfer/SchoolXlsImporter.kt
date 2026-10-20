package com.coursatrix.core.transfer

import com.coursatrix.core.model.*
import com.coursatrix.core.transfer.layout.CourseCellParser
import com.coursatrix.core.validation.Limits
import com.coursatrix.core.validation.Validation
import java.time.LocalTime

/** [data] keeps the current semester start; [periodsFromFile] is false when the sheet has no time column. */
data class SchoolXlsResult(val data: AppData, val periodsFromFile: Boolean, val reusedCourses: Int)

/**
 * Converts a school "grid" timetable (weekday columns × period rows, merged cells for consecutive periods,
 * cells like "课程\n\n教师【1-16周】\n教室") into Coursatrix data. Unrecognised content fails instead of being guessed.
 * Student name, ID and class rows above the header are never read into the result.
 */
object SchoolXlsImporter {
    private val dayNames = mapOf("一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "日" to 7, "天" to 7)
    private val time = Regex("(\\d{1,2})[:：](\\d{2})")
    private val school = Regex("[^\\n：:]{2,50}(大学|学院)")

    fun isSpreadsheet(bytes: ByteArray): Boolean = XlsReader.isXls(bytes) || XlsxReader.isZip(bytes)

    /** Accepts both .xls (OLE2) and .xlsx (ZIP); the format is detected from the file content, not its name. */
    fun decode(bytes: ByteArray, current: AppData): SchoolXlsResult =
        convert(if (XlsxReader.isZip(bytes)) XlsxReader.read(bytes) else XlsReader.read(bytes), current)

    fun convert(sheet: XlsSheet, current: AppData): SchoolXlsResult {
        require(sheet.rows <= 200 && sheet.columns <= 30) { "表格尺寸超出课表的合理范围" }
        var header = -1
        var dayColumns = emptyMap<Int, Int>()
        var timeColumn: Int? = null
        for (row in 0 until minOf(20, sheet.rows)) {
            val values = (0 until sheet.columns).map { sheet.text(row, it).trim() }
            val days = values.withIndex().mapNotNull { (column, value) -> dayOf(value)?.let { column to it } }.toMap()
            if (1 in days.values) {
                header = row
                dayColumns = days
                timeColumn = values.indexOfFirst { it == "时间" || it == "上课时间" }.takeIf { it >= 0 }
                break
            }
        }
        require(header >= 0 && dayColumns.size >= 5 && dayColumns.values.distinct().size == dayColumns.size) {
            "未找到「星期一 … 星期五」表头。目前只能识别教务系统导出的网格课表（星期为列、节次为行）"
        }
        val firstDayColumn = dayColumns.keys.min()
        val periodRows = linkedMapOf<Int, Int>()
        val periods = mutableListOf<Period>()
        for (row in header + 1 until sheet.rows) {
            val number = periodRows.size + 1
            if ((0 until firstDayColumn).none { periodNumber(sheet, row, it) == number }) continue
            periodRows[row] = number
            timeColumn?.let { column ->
                val found = time.findAll(sheet.text(row, column)).map { match ->
                    val (hour, minute) = match.destructured
                    require(hour.toInt() in 0..23 && minute.toInt() in 0..59) { "第 ${row + 1} 行的时间无效：${match.value}" }
                    LocalTime.of(hour.toInt(), minute.toInt())
                }.toList()
                require(found.size == 2) { "第 ${row + 1} 行缺少明确的上课起止时间" }
                val (start, end) = found
                require(start < end && (periods.isEmpty() || start >= periods.last().endTime)) { "第 ${row + 1} 行的作息时间顺序错误" }
                periods += Period(number, start.toString(), end.toString())
            }
        }
        require(periodRows.size in 1..Limits.PERIODS) { "未找到从 1 开始连续编号的节次" }
        val fromFile = timeColumn != null
        val finalPeriods = if (fromFile) periods else {
            require(current.periods.size >= periodRows.size) {
                "表格没有「时间」列，且当前作息只有 ${current.periods.size} 节、少于表格的 ${periodRows.size} 节。请先在「学期与作息」中补齐节数"
            }
            current.periods
        }

        val courses = linkedMapOf<Pair<String, String>, Course>()
        val meetings = mutableListOf<Meeting>()
        val signatures = mutableSetOf<String>()
        var reused = 0
        for ((row, startPeriod) in periodRows) for ((column, weekday) in dayColumns) {
            val text = sheet.text(row, column).trim()
            if (text.isEmpty()) continue
            val where = "第 ${row + 1} 行、第 ${column + 1} 列"
            val range = sheet.merged.firstOrNull { (row to column) in it }
            val endPeriod = if (range == null) startPeriod else {
                require(range.firstRow == row && range.firstColumn == column && range.lastColumn == column) {
                    "$where 的合并单元格跨越了多天，无法判断属于哪一天"
                }
                periodRows.filterKeys { it in range.firstRow..range.lastRow }.values.max()
            }
            for (parsed in CourseCellParser.parse(text.lines())) {
                // Excel files are exact, so anything the parser is unsure about stops the import instead of being guessed.
                require(parsed.weeks != null && parsed.issues.isEmpty()) {
                    "$where 无法识别（需要「课程名 / 教师【1-16周】/ 教室」）：" + parsed.issues.joinToString("；")
                }
                val (name, teacher, location) = parsed
                val weeks: List<Int> = parsed.weeks
                val course = courses.getOrPut(name to teacher) {
                    val existing = current.courses.firstOrNull { it.name == name && it.teacher == teacher }
                    if (existing != null) reused++
                    Course(id = existing?.id ?: newId(), semesterId = current.semester.id, name = name, teacher = teacher,
                        color = existing?.color ?: courseColors[courses.size % courseColors.size], note = existing?.note ?: "")
                }
                val meeting = Meeting(courseId = course.id, weekday = weekday, startPeriod = startPeriod,
                    endPeriod = endPeriod, weeks = weeks, location = location)
                if (signatures.add(meeting.signature())) meetings += meeting
            }
        }
        require(meetings.isNotEmpty()) { "表格中没有识别到任何课程" }
        val weekCount = maxOf(current.semester.weekCount, meetings.maxOf { it.weeks.max() })
        val schoolName = (0 until header).flatMap { row -> (0 until sheet.columns).map { sheet.text(row, it).trim() } }
            .firstOrNull { school.matches(it) }
        val data = current.copy(
            semester = current.semester.copy(school = schoolName ?: current.semester.school, weekCount = weekCount),
            periods = finalPeriods, courses = courses.values.toList(), meetings = meetings, tasks = emptyList(),
        )
        Validation.requireValid(data)
        return SchoolXlsResult(data, fromFile, reused)
    }

    fun parseWeeks(text: String): List<Int> = CourseCellParser.parseWeeks(text)

    private fun dayOf(value: String): Int? {
        val suffix = value.removePrefix("星期").removePrefix("周")
        return if (suffix.length == 1 && suffix != value) dayNames[suffix] else null
    }

    private fun periodNumber(sheet: XlsSheet, row: Int, column: Int): Int? {
        sheet.number(row, column)?.let { return if (it == Math.floor(it) && it in 1.0..99.0) it.toInt() else null }
        return Regex("第?(\\d{1,2})节?").matchEntire(sheet.text(row, column).trim())?.groupValues?.get(1)?.toInt()
    }
}
