package com.xingyihui.interviewassistant.diag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * F2 资料解析：TXT / DOCX / PDF / 图片。
 * 图片与 PDF 页面统一走「在线视觉识别」——DeepSeek 多模态（同一个 key，无需额外服务），
 * 与「调 API 本来就要联网」一致，不再把 OCR 模型打进 APK。
 */
object DocumentParser {
    private const val TAG = "InterviewApp"

    data class Parsed(val text: String, val pages: Int, val method: String)

    private val visionClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    fun parse(ctx: Context, uri: Uri, displayName: String, onProgress: ((String) -> Unit)? = null): Parsed {
        val lower = displayName.lowercase()
        return when {
            lower.endsWith(".txt") || lower.endsWith(".md") -> {
                val t = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                Parsed(t, 1, "txt")
            }
            lower.endsWith(".docx") -> {
                onProgress?.invoke("理解中…")
                Parsed(parseDocx(ctx, uri), 1, "docx")
            }
            lower.endsWith(".pdf") -> parsePdf(ctx, uri, onProgress)
            lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") -> {
                onProgress?.invoke("理解中…")
                val bmp = decodeScaled(ctx, uri, 1600) ?: return Parsed("", 0, "image")
                val text = visionOcr(bmp)
                bmp.recycle()
                Parsed(text, 1, "vision")
            }
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

    /** PDF：PdfRenderer 渲染每页（缩到 1600px 内）→ 在线视觉识别，最多 10 页 */
    private fun parsePdf(ctx: Context, uri: Uri, onProgress: ((String) -> Unit)? = null): Parsed {
        val pfd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: return Parsed("", 0, "pdf")
        val sb = StringBuilder()
        var pages = 0
        try {
            PdfRenderer(pfd).use { renderer ->
                val n = minOf(renderer.pageCount, 10)
                for (i in 0 until n) {
                    onProgress?.invoke("理解中… 第 ${i + 1}/${n} 页")
                    renderer.openPage(i).use { page ->
                        val scale = 2f
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val scaled = scaleDown(bmp, 1600)
                        sb.append(visionOcr(scaled)).append("\n")
                        if (scaled !== bmp) scaled.recycle()
                        bmp.recycle()
                        pages++
                    }
                }
            }
        } finally {
            try { pfd.close() } catch (_: Exception) {}
        }
        return Parsed(sb.toString(), pages, "vision-pdf")
    }

    private fun decodeScaled(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > maxSide * 2) sample *= 2
            val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts2) }
            bmp?.let { scaleDown(it, maxSide) }
        } catch (e: Exception) {
            Log.i(TAG, "图片解码失败: ${e.message}")
            null
        }
    }

    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val ratio = maxSide.toFloat() / longest
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun toJpegBase64(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * 在线视觉识别：DeepSeek 多模态（图片 + 指令），返回图中文字。
     * 失败返回空串并由调用方记录（不阻塞导入流程）。
     */
    fun visionOcr(bmp: Bitmap): String {
        val b64 = toJpegBase64(bmp)
        val payload = JSONObject().apply {
            put("model", "deepseek-chat")
            put("temperature", 0.0)
            put("max_tokens", 1500)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text",
                        "把这张图片里的文字逐行原样输出，保留数字与单位，不要解释、不要总结、不要补充推测内容。若没有文字则输出空。"))
                    put(JSONObject().put("type", "image_url").put("image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$b64")))
                }))
            })
        }.toString()
        val req = Request.Builder()
            .url(StrategyEngine.URL)
            .header("Authorization", "Bearer ${BuildConfig.DEEPSEEK_API_KEY}")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            visionClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.i(TAG, "视觉识别失败 HTTP ${resp.code}: ${raw.take(120)}")
                    return ""
                }
                JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty().trim()
            }
        } catch (e: Exception) {
            Log.i(TAG, "视觉识别异常: ${e.message}")
            ""
        }
    }
}
