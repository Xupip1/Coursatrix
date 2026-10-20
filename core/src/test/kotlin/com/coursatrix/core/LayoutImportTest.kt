package com.coursatrix.core

import com.coursatrix.core.model.*
import com.coursatrix.core.transfer.SchoolXlsImporter
import com.coursatrix.core.transfer.layout.*
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.geom.Point2D
import java.io.File

/** Spreads a line of text evenly over its box, one glyph per character. */
private fun line(text: String, centerX: Float, centerY: Float, charWidth: Float = 18f, height: Float = 20f): List<Glyph> {
    val left = centerX - text.length * charWidth / 2
    return text.mapIndexed { i, c -> Glyph(c.toString(), Box(left + i * charWidth, centerY - height / 2, left + (i + 1) * charWidth, centerY + height / 2)) }
}

/** Mirrors the app's pdfbox-android adapter, using desktop PDFBox (same API under a different package). */
private fun pdfLayout(file: File): PageLayout = PDDocument.load(file).use { doc ->
    val page = doc.getPage(0)
    val glyphs = mutableListOf<Glyph>()
    object : PDFTextStripper() {
        override fun writeString(text: String, positions: MutableList<TextPosition>) {
            positions.forEach { p ->
                val unicode = p.unicode ?: return@forEach
                if (unicode.isBlank()) return@forEach
                val height = maxOf(p.heightDir, p.fontSizeInPt * 0.7f)
                glyphs += Glyph(unicode, Box(p.xDirAdj, p.yDirAdj - height, p.xDirAdj + p.widthDirAdj, p.yDirAdj))
            }
        }
    }.apply { startPage = 1; endPage = 1; sortByPosition = true }.getText(doc)
    val collector = PdfRuleCollector(page.cropBox.lowerLeftX, page.cropBox.upperRightY)
    object : PDFGraphicsStreamEngine(page) {
        private val current = Point2D.Float()
        override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) = collector.rectangle(
            floatArrayOf(p0.x.toFloat(), p1.x.toFloat(), p2.x.toFloat(), p3.x.toFloat()),
            floatArrayOf(p0.y.toFloat(), p1.y.toFloat(), p2.y.toFloat(), p3.y.toFloat()))
        override fun drawImage(pdImage: PDImage) {}
        override fun clip(windingRule: Int) {}
        override fun moveTo(x: Float, y: Float) { collector.moveTo(x, y); current.setLocation(x, y) }
        override fun lineTo(x: Float, y: Float) { collector.lineTo(x, y); current.setLocation(x, y) }
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { collector.moveTo(x3, y3); current.setLocation(x3, y3) }
        override fun getCurrentPoint(): Point2D = current
        override fun closePath() {}
        override fun endPath() = collector.discard()
        override fun strokePath() = collector.stroke()
        override fun fillPath(windingRule: Int) = collector.fill()
        override fun fillAndStrokePath(windingRule: Int) = collector.stroke()
        override fun shadingFill(shadingName: COSName) {}
    }.processPage(page)
    PageLayout(glyphs, collector.rules)
}

class LayoutImportTest {
    private fun current() = AppData(semester = Semester(id = "term", firstMonday = "2026-09-07", weekCount = 20))

    @Test fun parserHandlesWrappedPdfCells() {
        val course = CourseCellParser.parse(listOf("军事理论(理论)", "钱老师", "【5-8,10-10,12-12,14-1", "4,16-16周】", "A309")).single()
        assertEquals(ParsedCourse("军事理论(理论)", "钱老师", "A309", listOf(5, 6, 7, 8, 10, 12, 14, 16), emptyList()), course)
        val wrappedName = CourseCellParser.parse(listOf("大学生职业发展与就业指导", "(理论)", "孙老师【6-13周】", "B108")).single()
        assertEquals("大学生职业发展与就业指导(理论)", wrappedName.name)
        assertEquals("孙老师", wrappedName.teacher)
        val two = CourseCellParser.parse(listOf("大学英语视听说1(理论)", "周老师【10-12(双)周】", "C414", "………………",
            "形势与政策1(理论)", "吴老师【9-15(单)周】", "A210"))
        assertEquals(listOf(listOf(10, 12), listOf(9, 11, 13, 15)), two.map { it.weeks })
    }

    @Test fun parserHandlesAppExportFormatAndFlagsTruncation() {
        val course = CourseCellParser.parse(listOf("大学英", "语读...@", "示例校", "区B楼", "[B213]")).single()
        assertEquals("大学英语读...", course.name)
        assertEquals("示例校区B楼[B213]", course.location)
        assertNull(course.weeks)
        assertTrue(course.issues.any { "截断" in it })
        val bad = CourseCellParser.parse(listOf("高数", "张三【第一周】")).single()
        assertNull(bad.weeks)
        assertTrue(bad.issues.isNotEmpty())
    }

    @Test fun truncatedNamesMatchTheOnlyExistingCourse() {
        val semester = current().semester
        val data = current().copy(courses = listOf(
            Course(id = "a", semesterId = semester.id, name = "大学英语读写1(理论)", teacher = "周老师", color = "#123456"),
            Course(id = "b", semesterId = semester.id, name = "大学英语视听说1(理论)", teacher = "周老师"),
        ))
        assertEquals("a", DraftImporter.match("大学英语读...", "", data)?.id)
        assertNull(DraftImporter.match("大学英语...", "", data)) // ambiguous: never guess
        val built = DraftImporter.build(listOf(DraftEntry("大学英语读...", "", "B213", 4, 1, 2, listOf(7))), data)
        assertEquals(Course(id = "a", semesterId = semester.id, name = "大学英语读写1(理论)", teacher = "周老师", color = "#123456"),
            built.courses.single())
    }

    @Test fun syntheticRuledGridWithMergedCellAndTimes() {
        val glyphs = mutableListOf<Glyph>()
        val days = listOf("星期一", "星期二", "星期三", "星期四", "星期五")
        days.forEachIndexed { i, d -> glyphs += line(d, 150f + i * 100f, 20f, 12f, 12f) }
        val rowCenters = listOf(60f, 100f, 140f)
        rowCenters.forEachIndexed { i, y -> glyphs += line("${i + 1}", 30f, y, 8f, 12f); glyphs += line("${8 + i}:00", 70f, y - 7, 5f, 10f); glyphs += line("${8 + i}:45", 70f, y + 7, 5f, 10f) }
        // Monday periods 1–2 merged; Wednesday period 3.
        glyphs += line("高等数学", 150f, 70f, 12f, 12f) + line("张老师【1-16周】", 150f, 86f, 9f, 12f)
        glyphs += line("体育", 350f, 134f, 12f, 12f) + line("李老师【2-8(双)周】", 350f, 148f, 7f, 12f)
        val rules = mutableListOf<Rule>()
        listOf(40f, 80f, 120f, 160f).forEach { y -> rules += Rule(Box(0f, y, 600f, y + 0.5f)) }
        listOf(100f, 200f, 300f, 400f, 500f, 600f).forEach { x -> rules += Rule(Box(x, 0f, x + 0.5f, 160f)) }
        // Remove the Monday line between periods 1 and 2 to merge those cells.
        rules.removeAt(1)
        rules.add(Rule(Box(200f, 80f, 600f, 80.5f)))
        val draft = GridReconstructor.reconstruct(PageLayout(glyphs, rules))
        assertEquals(listOf("08:00", "09:00", "10:00"), draft.periods!!.map { it.start })
        val math = draft.entries.single { it.name == "高等数学" }
        assertEquals(Triple(1, 1, 2), Triple(math.weekday, math.startPeriod, math.endPeriod))
        val pe = draft.entries.single { it.name == "体育" }
        assertEquals(Triple(3, 3, 3), Triple(pe.weekday, pe.startPeriod, pe.endPeriod))
        assertEquals(listOf(2, 4, 6, 8), pe.weeks)
    }

    @Test fun headerlessInputIsRejectedClearly() {
        val error = assertThrows(IllegalArgumentException::class.java) { GridReconstructor.reconstruct(PageLayout(line("随便一些文字", 100f, 100f))) }
        assertTrue(error.message!!.contains("表头"))
    }

    /** Private sample: the school PDF must give exactly what the school XLS gives. */
    @Test fun privatePdfMatchesXls() {
        val pdf = File("../Import/xskb.pdf")
        val xls = File("../Import/xskb.xls")
        assumeTrue(pdf.isFile && xls.isFile)
        val expected = SchoolXlsImporter.decode(xls.readBytes(), current()).data
        val draft = GridReconstructor.reconstruct(pdfLayout(pdf))
        assertNull(draft.periods)
        val problems = draft.entries.filter { it.issues.isNotEmpty() }
        assertTrue(problems.toString(), problems.isEmpty())
        fun AppData.shape() = meetings.map { m -> courses.single { it.id == m.courseId }.let { c ->
            listOf(c.name, c.teacher, m.location, m.weekday, m.startPeriod, m.endPeriod, m.weeks) } }.sortedBy { it.toString() }
        assertEquals(expected.shape(), DraftImporter.build(draft.entries, current()).shape())
    }
}
