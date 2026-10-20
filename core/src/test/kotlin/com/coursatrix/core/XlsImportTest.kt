package com.coursatrix.core

import com.coursatrix.core.model.*
import com.coursatrix.core.transfer.SchoolXlsImporter
import com.coursatrix.core.transfer.XlsReader
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds tiny synthetic BIFF8 workbooks inside an OLE2 container, so no private school file is needed. */
private object XlsFixture {
    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    private fun record(type: Int, data: ByteArray) = le(4 + data.size).putShort(type.toShort()).putShort(data.size.toShort()).put(data).array()
    private fun utf16(text: String) = text.toByteArray(Charsets.UTF_16LE)
    private fun cell(row: Int, column: Int) = le(6).putShort(row.toShort()).putShort(column.toShort()).putShort(0).array()

    fun workbook(strings: List<String>, cells: List<ByteArray>, merged: List<IntArray>, padTo: Int = 0): ByteArray {
        val bof = { kind: Int -> record(0x0809, le(16).putShort(0x0600).putShort(kind.toShort()).array()) }
        // SST with the last string split across a CONTINUE record (continuation restarts with a flags byte).
        val sst = ByteArrayOutputStream().apply { write(le(8).putInt(strings.size).putInt(strings.size).array()) }
        val last = strings.last()
        strings.dropLast(1).forEach { sst.write(le(3).putShort(it.length.toShort()).put(1).array()); sst.write(utf16(it)) }
        sst.write(le(3).putShort(last.length.toShort()).put(1).array())
        sst.write(utf16(last.take(2)))
        val continuation = byteArrayOf(0) + last.drop(2).toByteArray(Charsets.ISO_8859_1) // compressed 8-bit chars
        val sheetName = "Sheet1"
        val boundSheetSize = 4 + 8 + sheetName.length
        val globals = ByteArrayOutputStream().apply {
            write(bof(0x0005))
            write(record(0x00FC, sst.toByteArray()))
            write(record(0x003C, continuation))
        }
        val sheetOffset = globals.size() + boundSheetSize + 4
        globals.write(record(0x0085, le(8 + sheetName.length).putInt(sheetOffset).put(0).put(0)
            .put(sheetName.length.toByte()).put(0).put(sheetName.toByteArray()).array()))
        globals.write(record(0x000A, ByteArray(0)))
        check(globals.size() == sheetOffset)
        globals.write(bof(0x0010))
        cells.forEach(globals::write)
        val mergedData = le(2 + merged.size * 8).putShort(merged.size.toShort())
        merged.forEach { r -> r.forEach { mergedData.putShort(it.toShort()) } }
        globals.write(record(0x00E5, mergedData.array()))
        globals.write(record(0x000A, ByteArray(0)))
        while (globals.size() < padTo) globals.write(0)
        return compound(globals.toByteArray())
    }

    fun labelSst(row: Int, column: Int, index: Int) = record(0x00FD, cell(row, column) + le(4).putInt(index).array())
    fun label(row: Int, column: Int, text: String) =
        record(0x0204, cell(row, column) + le(3).putShort(text.length.toShort()).put(1).array() + utf16(text))
    fun number(row: Int, column: Int, value: Double) = record(0x0203, cell(row, column) + le(8).putDouble(value).array())
    fun rkInt(row: Int, column: Int, value: Int) = record(0x027E, cell(row, column) + le(4).putInt((value shl 2) or 2).array())
    fun mulRk(row: Int, column: Int, values: List<Int>): ByteArray {
        val data = le(4 + values.size * 6 + 2).putShort(row.toShort()).putShort(column.toShort())
        values.forEach { data.putShort(0).putInt((it shl 2) or 2) }
        return record(0x00BD, data.putShort((column + values.size - 1).toShort()).array())
    }

    /** OLE2 container, sector size 512. Streams under 4096 bytes go into the mini stream like real files. */
    private fun compound(stream: ByteArray): ByteArray {
        val mini = stream.size < 4096
        val sectors = mutableListOf<ByteArray>()
        fun chunks(data: ByteArray, size: Int) = (data.indices step size).map { data.copyOfRange(it, minOf(it + size, data.size)).copyOf(size) }
        val fat = mutableListOf<Int>()
        fun addChain(parts: List<ByteArray>): Int {
            val start = sectors.size
            parts.forEachIndexed { i, part -> sectors += part; fat += if (i == parts.lastIndex) -2 else start + i + 1 }
            return start
        }
        var rootStart = -2
        var rootSize = 0
        var miniFatStart = -2
        val streamStart: Int
        if (mini) {
            val miniSectors = chunks(stream, 64)
            val container = miniSectors.fold(ByteArray(0)) { acc, b -> acc + b }
            rootStart = addChain(chunks(container, 512))
            rootSize = container.size
            val miniFat = le(512)
            miniSectors.indices.forEach { miniFat.putInt(if (it == miniSectors.lastIndex) -2 else it + 1) }
            while (miniFat.hasRemaining()) miniFat.putInt(-1)
            miniFatStart = addChain(listOf(miniFat.array()))
            streamStart = 0
        } else streamStart = addChain(chunks(stream, 512))
        val directory = le(512)
        fun entry(name: String, type: Int, start: Int, size: Int) {
            val base = directory.position()
            directory.put(utf16(name)); directory.position(base + 0x40)
            directory.putShort(((name.length + 1) * 2).toShort()).put(type.toByte())
            directory.position(base + 0x74); directory.putInt(start).putInt(size)
            directory.position(base + 128)
        }
        entry("Root Entry", 5, rootStart, rootSize)
        entry("Workbook", 2, streamStart, stream.size)
        val directoryStart = addChain(listOf(directory.array()))
        val fatSector = sectors.size
        fat += -3
        val fatBytes = le(512)
        fat.forEach { fatBytes.putInt(it) }
        while (fatBytes.hasRemaining()) fatBytes.putInt(-1)
        sectors += fatBytes.array()
        val header = le(512).put(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))
        header.position(0x18); header.putShort(0x3E).putShort(3).putShort(0xFFFE.toShort()).putShort(9).putShort(6)
        header.position(0x2C); header.putInt(1).putInt(directoryStart).putInt(0).putInt(4096)
            .putInt(miniFatStart).putInt(if (mini) 1 else 0).putInt(-2).putInt(0).putInt(fatSector)
        while (header.hasRemaining()) header.putInt(-1)
        return sectors.fold(header.array()) { acc, s -> acc + s }
    }

    val strings = listOf("示例大学", "姓名：不应保存 学号：123", "节次", "时间", "星期一", "星期二", "星期三", "星期四",
        "星期五", "星期六", "星期日", "8:00\n8:45", "8:55\n9:40",
        "示例课程(理论)\n\n示例教师【1-6(单)周】\nA101\n………………\n示例实验\n\n示例教师【2-6(双)周】\nB202",
        "第二门课\n教师乙【3-4,8周】", "ASCII tail split")

    fun timetable(padTo: Int = 0): ByteArray = workbook(strings, listOf(
        labelSst(0, 2, 0), labelSst(1, 2, 1),
        labelSst(2, 0, 2), labelSst(2, 1, 3),
    ) + (0..6).map { labelSst(2, 2 + it, 4 + it) } + listOf(
        number(3, 0, 1.0), labelSst(3, 1, 11), labelSst(3, 2, 13),
        rkInt(4, 0, 2), labelSst(4, 1, 12),
        mulRk(5, 0, listOf(3)), label(5, 1, "10:00\n10:45"), labelSst(5, 3, 14),
        labelSst(6, 9, 15),
    ), merged = listOf(intArrayOf(3, 4, 2, 2)), padTo = padTo)
}

/** Same timetable as [XlsFixture], written as a minimal .xlsx package. */
private object XlsxFixture {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun ref(row: Int, column: Int) = ('A' + column).toString() + (row + 1)

    fun timetable(extra: Map<String, String> = emptyMap(), workbook: Boolean = true): ByteArray {
        val strings = XlsFixture.strings
        // Index 0 uses rich-text runs plus a phonetic hint that must be ignored; 11 carries an escaped CR.
        val shared = strings.mapIndexed { i, s ->
            when (i) {
                0 -> "<si><r><rPr><b/></rPr><t>示例</t></r><r><t>大学</t></r><rPh sb=\"0\" eb=\"2\"><t>しれい</t></rPh></si>"
                11 -> "<si><t xml:space=\"preserve\">${esc(s).replace("\n", "_x000D_\n")}</t></si>"
                else -> "<si><t xml:space=\"preserve\">${esc(s)}</t></si>"
            }
        }.joinToString("")
        val cells = mutableMapOf<Int, MutableList<String>>()
        fun put(row: Int, column: Int, xml: String) { cells.getOrPut(row) { mutableListOf() } += xml }
        fun s(row: Int, column: Int, index: Int) = put(row, column, "<c r=\"${ref(row, column)}\" t=\"s\"><v>$index</v></c>")
        s(0, 2, 0); s(1, 2, 1); s(2, 0, 2); s(2, 1, 3)
        (0..6).forEach { s(2, 2 + it, 4 + it) }
        put(3, 0, "<c r=\"A4\"><v>1</v></c>"); s(3, 1, 11); s(3, 2, 13)
        put(4, 0, "<c r=\"A5\" t=\"n\"><v>2</v></c>"); s(4, 1, 12)
        put(5, 0, "<c r=\"A6\"><f>A5+1</f><v>3</v></c>")
        put(5, 1, "<c r=\"B6\" t=\"inlineStr\"><is><t>10:00\n10:45</t></is></c>"); s(5, 3, 14)
        s(6, 9, 15)
        val rows = cells.toSortedMap().entries.joinToString("") { (r, list) -> "<row r=\"${r + 1}\">${list.joinToString("")}</row>" }
        val files = linkedMapOf(
            "[Content_Types].xml" to "<?xml version=\"1.0\"?><Types/>",
            "xl/_rels/workbook.xml.rels" to "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId7\" Type=\"worksheet\" Target=\"/xl/worksheets/timetable.xml\"/></Relationships>",
            "xl/sharedStrings.xml" to "<?xml version=\"1.0\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">$shared</sst>",
            "xl/worksheets/timetable.xml" to "<?xml version=\"1.0\"?><x:worksheet xmlns:x=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<x:sheetData>" + rows.replace("<", "<x:").replace("<x:/", "</x:") + "</x:sheetData>" +
                "<x:mergeCells count=\"1\"><x:mergeCell ref=\"C4:C5\"/></x:mergeCells></x:worksheet>",
        )
        if (workbook) files["xl/workbook.xml"] = "<?xml version=\"1.0\"?><workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets><sheet name=\"课表\" sheetId=\"1\" r:id=\"rId7\"/></sheets></workbook>"
        files.putAll(extra)
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            files.forEach { (name, text) -> zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
        return out.toByteArray()
    }
}

class XlsImportTest {
    @Test fun xlsxGivesSameResultAsXls() {
        val sheet = com.coursatrix.core.transfer.XlsxReader.read(XlsxFixture.timetable())
        assertEquals("示例大学", sheet.text(0, 2))
        assertEquals("8:00\n8:45", sheet.text(3, 1))
        assertEquals(3.0, sheet.number(5, 0))
        val fromXlsx = SchoolXlsImporter.decode(XlsxFixture.timetable(), current()).data
        val fromXls = SchoolXlsImporter.decode(XlsFixture.timetable(), current()).data
        fun shape(d: AppData) = d.meetings.map { m -> listOf(d.courses.single { it.id == m.courseId }.name, m.weekday,
            m.startPeriod, m.endPeriod, m.weeks, m.location) }.sortedBy { it.toString() }
        assertEquals(shape(fromXls), shape(fromXlsx))
        assertEquals(fromXls.periods, fromXlsx.periods)
        assertEquals("示例大学", fromXlsx.semester.school)
    }

    @Test fun xlsxRejectsOtherZipsDoctypeAndBrokenArchives() {
        assertThrows(IllegalArgumentException::class.java) { SchoolXlsImporter.decode(XlsxFixture.timetable(workbook = false), current()) }
        val doctype = XlsxFixture.timetable(mapOf("xl/sharedStrings.xml" to
            "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><sst><si><t>&e;</t></si></sst>"))
        assertThrows(IllegalArgumentException::class.java) { SchoolXlsImporter.decode(doctype, current()) }
        val bytes = XlsxFixture.timetable()
        assertThrows(IllegalArgumentException::class.java) { SchoolXlsImporter.decode(bytes.copyOf(bytes.size / 2), current()) }
    }

    /** Optional: an .xlsx copy of the private sample, e.g. re-saved by Excel/WPS. */
    @Test fun privateXlsxSampleIfPresent() {
        val file = File("../Import/xskb.xlsx")
        assumeTrue(file.isFile)
        val data = SchoolXlsImporter.decode(file.readBytes(), current()).data
        assertEquals(10, data.courses.size)
        assertEquals(18, data.meetings.size)
        assertEquals(12, data.periods.size)
    }

    private fun current() = AppData(semester = Semester(id = "term", firstMonday = "2026-09-07", weekCount = 20))

    @Test fun readsMiniAndRegularStreamsWithContinuedStrings() {
        for (bytes in listOf(XlsFixture.timetable(), XlsFixture.timetable(padTo = 6000))) {
            val sheet = XlsReader.read(bytes)
            assertEquals("示例大学", sheet.text(0, 2))
            assertEquals("ASCII tail split", sheet.text(6, 9))
            assertEquals(1.0, sheet.number(3, 0))
            assertEquals(2.0, sheet.number(4, 0))
            assertEquals(3.0, sheet.number(5, 0))
            assertEquals("10:00\n10:45", sheet.text(5, 1))
            assertEquals(3, sheet.merged.single().firstRow)
        }
    }

    @Test fun convertsGridWithMergedPeriodsParityAndNoStudentIdentity() {
        val result = SchoolXlsImporter.decode(XlsFixture.timetable(), current())
        val data = result.data
        assertTrue(result.periodsFromFile)
        assertEquals("示例大学", data.semester.school)
        assertEquals("2026-09-07", data.semester.firstMonday)
        assertEquals(listOf("08:00", "08:55", "10:00"), data.periods.map { it.start })
        assertEquals(setOf("示例课程(理论)", "示例实验", "第二门课"), data.courses.map { it.name }.toSet())
        val lab = data.meetings.single { m -> data.courses.single { it.id == m.courseId }.name == "示例实验" }
        assertEquals(listOf(2, 4, 6), lab.weeks)
        assertEquals(1 to 2, lab.startPeriod to lab.endPeriod)
        assertEquals("B202", lab.location)
        val second = data.meetings.single { it.weekday == 2 }
        assertEquals(listOf(3, 4, 8), second.weeks)
        assertEquals("", second.location)
        assertFalse(data.toString().contains("不应保存"))
    }

    @Test fun reimportKeepsExistingCourseIdsColorsAndNotes() {
        val first = SchoolXlsImporter.decode(XlsFixture.timetable(), current()).data
        val edited = first.copy(courses = first.courses.map { it.copy(color = "#123456", note = "我的笔记") })
        val again = SchoolXlsImporter.decode(XlsFixture.timetable(), edited)
        assertEquals(3, again.reusedCourses)
        assertEquals(edited.courses.map { it.id }.toSet(), again.data.courses.map { it.id }.toSet())
        assertTrue(again.data.courses.all { it.color == "#123456" && it.note == "我的笔记" })
    }

    @Test fun weekRulesAreExplicitAndNotGuessed() {
        assertEquals(listOf(5, 6, 7, 8, 10, 12), SchoolXlsImporter.parseWeeks("5-8,10-10,12-12周"))
        assertEquals(listOf(9, 11, 13, 15), SchoolXlsImporter.parseWeeks("9-15(单)周"))
        assertEquals(listOf(2, 4, 6), SchoolXlsImporter.parseWeeks("2-6（双）周"))
        for (rule in listOf("0-6", "6-2", "1-99", "不明", "2-2(单)")) {
            assertThrows(IllegalArgumentException::class.java) { SchoolXlsImporter.parseWeeks(rule) }
        }
    }

    @Test fun rejectsNonXlsAndTruncatedFiles() {
        assertThrows(IllegalArgumentException::class.java) { XlsReader.read("课程名称,教师".toByteArray()) }
        val bytes = XlsFixture.timetable()
        assertThrows(IllegalArgumentException::class.java) { XlsReader.read(bytes.copyOf(700)) }
    }

    /** Runs only on a machine that has the private sample; compares with the Python converter's known output. */
    @Test fun privateSchoolSampleIfPresent() {
        val file = File("../Import/xskb.xls")
        assumeTrue(file.isFile)
        val data = SchoolXlsImporter.decode(file.readBytes(), current()).data
        assertEquals(10, data.courses.size)
        assertEquals(18, data.meetings.size)
        assertEquals(12, data.periods.size)
        assertEquals("14:30", data.periods[5].start)
        assertTrue(data.semester.school.endsWith("学院"))
    }
}
