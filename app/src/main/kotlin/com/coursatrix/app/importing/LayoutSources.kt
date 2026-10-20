package com.coursatrix.app.importing

import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import com.coursatrix.core.transfer.layout.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.IOException

object LayoutFiles {
    fun isPdf(bytes: ByteArray) = bytes.size >= 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-"
}

/** Text and table lines of the first PDF page via pdfbox-android. Scanned PDFs (images only) are rejected. */
object PdfSource {
    fun read(context: Context, bytes: ByteArray): PageLayout {
        PDFBoxResourceLoader.init(context.applicationContext)
        val document = try {
            PDDocument.load(bytes)
        } catch (e: InvalidPasswordException) {
            throw IllegalArgumentException("PDF 设置了打开密码，请先取消密码后再导入", e)
        } catch (e: IOException) {
            throw IllegalArgumentException("PDF 文件损坏或格式不受支持", e)
        }
        return document.use { doc ->
            require(doc.numberOfPages > 0) { "PDF 没有页面" }
            val page = doc.getPage(0)
            val glyphs = mutableListOf<Glyph>()
            object : PDFTextStripper() {
                override fun writeString(text: String, positions: MutableList<TextPosition>) {
                    positions.forEach { p ->
                        val unicode = p.unicode ?: return@forEach
                        if (unicode.isBlank()) return@forEach
                        // Some fonts report a tiny glyph height; fall back to the font size.
                        val height = maxOf(p.heightDir, p.fontSizeInPt * 0.7f)
                        glyphs += Glyph(unicode, Box(p.xDirAdj, p.yDirAdj - height, p.xDirAdj + p.widthDirAdj, p.yDirAdj))
                    }
                }
            }.apply { startPage = 1; endPage = 1; sortByPosition = true }.getText(doc)
            require(glyphs.size >= 10) { "这是扫描版（图片）PDF，没有可读取的文字，暂不支持。请使用教务系统直接导出的 PDF 或 Excel" }
            val collector = PdfRuleCollector(page.cropBox.lowerLeftX, page.cropBox.upperRightY)
            object : PDFGraphicsStreamEngine(page) {
                private var current = PointF()
                override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) =
                    collector.rectangle(floatArrayOf(p0.x, p1.x, p2.x, p3.x), floatArrayOf(p0.y, p1.y, p2.y, p3.y))
                override fun drawImage(pdImage: PDImage) {}
                override fun clip(windingRule: Path.FillType) {}
                override fun moveTo(x: Float, y: Float) { collector.moveTo(x, y); current = PointF(x, y) }
                override fun lineTo(x: Float, y: Float) { collector.lineTo(x, y); current = PointF(x, y) }
                override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                    collector.moveTo(x3, y3); current = PointF(x3, y3)
                }
                override fun getCurrentPoint(): PointF = current
                override fun closePath() {}
                override fun endPath() = collector.discard()
                override fun strokePath() = collector.stroke()
                override fun fillPath(windingRule: Path.FillType) = collector.fill()
                override fun fillAndStrokePath(windingRule: Path.FillType) = collector.stroke()
                override fun shadingFill(shadingName: COSName) {}
            }.processPage(page)
            PageLayout(glyphs, collector.rules)
        }
    }
}
