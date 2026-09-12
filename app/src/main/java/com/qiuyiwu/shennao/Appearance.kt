package com.qiuyiwu.shennao

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * 外观：跟随系统 / 浅色 / 深色。邱 2026-09-12：「一定要有明 UI 和暗 UI 两种，现在全都是暗 UI 了」——
 * 其实是手机开着深色模式，App 一直在跟随系统。给个开关，想看哪套就哪套。
 */
object Appearance {
    enum class Mode(val key: String, val label: String) { SYSTEM("system", "跟随系统"), LIGHT("light", "浅色"), DARK("dark", "深色") }
    private const val PREFS = "shennao-ui"
    val mode = mutableStateOf(Mode.SYSTEM)
    fun load(ctx: Context) {
        val k = ctx.getSharedPreferences(PREFS, 0).getString("appearance", null)
        mode.value = Mode.entries.firstOrNull { it.key == k } ?: Mode.SYSTEM
    }
    fun save(ctx: Context, m: Mode) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString("appearance", m.key).apply()
        mode.value = m
    }
}
