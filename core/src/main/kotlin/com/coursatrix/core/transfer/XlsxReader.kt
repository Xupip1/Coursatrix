package com.coursatrix.core.transfer

import com.coursatrix.core.validation.Limits
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Minimal reader for Excel 2007+ (.xlsx, Office Open XML). Reads only cell values and merged ranges of the
 * first worksheet; formulas are not evaluated (their cached results are used) and macros are never run.
 */
object XlsxReader {
    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    private const val MAX_ENTRIES = 2000
    private const val MAX_CELLS = 20_000
    private val cellReference = Regex("([A-Z]{1,3})(\\d{1,7})")
    private val escaped = Regex("_x([0-9A-Fa-f]{4})_")

    fun isZip(bytes: ByteArray): Boolean = bytes.size >= 4 &&
        bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() && bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    fun read(bytes: ByteArray): XlsSheet {
        require(bytes.size <= Limits.FILE_BYTES) { "文件超过 4 MiB" }
        require(isZip(bytes)) { "不是 Excel 2007+ (.xlsx) 文件" }
        val entries = unzip(bytes)
        val workbook = requireNotNull(entries["xl/workbook.xml"]) { "压缩包中没有 Excel 工作簿，可能是 Word/PPT 等其他文件" }
        val sheetId = parse(workbook, object : Handler() {
            var id: String? = null
            override fun start(name: String, attributes: Attributes) {
                if (name == "sheet" && id == null) id = attribute(attributes, "id")
            }
        }).id
        val target = entries["xl/_rels/workbook.xml.rels"]?.let { rels ->
            parse(rels, object : Handler() {
                var target: String? = null
                override fun start(name: String, attributes: Attributes) {
                    if (name == "Relationship" && attributes.getValue("Id") == sheetId) target = attributes.getValue("Target")
                }
            }).target
        }
        val sheetPath = when {
            target == null -> "xl/worksheets/sheet1.xml"
            target.startsWith("/") -> target.removePrefix("/")
            else -> "xl/" + target.removePrefix("./")
        }
        val sheet = requireNotNull(entries[sheetPath]) { "找不到第一个工作表" }
        val strings = entries["xl/sharedStrings.xml"]?.let(::sharedStrings).orEmpty()
        return worksheet(sheet, strings)
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val wanted = { name: String -> name == "xl/workbook.xml" || name == "xl/_rels/workbook.xml.rels" ||
            name == "xl/sharedStrings.xml" || name.startsWith("xl/worksheets/") && name.endsWith(".xml") }
        val result = HashMap<String, ByteArray>()
        var total = 0L
        var count = 0
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= MAX_ENTRIES) { "压缩包内文件过多，不像课表文件" }
                    val name = entry.name.replace('\\', '/').removePrefix("/")
                    if (entry.isDirectory || !wanted(name)) continue
                    val out = ByteArrayOutputStream()
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        // Guards against "zip bombs" that expand to huge sizes.
                        require(out.size() + read <= MAX_ENTRY_BYTES && total <= MAX_TOTAL_BYTES) { "表格解压后过大，不像课表文件" }
                        out.write(buffer, 0, read)
                    }
                    result[name] = out.toByteArray()
                }
            }
        } catch (e: IOException) { // includes ZipException and EOF on truncated files
            throw IllegalArgumentException("xlsx 文件已损坏或不是有效的压缩包", e)
        }
        return result
    }

    private abstract class Handler : DefaultHandler() {
        val text = StringBuilder()
        open fun start(name: String, attributes: Attributes) {}
        open fun end(name: String) {}
        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) =
            start(qName.substringAfter(':'), attributes)
        override fun endElement(uri: String?, localName: String?, qName: String) = end(qName.substringAfter(':'))
        override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }
        // Never fetch external entities.
        override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(ByteArrayInputStream(ByteArray(0)))
        fun attribute(attributes: Attributes, local: String): String? =
            (0 until attributes.length).firstOrNull { attributes.getQName(it).substringAfter(':') == local }?.let(attributes::getValue)
    }

    private fun <T : Handler> parse(xml: ByteArray, handler: T): T {
        val head = String(xml, 0, minOf(xml.size, 4096), Charsets.ISO_8859_1)
        require(!head.contains("<!DOCTYPE", ignoreCase = true)) { "xlsx 中含有不允许的 XML 声明" }
        try {
            SAXParserFactory.newInstance().newSAXParser().parse(ByteArrayInputStream(xml), handler)
        } catch (e: org.xml.sax.SAXException) {
            throw IllegalArgumentException("xlsx 内容损坏，无法读取：${e.message}", e)
        }
        return handler
    }

    /** Each <si> is plain <t> or rich-text runs <r><t>; phonetic hints <rPh> are skipped. */
    private fun sharedStrings(xml: ByteArray): List<String> = parse(xml, object : Handler() {
        val values = ArrayList<String>()
        val current = StringBuilder()
        var phonetic = 0
        override fun start(name: String, attributes: Attributes) {
            when (name) {
                "si" -> current.setLength(0)
                "rPh" -> phonetic++
                "t" -> text.setLength(0)
            }
        }
        override fun end(name: String) {
            when (name) {
                "t" -> if (phonetic == 0) current.append(text)
                "rPh" -> phonetic--
                "si" -> {
                    require(values.size < MAX_CELLS * 4) { "共享字符串过多，不像课表文件" }
                    values += unescape(current.toString())
                }
            }
        }
    }).values

    private fun worksheet(xml: ByteArray, strings: List<String>): XlsSheet {
        val handler = parse(xml, object : Handler() {
            val cells = HashMap<Pair<Int, Int>, Any>()
            val merged = mutableListOf<CellRange>()
            var row = -1
            var column = -1
            var type = ""
            var value: String? = null
            var inline = StringBuilder()
            var inCell = false
            override fun start(name: String, attributes: Attributes) {
                when (name) {
                    "row" -> { row = attributes.getValue("r")?.toIntOrNull()?.minus(1) ?: (row + 1); column = -1 }
                    "c" -> {
                        val position = attributes.getValue("r")?.let(::position)
                        row = position?.first ?: row
                        column = position?.second ?: (column + 1)
                        type = attributes.getValue("t").orEmpty()
                        value = null
                        inline.setLength(0)
                        inCell = true
                    }
                    "v", "t" -> text.setLength(0)
                    "mergeCell" -> attributes.getValue("ref")?.let { ref ->
                        val parts = ref.split(':')
                        val first = position(parts[0])
                        val last = position(parts.getOrElse(1) { parts[0] })
                        if (first != null && last != null) merged += CellRange(first.first, last.first, first.second, last.second)
                    }
                }
            }
            override fun end(name: String) {
                when (name) {
                    "v" -> if (inCell) value = text.toString()
                    "t" -> if (inCell) inline.append(text)
                    "c" -> {
                        inCell = false
                        val cell: Any? = when (type) {
                            "s" -> value?.trim()?.toIntOrNull()?.let { index ->
                                require(index in strings.indices) { "xlsx 共享字符串索引越界" }
                                strings[index]
                            }
                            "inlineStr" -> unescape(inline.toString())
                            "str" -> value?.let(::unescape)
                            "b", "e" -> value
                            else -> value?.trim()?.toDoubleOrNull()
                        }
                        if (cell != null && row >= 0 && column >= 0) {
                            require(cells.size < MAX_CELLS) { "表格单元格过多，超出课表文件的合理范围" }
                            cells[row to column] = cell
                        }
                    }
                }
            }
        })
        return XlsSheet(handler.cells, handler.merged)
    }

    /** "B12" → zero-based (row 11, column 1). */
    private fun position(reference: String): Pair<Int, Int>? {
        val match = cellReference.matchEntire(reference.replace("$", "").uppercase()) ?: return null
        val column = match.groupValues[1].fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1
        return match.groupValues[2].toInt() - 1 to column
    }

    /** Excel escapes control characters such as CR as "_x000D_". */
    private fun unescape(value: String): String = value.replace(escaped) { match ->
        match.groupValues[1].toInt(16).toChar().let { if (it == '\r') "" else it.toString() }
    }
}
