package com.qiuyiwu.shennao

import android.app.Application

/**
 * 崩溃捕获要在**任何东西初始化之前**装上——如果晚了，App 早期
 * （Session/DeepBrainClient 构造那一段）出的崩溃就会漏网，
 * 而那恰恰是最该被看见的一类：一装上就崩，用户第一印象就是「坏的」。
 * `Application.onCreate` 是能拿到 Context 的最早一站。
 */
class ShennaoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
    }
}
