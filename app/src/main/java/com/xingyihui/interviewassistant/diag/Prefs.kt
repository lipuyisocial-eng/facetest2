package com.xingyihui.interviewassistant.diag

import android.content.Context

/**
 * 本地设置（SharedPreferences）：面试官提问过滤阈值、话术播报开关、默认模式。
 * v1.5.0 起阈值不再写死，设置页滑杆可调并持久化。
 */
object Prefs {
    private const val NAME = "interview_prefs"

    const val KEY_THRESHOLD = "filter_threshold"
    const val KEY_SPEAK = "speak_strategy"
    const val KEY_MODE = "default_mode"
    const val KEY_VOICE = "tts_voice" // "practice" | "assist"

    const val MODE_PRACTICE = "practice"
    const val MODE_ASSIST = "assist"

    const val DEFAULT_THRESHOLD = 0.55f
    const val MIN_THRESHOLD = 0.30f
    const val MAX_THRESHOLD = 0.90f

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun threshold(ctx: Context): Float = sp(ctx).getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)

    fun setThreshold(ctx: Context, v: Float) {
        sp(ctx).edit().putFloat(KEY_THRESHOLD, v.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)).apply()
    }

    fun speakEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SPEAK, true)

    fun setSpeakEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_SPEAK, on).apply()
    }

    fun voice(ctx: Context): String = sp(ctx).getString(KEY_VOICE, "") ?: ""

    fun setVoice(ctx: Context, name: String) {
        sp(ctx).edit().putString(KEY_VOICE, name).apply()
    }

    fun mode(ctx: Context): String = sp(ctx).getString(KEY_MODE, MODE_PRACTICE) ?: MODE_PRACTICE

    fun setMode(ctx: Context, mode: String) {
        sp(ctx).edit().putString(KEY_MODE, mode).apply()
    }
}
