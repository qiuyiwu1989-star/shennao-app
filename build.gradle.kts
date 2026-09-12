plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // 截图测试：在 JVM 上把 Compose 画面渲染成 PNG，不用真机也不用模拟器
    id("io.github.takahirom.roborazzi") version "1.26.0" apply false
}
