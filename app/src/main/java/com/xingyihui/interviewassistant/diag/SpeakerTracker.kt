package com.xingyihui.interviewassistant.diag

/**
 * 声学说话人追踪：逐帧音高（自相关 F0）→ 在线两簇聚类 → 按时间窗给句子投票。
 * 用途：讯飞 rl 在本场景恒为 0，只能自己在本地从 PCM 判断「这句是谁说的」。
 */
object SpeakerTracker {

    private const val MIN_F0 = 70f
    private const val MAX_F0 = 400f
    private const val MIN_RMS = 0.015f
    private const val RING_MS = 25_000L

    private val ring = ArrayDeque<LongArray>()   // [tMs, f0*10, rms*1000]
    private val voiced = ArrayList<Float>()      // 最近音高样本
    private var c1 = 0f
    private var c2 = 0f
    private var sep = 0f
    private var voicedCount = 0

    @Synchronized
    fun reset() {
        ring.clear()
        voiced.clear()
        c1 = 0f; c2 = 0f; sep = 0f; voicedCount = 0
    }

    /** 每 40ms 录音帧调用一次（录音线程） */
    @Synchronized
    fun feed(tMs: Long, pcm: ByteArray, n: Int) {
        val f0 = estimateF0(pcm, n) ?: return
        ring.addLast(longArrayOf(tMs, (f0 * 10).toLong(), 0))
        while (ring.isNotEmpty() && tMs - ring.first()[0] > RING_MS) ring.removeFirst()
        voiced.add(f0)
        voicedCount++
        if (voiced.size > 240) voiced.removeAt(0)
        if (voicedCount % 20 == 0) recluster()
    }

    /** 两簇 KMeans（音高），返回分离度 */
    @Synchronized
    fun recluster() {
        if (voiced.size < 30) return
        val sorted = voiced.sorted()
        var a = sorted[(sorted.size * 0.3).toInt()]
        var b = sorted[(sorted.size * 0.7).toInt()]
        repeat(8) {
            var sa = 0f; var na = 0; var sb = 0f; var nb = 0
            for (f in voiced) {
                if (kotlin.math.abs(f - a) <= kotlin.math.abs(f - b)) { sa += f; na++ } else { sb += f; nb++ }
            }
            if (na > 0) a = sa / na
            if (nb > 0) b = sb / nb
        }
        if (a > b) { val t = a; a = b; b = t }
        c1 = a; c2 = b; sep = b - a
    }

    val centerLow: Float get() = c1
    val centerHigh: Float get() = c2
    val separation: Float get() = sep
    val confident: Boolean get() = sep >= 30f && voiced.size >= 40

    /** 句子时间窗内多数投票：返回 1(低音簇) / 2(高音簇) / 0(无法判断) */
    @Synchronized
    fun label(windowStartMs: Long, windowEndMs: Long): Int {
        if (!confident) return 0
        var low = 0; var high = 0
        for (e in ring) {
            val t = e[0]
            if (t in windowStartMs..windowEndMs) {
                val f = e[1] / 10f
                if (f <= 0f) continue
                if (kotlin.math.abs(f - c1) <= kotlin.math.abs(f - c2)) low++ else high++
            }
        }
        val total = low + high
        if (total < 5) return 0
        return when {
            low >= total * 0.62 -> 1
            high >= total * 0.62 -> 2
            else -> 0
        }
    }

    /** 自相关估音高（先 4 倍降采样降计算量） */
    private fun estimateF0(pcm: ByteArray, n: Int): Float? {
        if (n < 640) return null
        val samples = FloatArray(n / 8)
        var sum = 0.0
        for (i in samples.indices) {
            val idx = i * 8
            val s = ((pcm[idx].toInt() and 0xFF) or (pcm[idx + 1].toInt() shl 8)).toShort() / 32768f
            samples[i] = s
            sum += s * s
        }
        val rms = kotlin.math.sqrt(sum / samples.size)
        if (rms < MIN_RMS) return null

        val sr = 16000f / 4f
        val lo = (sr / MAX_F0).toInt().coerceAtLeast(2)
        val hi = (sr / MIN_F0).toInt().coerceAtMost(samples.size - 2)
        if (hi <= lo) return null
        var bestLag = 0
        var best = 0f
        for (lag in lo..hi) {
            var acc = 0f
            var i = 0
            while (i + lag < samples.size) {
                acc += samples[i] * samples[i + lag]
                i++
            }
            if (acc > best) { best = acc; bestLag = lag }
        }
        if (bestLag == 0 || best <= 0f) return null
        val energy = samples.fold(0f) { acc, v -> acc + v * v }
        if (best < energy * 0.25f) return null
        return sr / bestLag
    }
}
