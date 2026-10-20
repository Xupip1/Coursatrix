package com.coursatrix.core.transfer.layout

import com.coursatrix.core.model.Period
import java.time.LocalTime

/** A course occurrence recognised from a PDF, before the user reviews it. */
data class DraftEntry(
    val name: String, val teacher: String, val location: String,
    val weekday: Int, val startPeriod: Int, val endPeriod: Int,
    /** null when the source does not say which weeks. */
    val weeks: List<Int>?,
    val issues: List<String> = emptyList(),
)

/** [periods] is set only when the source shows start/end times for every period row. */
data class DraftTimetable(val entries: List<DraftEntry>, val periods: List<Period>?, val notes: List<String>)

/**
 * Rebuilds a weekly timetable grid from positioned text. Anchors are the weekday header ("星期一"/"周一" …) and the
 * period numbers (1, 2, 3 …) left of the grid. Cell extents come from the drawn table lines, or else from the text\n * position alone.
 */
object GridReconstructor {
    private val weekday = Regex("(星期|周)([一二三四五六日天])")
    private val dayNumbers = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7)
    /** A standalone 1–2 digit number, not part of a time ("8:00") or a longer number. */
    private val periodLabel = Regex("(?<![\\d:：.])第?(\\d{1,2})节?(?![\\d:：.])")
    private val time = Regex("(\\d{1,2})[:：](\\d{2})")

    private class Anchor(val value: Int, val box: Box)
    private class Column(val day: Int, val left: Float, val right: Float)

    fun reconstruct(page: PageLayout): DraftTimetable {
        require(page.glyphs.isNotEmpty()) { "PDF 中没有可读取的文字" }
        val lines = TextLine.group(page.glyphs)

        // 1. Weekday header: the row with the most distinct weekdays, at least Monday–Friday.
        val dayAnchors = lines.flatMap { line ->
            weekday.findAll(line.text).map { Anchor(dayNumbers.getValue(it.groupValues[2][0]), line.boxOf(it.range)) }.toList()
        }
        val header = dayAnchors.groupByRow().map { group -> group.distinctBy { it.value } }
            .filter { group -> (1..5).all { day -> group.any { it.value == day } } }
            .maxByOrNull { it.size }
            ?: throw IllegalArgumentException("没有找到「周一 … 周五」或「星期一 … 星期五」表头，无法确定每列是星期几")
        val days = header.sortedBy { it.value }
        require(days.zipWithNext().all { (a, b) -> b.box.centerX > a.box.centerX }) { "表头的星期顺序不是从左到右，暂不支持竖排课表" }
        val spacing = days.zipWithNext().map { (a, b) -> (b.box.centerX - a.box.centerX) / (b.value - a.value) }.median()
        val vertical = page.rules.filterNot { it.horizontal }
        fun snapX(x: Float) = vertical.map { it.box.centerX }.filter { kotlin.math.abs(it - x) < spacing * 0.25f }
            .minByOrNull { kotlin.math.abs(it - x) } ?: x
        val columns = days.map { Column(it.value, snapX(it.box.centerX - spacing / 2), snapX(it.box.centerX + spacing / 2)) }
        val headerBottom = days.maxOf { it.box.bottom }
        val gridLeft = columns.first().left

        // 2. Period rows: numbers 1, 2, 3 … in order, left of the first day column and below the header.
        val periodAnchors = mutableListOf<Anchor>()
        lines.filter { it.box.right <= gridLeft + spacing * 0.1f && it.box.centerY > headerBottom }.sortedBy { it.box.centerY }
            .forEach { line ->
                // Search inside the line: side labels such as a vertical "下午" can sit right next to the number ("午8").
                periodLabel.findAll(line.text).forEach { match ->
                    val number = match.groupValues[1].toInt()
                    if (number == periodAnchors.size + 1) periodAnchors += Anchor(number, line.boxOf(match.range))
                }
            }
        require(periodAnchors.size >= 2) { "没有在表格左侧找到节次编号（1、2、3 …），无法确定每行是第几节" }
        val centers = periodAnchors.map { it.box.centerY }
        val horizontal = mergeHorizontal(page.rules.filter { it.horizontal && it.box.width >= spacing * 0.5f })
        val rowHeight = centers.zipWithNext().map { (a, b) -> b - a }.median()
        fun snapY(y: Float) = horizontal.map { it.box.centerY }.filter { kotlin.math.abs(it - y) < rowHeight * 0.3f }
            .minByOrNull { kotlin.math.abs(it - y) } ?: y
        val bounds = buildList {
            add(snapY(maxOf(headerBottom, centers.first() - rowHeight / 2)))
            centers.zipWithNext().forEach { (a, b) -> add(snapY((a + b) / 2)) }
            add(snapY(centers.last() + rowHeight / 2))
        }
        val gridTop = bounds.first()
        val gridBottom = bounds.last()

        // Period times, only if every row shows exactly two times in the left area.
        val periods = periodAnchors.indices.map { index ->
            val found = lines.filter { it.box.right <= gridLeft + spacing * 0.1f && it.box.centerY in bounds[index]..bounds[index + 1] }
                .flatMap { line -> time.findAll(line.text).map { it.destructured }.toList() }
                .mapNotNull { (h, m) -> runCatching { LocalTime.of(h.toInt(), m.toInt()) }.getOrNull() }
            if (found.size == 2 && found[0] < found[1]) Period(index + 1, found[0].toString(), found[1].toString()) else null
        }.takeIf { list -> list.all { it != null } && list.zipWithNext().all { (a, b) -> b!!.startTime >= a!!.endTime } }
            ?.filterNotNull()

        fun periodsOf(top: Float, bottom: Float): IntRange? {
            val rows = periodAnchors.indices.filter { i ->
                val overlap = minOf(bottom, bounds[i + 1]) - maxOf(top, bounds[i])
                overlap >= (bounds[i + 1] - bounds[i]) * 0.5f
            }
            val center = (top + bottom) / 2
            return if (rows.isNotEmpty()) rows.first() + 1..rows.last() + 1
                else periodAnchors.indices.firstOrNull { center in bounds[it]..bounds[it + 1] }?.let { it + 1..it + 1 }
        }

        // 3. Cells in each day column.
        val entries = mutableListOf<DraftEntry>()
        var inferredSpans = 0
        for (column in columns) {
            val glyphs = page.glyphs.filter { it.box.centerX in column.left..column.right && it.box.centerY in gridTop..gridBottom }
            if (glyphs.isEmpty()) continue
            fun hasText(top: Float, bottom: Float) = glyphs.any { it.box.centerY in top..bottom }
            // Cells are the gaps between table lines that cross this column.
            var cells = emptyList<Pair<Float, Float>>()
            val interior = horizontal.filter { it.box.left <= column.left + spacing * 0.2f && it.box.right >= column.right - spacing * 0.2f }
                .map { it.box.centerY }.filter { it > gridTop + rowHeight * 0.3f && it < gridBottom - rowHeight * 0.3f }
            if (interior.isNotEmpty()) {
                val edges = (listOf(gridTop, gridBottom) + interior).sorted()
                    .fold(mutableListOf<Float>()) { acc, y -> if (acc.isEmpty() || y - acc.last() > rowHeight * 0.2f) acc += y; acc }
                cells = edges.zipWithNext().filter { (top, bottom) -> hasText(top, bottom) }
            }
            val inferred = cells.isEmpty()
            if (inferred) {
                // No table lines: group text lines that are close together and take their extent.
                val columnLines = TextLine.group(glyphs)
                val clusters = mutableListOf(mutableListOf(columnLines.first()))
                columnLines.drop(1).forEach { line ->
                    if (line.box.top - clusters.last().maxOf { it.box.bottom } > rowHeight * 0.6f) clusters += mutableListOf(line)
                    else clusters.last() += line
                }
                cells = clusters.map { c -> c.minOf { it.box.top } to c.maxOf { it.box.bottom } }
                inferredSpans += cells.size
            }
            for ((top, bottom) in cells) {
                val span = periodsOf(top, bottom) ?: continue
                val text = TextLine.group(glyphs.filter { it.box.centerY in top..bottom }).map { it.text }
                CourseCellParser.parse(text).forEach { course ->
                    entries += DraftEntry(course.name, course.teacher, course.location, column.day, span.first, span.last, course.weeks,
                        course.issues + if (inferred) listOf("节次按文字位置推测，连堂节数可能不准") else emptyList())
                }
            }
        }
        require(entries.isNotEmpty()) { "找到了表格，但没有识别到任何课程" }
        val notes = buildList {
            add("识别到 ${columns.size} 天 × ${periodAnchors.size} 节的课表，共 ${entries.size} 条安排。")
            if (periods == null) add("来源中没有每节的上课时间，将使用当前作息。")
            if (entries.any { it.weeks == null }) add("部分课程没有周次信息，请在下方统一设置或逐条填写。")
            if (inferredSpans > 0) add("有 $inferredSpans 处没有表格线，节次按文字位置推测。")
        }
        return DraftTimetable(entries.sortedWith(compareBy({ it.weekday }, { it.startPeriod })), periods, notes)
    }

    /** Joins collinear horizontal segments (tables are often drawn cell by cell). */
    private fun mergeHorizontal(rules: List<Rule>): List<Rule> {
        val result = mutableListOf<Rule>()
        for (rule in rules.sortedWith(compareBy({ it.box.centerY }, { it.box.left }))) {
            val last = result.lastOrNull()
            if (last != null && kotlin.math.abs(last.box.centerY - rule.box.centerY) < 1.5f && rule.box.left <= last.box.right + 2f)
                result[result.lastIndex] = Rule(last.box.union(rule.box))
            else result += rule
        }
        return result
    }

    /** Groups anchors whose vertical centres are within about one text height of each other (same header row). */
    private fun List<Anchor>.groupByRow(): List<List<Anchor>> {
        val groups = mutableListOf<MutableList<Anchor>>()
        sortedBy { it.box.centerY }.forEach { anchor ->
            val group = groups.lastOrNull()
            if (group != null && anchor.box.centerY - group.last().box.centerY < anchor.box.height) group += anchor
            else groups += mutableListOf(anchor)
        }
        return groups
    }

    private fun List<Float>.median(): Float = sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
}
