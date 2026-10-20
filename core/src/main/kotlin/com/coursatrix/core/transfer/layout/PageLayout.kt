package com.coursatrix.core.transfer.layout

/** Axis-aligned box with a top-left origin; y grows downward. Units are PDF points or image pixels. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    fun union(other: Box) = Box(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))
}

/** One extracted character (or a short run of characters) and where it is on the page. */
data class Glyph(val text: String, val box: Box)

/** A drawn table border segment. */
data class Rule(val box: Box) {
    val horizontal get() = box.width >= box.height
}

/** Everything the grid reconstruction needs from one PDF page, independent of how it was read. */
class PageLayout(val glyphs: List<Glyph>, val rules: List<Rule> = emptyList())

/** A visual line of text: glyphs on the same baseline that are close together. */
class TextLine(val glyphs: List<Glyph>) {
    val box: Box = glyphs.map { it.box }.reduce(Box::union)
    /** Text plus, for each character, the glyph it came from (null for inserted spaces). */
    private val joined: Pair<String, List<Glyph?>> = run {
        val text = StringBuilder()
        val owners = mutableListOf<Glyph?>()
        glyphs.forEachIndexed { index, glyph ->
            if (index > 0) {
                val previous = glyphs[index - 1]
                val gap = glyph.box.left - previous.box.right
                if (gap > 0.35f * maxOf(glyph.box.height, previous.box.height) && previous.text.last().isAsciiWord() &&
                    glyph.text.first().isAsciiWord()) { text.append(' '); owners += null }
            }
            glyph.text.forEach { text.append(it); owners += glyph }
        }
        text.toString() to owners
    }
    val text: String get() = joined.first

    /** Box covering the characters at [range] of [text]. */
    fun boxOf(range: IntRange): Box = range.mapNotNull { joined.second.getOrNull(it)?.box }.reduce(Box::union)

    companion object {
        /**
         * Groups glyphs into lines: same row when they overlap vertically by half the smaller height, split where the
         * horizontal gap exceeds [splitGap] character heights (that usually means a different table column).
         */
        fun group(glyphs: List<Glyph>, splitGap: Float = 1.5f): List<TextLine> {
            val rows = mutableListOf<MutableList<Glyph>>()
            for (glyph in glyphs.filter { it.text.isNotBlank() && it.box.height > 0 }.sortedBy { it.box.centerY }) {
                val row = rows.lastOrNull { row ->
                    val box = row.map { it.box }.reduce(Box::union)
                    minOf(box.bottom, glyph.box.bottom) - maxOf(box.top, glyph.box.top) >= 0.5f * minOf(row.first().box.height, glyph.box.height)
                }
                if (row != null) row += glyph else rows += mutableListOf(glyph)
            }
            return rows.flatMap { row ->
                val sorted = row.sortedBy { it.box.left }
                val lines = mutableListOf(mutableListOf(sorted.first()))
                for (glyph in sorted.drop(1)) {
                    val previous = lines.last().last()
                    val height = maxOf(previous.box.height, glyph.box.height)
                    if (glyph.box.left - previous.box.right > splitGap * height) lines += mutableListOf(glyph) else lines.last() += glyph
                }
                lines.map(::TextLine)
            }.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        }
    }
}

internal fun Char.isAsciiWord() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/**
 * Joins wrapped lines. Chinese text wraps anywhere (even inside "[W302]"), so lines are glued directly; only purely
 * Latin text gets a space between two words.
 */
internal fun joinWrapped(lines: List<String>): String {
    val parts = lines.map { it.trim() }.filter { it.isNotEmpty() }
    val latin = parts.none { line -> line.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } }
    return parts.fold("") { acc, line ->
        if (acc.isNotEmpty() && latin && acc.last().isLetter() && line.first().isLetter()) "$acc $line" else acc + line
    }
}
