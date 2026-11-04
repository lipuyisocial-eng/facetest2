package com.xingyihui.interviewassistant.diag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import java.util.zip.ZipInputStream

/**
 * F2 资料解析：TXT / DOCX / PDF（文字版直接抽文本，扫描版走 OCR）/ 图片（OCR）。
 * 解析结果写入 filesDir/kb/<id>.txt，索引存 kb_index.json。
 */
object DocumentParser {
    private const val TAG = "InterviewApp"

    data class Parsed(val text: String, val pages: Int, val method: String)

    fun parse(ctx: Context, uri: Uri, displayName: String): Parsed {
        val lower = displayName.lowercase()
        return when {
            lower.endsWith(".txt") || lower.endsWith(".md") -> {
                val t = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                Parsed(t, 1, "txt")
            }
            lower.endsWith(".docx") -> Parsed(parseDocx(ctx, uri), 1, "docx")
            lower.endsWith(".pdf") -> parsePdf(ctx, uri)
            lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") ->
                Parsed(ocrUri(ctx, uri), 1, "ocr")
            else -> Parsed("", 0, "unsupported")
        }
    }

    /** DOCX = zip + word/document.xml，取 <w:t> 文本，无需第三方库 */
    private fun parseDocx(ctx: Context, uri: Uri): String {
        val sb = StringBuilder()
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (e.name == "word/document.xml") {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        sb.append(extractWt(xml))
                        break
                    }
                    e = zip.nextEntry
                }
            }
        }
        return sb.toString()
    }

    private fun extractWt(xml: String): String {
        val sb = StringBuilder()
        var i = 0
        while (true) {
            val s = xml.indexOf("<w:t", i)
            if (s < 0) break
            val gt = xml.indexOf('>', s)
            if (gt < 0) break
            val end = xml.indexOf("</w:t>", gt)
            if (end < 0) break
            sb.append(xml.substring(gt + 1, end))
            i = end + 6
        }
        return sb.toString().replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    }

    /** PDF：先用 PdfRenderer 渲染每页再 OCR（同时覆盖文字版与扫描版），最多 10 页 */
    private fun parsePdf(ctx: Context, uri: Uri): Parsed {
        val pfd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return Parsed("", 0, "pdf")
        val sb = StringBuilder()
        var pages = 0
        try {
            PdfRenderer(pfd).use { renderer ->
                val n = minOf(renderer.pageCount, 10)
                for (i in 0 until n) {
                    renderer.openPage(i).use { page ->
                        val scale = 2
                        val bmp = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        sb.append(ocrBitmap(bmp)).append("\n")
                        bmp.recycle()
                        pages++
                    }
                }
            }
        } finally {
            try { pfd.close() } catch (_: Exception) {}
        }
        return Parsed(sb.toString(), pages, "pdf-ocr")
    }

    private fun ocrUri(ctx: Context, uri: Uri): String {
        val img = InputImage.fromFilePath(ctx, uri)
        return ocrInput(img)
    }

    private fun ocrBitmap(bmp: Bitmap): String = ocrInput(InputImage.fromBitmap(bmp, 0))

    fun ocrBitmapForTest(bmp: Bitmap): String = ocrBitmap(bmp)

    /** ML Kit 中文识别（用 CountDownLatch 同步，避免额外引入 Tasks 依赖） */
    private fun ocrInput(image: InputImage): String {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val latch = java.util.concurrent.CountDownLatch(1)
        var out = ""
        try {
            recognizer.process(image)
                .addOnSuccessListener { text -> out = text.text; latch.countDown() }
                .addOnFailureListener { e ->
                    Log.i(TAG, "OCR 失败: ${e.javaClass.simpleName} ${e.message}")
                    latch.countDown()
                }
            latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.i(TAG, "OCR 异常: ${e.message}")
        } finally {
            try { recognizer.close() } catch (_: Exception) {}
        }
        return out
    }
}
