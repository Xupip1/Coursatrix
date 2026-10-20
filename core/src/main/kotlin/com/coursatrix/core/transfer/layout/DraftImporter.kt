package com.coursatrix.core.transfer.layout

import com.coursatrix.core.model.*
import com.coursatrix.core.transfer.signature
import com.coursatrix.core.validation.Validation

/** Turns reviewed [DraftEntry]s into courses and meetings for the current semester. */
object DraftImporter {
    /**
     * An existing course this entry most likely refers to: exact name+teacher, or — when the source omits the teacher
     * or truncates the name ("大学英语读...") — the only existing course whose name starts with the given text.
     */
    fun match(name: String, teacher: String, current: AppData): Course? {
        current.courses.firstOrNull { it.name == name && it.teacher == teacher }?.let { return it }
        val prefix = name.trimEnd('.', '…').trim()
        if (prefix.isEmpty() || (!CourseCellParser.isTruncated(name) && teacher.isNotBlank())) return null
        return current.courses.filter { it.name.startsWith(prefix) && (teacher.isBlank() || it.teacher == teacher) }
            .distinctBy { it.name to it.teacher }.singleOrNull()
    }

    /** All entries must already have weeks. The result has the current semester and only the imported courses. */
    fun build(entries: List<DraftEntry>, current: AppData): AppData {
        require(entries.isNotEmpty()) { "没有可导入的课程" }
        val courses = linkedMapOf<Pair<String, String>, Course>()
        val meetings = mutableListOf<Meeting>()
        val signatures = mutableSetOf<String>()
        for (entry in entries) {
            val weeks = requireNotNull(entry.weeks) { "${entry.name}：请填写周次" }
            val existing = match(entry.name.trim(), entry.teacher.trim(), current)
            val name = existing?.name ?: entry.name.trim()
            val teacher = existing?.teacher ?: entry.teacher.trim()
            val course = courses.getOrPut(name to teacher) {
                Course(id = existing?.id ?: newId(), semesterId = current.semester.id, name = name, teacher = teacher,
                    color = existing?.color ?: courseColors[courses.size % courseColors.size], note = existing?.note ?: "")
            }
            val meeting = Meeting(courseId = course.id, weekday = entry.weekday, startPeriod = entry.startPeriod,
                endPeriod = entry.endPeriod, weeks = weeks.distinct().sorted(), location = entry.location.trim())
            if (signatures.add(meeting.signature())) meetings += meeting
        }
        return current.copy(courses = courses.values.toList(), meetings = meetings, tasks = emptyList()).also(Validation::requireValid)
    }
}

/**
 * Collects table borders from PDF path operators. Coordinates arrive in PDF user space (origin bottom-left) and are
 * converted to top-left page coordinates using the crop box ([cropLeft], [cropTop]). Only straight horizontal or
 * vertical strokes and thin filled rectangles count; filled cell backgrounds are ignored.
 */
class PdfRuleCollector(private val cropLeft: Float, private val cropTop: Float) {
    val rules = mutableListOf<Rule>()
    private val rects = mutableListOf<Box>()
    private val segments = mutableListOf<Box>()
    private var x = 0f
    private var y = 0f

    private fun box(x0: Float, y0: Float, x1: Float, y1: Float) =
        Box(minOf(x0, x1) - cropLeft, cropTop - maxOf(y0, y1), maxOf(x0, x1) - cropLeft, cropTop - minOf(y0, y1))

    fun rectangle(xs: FloatArray, ys: FloatArray) { rects += box(xs.min(), ys.min(), xs.max(), ys.max()) }
    fun moveTo(x: Float, y: Float) { this.x = x; this.y = y }
    fun lineTo(x: Float, y: Float) { segments += box(this.x, this.y, x, y); this.x = x; this.y = y }

    fun stroke() {
        (segments + rects.flatMap { r ->
            listOf(Box(r.left, r.top, r.right, r.top), Box(r.left, r.bottom, r.right, r.bottom),
                Box(r.left, r.top, r.left, r.bottom), Box(r.right, r.top, r.right, r.bottom))
        }).filter { it.width < 1.5f || it.height < 1.5f }.filter { maxOf(it.width, it.height) >= 3f }.forEach { rules += Rule(it) }
        discard()
    }

    fun fill() {
        rects.filter { minOf(it.width, it.height) <= 2.5f && maxOf(it.width, it.height) >= 3f }.forEach { rules += Rule(it) }
        discard()
    }

    fun discard() { rects.clear(); segments.clear() }
}
