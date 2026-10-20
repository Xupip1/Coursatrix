package com.coursatrix.core.transfer

import com.coursatrix.core.validation.Limits

/** Inclusive cell range from a MERGEDCELLS record, zero-based. */
data class CellRange(val firstRow: Int, val lastRow: Int, val firstColumn: Int, val lastColumn: Int) {
    operator fun contains(cell: Pair<Int, Int>) = cell.first in firstRow..lastRow && cell.second in firstColumn..lastColumn
}

/** Values of the first worksheet: text cells, numeric cells (as Double) and merged ranges. Zero-based. */
class XlsSheet(private val cells: Map<Pair<Int, Int>, Any>, val merged: List<CellRange>) {
    val rows: Int = (cells.keys.maxOfOrNull { it.first } ?: -1) + 1
    val columns: Int = (cells.keys.maxOfOrNull { it.second } ?: -1) + 1
    fun number(row: Int, column: Int): Double? = cells[row to column] as? Double
    fun text(row: Int, column: Int): String = when (val value = cells[row to column]) {
        is String -> value
        is Double -> if (value == Math.floor(value) && value in -1e9..1e9) value.toLong().toString() else value.toString()
        else -> ""
    }
}

/**
 * Minimal reader for legacy Excel 97–2003 (.xls, BIFF8 inside an OLE2 compound file).
 * Reads only cell values and merged ranges of the first worksheet; never evaluates formulas or macros.
 */
object XlsReader {
    private const val END_OF_CHAIN = -2
    private const val FREE = -1
    private const val MAX_CELLS = 20_000

    fun isXls(bytes: ByteArray): Boolean = bytes.size >= 8 &&
        bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))

    fun read(bytes: ByteArray): XlsSheet {
        require(bytes.size <= Limits.FILE_BYTES) { "文件超过 4 MiB" }
        require(isXls(bytes)) { "不是 Excel 97-2003 (.xls) 文件" }
        val workbook = try {
            val file = CompoundFile(bytes)
            // Password-protected .xlsx files are also stored in an OLE2 container.
            require(file.stream("EncryptedPackage") == null) { "表格已设置打开密码，请先在 Excel/WPS 中取消密码后再导入" }
            require(file.stream("Book") == null || file.stream("Workbook") != null) {
                "这是 Excel 95 或更早的格式，请在 Excel/WPS 中另存为「Excel 97-2003 工作簿」"
            }
            requireNotNull(file.stream("Workbook")) { "XLS 文件中没有工作簿数据" }
        } catch (e: IndexOutOfBoundsException) {
            throw IllegalArgumentException("XLS 文件结构损坏，无法读取", e)
        }
        return try {
            parseWorkbook(workbook)
        } catch (e: IndexOutOfBoundsException) {
            throw IllegalArgumentException("XLS 工作表记录损坏，无法读取", e)
        }
    }

    private class CompoundFile(private val bytes: ByteArray) {
        private val sectorSize: Int
        private val miniSectorSize: Int
        private val miniCutoff: Int
        private val fat: IntArray
        private val sectorCount: Int

        init {
            val shift = u16(bytes, 0x1E)
            require(shift == 9 || shift == 12) { "不支持的 XLS 扇区大小" }
            sectorSize = 1 shl shift
            miniSectorSize = 1 shl u16(bytes, 0x20)
            require(miniSectorSize == 64) { "不支持的 XLS 小扇区大小" }
            miniCutoff = i32(bytes, 0x38)
            sectorCount = (bytes.size - sectorSize) / sectorSize
            val fatSectors = mutableListOf<Int>()
            for (i in 0 until 109) i32(bytes, 0x4C + i * 4).takeIf { it >= 0 }?.let(fatSectors::add)
            var difat = i32(bytes, 0x44)
            var guard = 0
            while (difat >= 0 && guard++ <= sectorCount) {
                val base = offset(difat)
                val perSector = sectorSize / 4 - 1
                for (i in 0 until perSector) i32(bytes, base + i * 4).takeIf { it >= 0 }?.let(fatSectors::add)
                difat = i32(bytes, base + perSector * 4)
            }
            require(fatSectors.size <= sectorCount) { "XLS 分配表异常" }
            val perSector = sectorSize / 4
            fat = IntArray(fatSectors.size * perSector)
            fatSectors.forEachIndexed { index, sector ->
                val base = offset(sector)
                for (i in 0 until perSector) fat[index * perSector + i] = i32(bytes, base + i * 4)
            }
        }

        private fun offset(sector: Int): Int {
            require(sector in 0 until sectorCount) { "XLS 扇区编号越界" }
            return (sector + 1) * sectorSize
        }

        private fun chain(start: Int, table: IntArray, limit: Int): List<Int> {
            val result = mutableListOf<Int>()
            var sector = start
            while (sector != END_OF_CHAIN && sector != FREE) {
                require(sector in table.indices && result.size < limit) { "XLS 扇区链损坏或存在循环" }
                result += sector
                sector = table[sector]
            }
            return result
        }

        private fun readBig(start: Int, size: Int): ByteArray {
            val out = ByteArray(size)
            var written = 0
            for (sector in chain(start, fat, sectorCount)) {
                if (written >= size) break
                val count = minOf(sectorSize, size - written)
                System.arraycopy(bytes, offset(sector), out, written, count)
                written += count
            }
            require(written == size) { "XLS 数据流被截断" }
            return out
        }

        fun stream(name: String): ByteArray? {
            val root = readBig(i32(bytes, 0x30), chain(i32(bytes, 0x30), fat, sectorCount).size * sectorSize)
            var rootStart = 0
            var rootSize = 0
            for (base in 0 until root.size / 128 * 128 step 128) {
                val type = root[base + 0x42].toInt()
                val nameBytes = u16(root, base + 0x40).coerceIn(0, 64)
                val entryName = String(root, base, (nameBytes - 2).coerceAtLeast(0), Charsets.UTF_16LE)
                val start = i32(root, base + 0x74)
                val size = i32(root, base + 0x78)
                if (type == 5) { rootStart = start; rootSize = size }
                if (type == 2 && entryName.equals(name, ignoreCase = true)) {
                    require(size in 0..Limits.FILE_BYTES) { "XLS 数据流大小异常" }
                    if (size >= miniCutoff) return readBig(start, size)
                    val miniStream = readBig(rootStart, rootSize)
                    val miniFatStart = i32(bytes, 0x3C)
                    val miniFatBytes = if (miniFatStart < 0) ByteArray(0)
                        else readBig(miniFatStart, chain(miniFatStart, fat, sectorCount).size * sectorSize)
                    val miniFat = IntArray(miniFatBytes.size / 4) { i32(miniFatBytes, it * 4) }
                    val out = ByteArray(size)
                    var written = 0
                    for (sector in chain(start, miniFat, miniFat.size)) {
                        if (written >= size) break
                        val count = minOf(miniSectorSize, size - written)
                        System.arraycopy(miniStream, sector * miniSectorSize, out, written, count)
                        written += count
                    }
                    require(written == size) { "XLS 数据流被截断" }
                    return out
                }
            }
            return null
        }
    }

    private class Record(val type: Int, val data: ByteArray)

    private fun records(stream: ByteArray, from: Int): Sequence<Record> = sequence {
        var position = from
        while (position + 4 <= stream.size) {
            val type = u16(stream, position)
            val length = u16(stream, position + 2)
            require(position + 4 + length <= stream.size) { "XLS 记录被截断" }
            yield(Record(type, stream.copyOfRange(position + 4, position + 4 + length)))
            position += 4 + length
        }
    }

    private fun parseWorkbook(stream: ByteArray): XlsSheet {
        val strings = mutableListOf<String>()
        var sheetOffset: Int? = null
        val globals = records(stream, 0).iterator()
        val first = globals.next()
        require(first.type == 0x0809 && u16(first.data, 0) == 0x0600) {
            "仅支持 Excel 97-2003 (BIFF8) 格式，请在 Excel/WPS 中另存为「Excel 97-2003 工作簿」"
        }
        var sst: MutableList<ByteArray>? = null
        while (globals.hasNext()) {
            val record = globals.next()
            val pending = sst
            if (record.type == 0x003C && pending != null) { pending += record.data; continue }
            if (pending != null) { strings += readSst(pending); sst = null }
            when (record.type) {
                0x002F -> throw IllegalArgumentException("XLS 文件已加密或设置了密码，请先在 Excel 中取消保护")
                0x0085 -> if (sheetOffset == null && record.data[5].toInt() == 0) sheetOffset = i32(record.data, 0)
                0x00FC -> sst = mutableListOf(record.data)
                0x000A -> break
            }
        }
        sst?.let { strings += readSst(it) }
        val offset = requireNotNull(sheetOffset) { "XLS 中没有工作表" }
        require(offset in 0 until stream.size) { "XLS 工作表位置无效" }
        val cells = HashMap<Pair<Int, Int>, Any>()
        val merged = mutableListOf<CellRange>()
        var pendingFormula: Pair<Int, Int>? = null
        fun put(row: Int, column: Int, value: Any) {
            require(cells.size < MAX_CELLS) { "表格单元格过多，超出课表文件的合理范围" }
            cells[row to column] = value
        }
        for (record in records(stream, offset)) {
            val d = record.data
            when (record.type) {
                0x00FD -> {
                    val index = i32(d, 6)
                    require(index in strings.indices) { "XLS 共享字符串索引越界" }
                    put(u16(d, 0), u16(d, 2), strings[index])
                }
                0x0204 -> put(u16(d, 0), u16(d, 2), ByteReader(listOf(d), 6).let { it.unicodeString(it.short()) })
                0x0203 -> put(u16(d, 0), u16(d, 2), java.lang.Double.longBitsToDouble(i64(d, 6)))
                0x027E -> put(u16(d, 0), u16(d, 2), rk(i32(d, 6)))
                0x00BD -> {
                    val row = u16(d, 0)
                    val firstColumn = u16(d, 2)
                    val count = (d.size - 6) / 6
                    for (i in 0 until count) put(row, firstColumn + i, rk(i32(d, 4 + i * 6 + 2)))
                }
                0x0006 -> {
                    val row = u16(d, 0)
                    val column = u16(d, 2)
                    pendingFormula = null
                    if (u16(d, 12) != 0xFFFF) put(row, column, java.lang.Double.longBitsToDouble(i64(d, 6)))
                    else if (d[6].toInt() == 0) pendingFormula = row to column
                }
                0x0207 -> pendingFormula?.let { (row, column) ->
                    put(row, column, ByteReader(listOf(d), 0).let { it.unicodeString(it.short()) })
                    pendingFormula = null
                }
                0x00E5 -> {
                    val count = u16(d, 0)
                    for (i in 0 until count) {
                        val base = 2 + i * 8
                        if (base + 8 > d.size) break
                        merged += CellRange(u16(d, base), u16(d, base + 2), u16(d, base + 4), u16(d, base + 6))
                    }
                }
                0x000A -> break
            }
        }
        return XlsSheet(cells, merged)
    }

    /** Reads SST strings; segments are the SST record followed by its CONTINUE records. */
    private fun readSst(segments: List<ByteArray>): List<String> {
        val reader = ByteReader(segments, 0)
        reader.skip(4) // total string references
        val unique = reader.int()
        require(unique in 0..MAX_CELLS * 4) { "XLS 共享字符串数量异常" }
        val result = ArrayList<String>(unique)
        repeat(unique) {
            if (reader.atEnd()) return result
            result += reader.unicodeString(reader.short())
        }
        return result
    }

    /** Reads BIFF8 data that may continue across CONTINUE records. */
    private class ByteReader(private val segments: List<ByteArray>, private var position: Int) {
        private var segment = 0

        fun atEnd(): Boolean {
            while (segment < segments.size && position >= segments[segment].size) { segment++; position = 0 }
            return segment >= segments.size
        }

        fun byte(): Int {
            require(!atEnd()) { "XLS 字符串数据被截断" }
            return segments[segment][position++].toInt() and 0xFF
        }

        fun short(): Int = byte() or (byte() shl 8)
        fun int(): Int = short() or (short() shl 16)
        fun skip(count: Int) = repeat(count) { byte() }

        /** Reads flags + characters for a string whose character count was already read. */
        fun unicodeString(length: Int): String {
            var flags = byte()
            val runs = if (flags and 0x08 != 0) short() else 0
            val extended = if (flags and 0x04 != 0) int() else 0
            val builder = StringBuilder(length)
            while (builder.length < length) {
                if (position >= segments[segment].size) {
                    segment++
                    position = 0
                    require(segment < segments.size) { "XLS 字符串数据被截断" }
                    flags = byte()
                }
                builder.append(if (flags and 0x01 != 0) short().toChar() else byte().toChar())
            }
            skip(runs * 4)
            skip(extended)
            return builder.toString()
        }
    }

    private fun rk(value: Int): Double {
        val number = if (value and 0x02 != 0) (value shr 2).toDouble()
            else java.lang.Double.longBitsToDouble((value.toLong() and 0xFFFFFFFCL) shl 32)
        return if (value and 0x01 != 0) number / 100 else number
    }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun i32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)
    private fun i64(b: ByteArray, at: Int) = (i32(b, at).toLong() and 0xFFFFFFFFL) or (i32(b, at + 4).toLong() shl 32)
}
