import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 配置从 local.properties 读，不进仓。
// 写死在源码里的后果不只是「不好改」——anon key 会跟着仓库到处走，
// 而且换环境要改代码、发版才生效。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun cfg(key: String, fallback: String = "") = localProps.getProperty(key) ?: fallback

android {
    namespace = "com.qiuyiwu.shennao"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qiuyiwu.shennao"
        minSdk = 26          // 8.0：低于这个连 Java 8 时间 API 都要脱糖，不值得
        targetSdk = 34
        versionCode = 5
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_BASE", "\"${cfg("deepbrain.apiBase")}\"")
        buildConfigField("String", "SUPABASE_URL", "\"${cfg("deepbrain.supabaseUrl")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${cfg("deepbrain.anonKey")}\"")
    }

    // 外发的 APK 必须用**稳定**的签名：安卓认签名不认版本号，
    // 换了签名就装不上去，用户只能先卸载——那会连同本地凭证一起丢掉。
    // 口令放 local.properties，不进仓。
    signingConfigs {
        create("release") {
            val ks = cfg("shennao.ksPath")
            if (ks.isNotBlank() && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = cfg("shennao.ksPass")
                keyAlias = "shennao"
                keyPassword = cfg("shennao.ksPass")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 没配密钥时不要静默退回 debug 签名——那种 APK 发出去，
            // 下次换了正式签名所有人都得卸载重装。宁可构建失败。
            signingConfig = if (cfg("shennao.ksPath").isNotBlank())
                signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // XML 主题（AndroidManifest 里 android:theme 用）只能由这个库提供。
    // Compose 的 material3 是运行时组件，不带 XML 资源——少了它 aapt 直接链接失败。
    implementation("com.google.android.material:material:1.12.0")
    // 上传要在 App 被划掉之后继续。前台服务活不过「用户从最近任务里划走」，
    // 而没传完的录音只躺在手机上，用户以为已经进深脑了。
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // refresh token 现在是明文躺在 SharedPreferences 里。它能换 access token，
    // 等于长期钥匙——落盘必须加密。
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    // 安卓自带 org.json，但 JVM 单测里那是个**空壳桩**——每个方法都抛
    // "Stub!" 异常。不补这一条，解析测试会全部挂掉，而且报错完全看不出原因。
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
