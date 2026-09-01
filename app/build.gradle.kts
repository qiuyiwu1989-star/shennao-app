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
        // 29（Android 10）。提上来是为 Opus 编码器铺路——它是 API 29 才有的，
        // 而真实语音实测 Opus 能省 37%（24k）到 59%（16k）的流量与存储，
        // 那是录音应用天天在付的成本。代价是放弃 Android 8/9。
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 34
        versionCode = 27
        versionName = "2.0.0"
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
    // Robolectric 要读 merged manifest 和资源
    testOptions { unitTests { isIncludeAndroidResources = true } }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        /*
         * minSdk 提到 29 之后，AGP 默认**不再压缩 dex**——Android 10+ 能直接
         * mmap 未压缩的 dex，启动更快、内存更省。代价是安装包本身从 10.4 MB
         * 变成 26 MB（内容一个字节没变，两边 dex 完全同样大小）。
         *
         * 但这个包是从网页下载的，**下载体积比冷启动那几十毫秒要紧得多**。
         * 走商店分发时是另一回事（商店会自己重打包），那时再把这行去掉。
         */
        dex.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    // enableEdgeToEdge 在 activity-ktx 里（activity-compose 只带 setContent）
    implementation("androidx.activity:activity-ktx:1.9.1")
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
    // 实时字幕要 WebSocket。安卓没有内置的 WS 客户端（java.net.http 不在 SDK 里），
    // 手写 RFC 6455 的握手与掩码帧不是不能做，但今天已经在协议细节上栽过两次，
    // 这里用成熟实现更划算。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    /*
     * Instrumented test：跑在 App 自己的进程里。
     *
     * 这是唯一能真正碰到 AudioRecord 和 MediaCodec 的地方——JVM 单测碰不到，
     * 而从 adb shell 启动前台服务是被系统拒绝的（服务 exported=false，
     * 那是对的；之前能从 shell 启起来是侥幸，不是设计）。
     */
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    // GrantPermissionRule：让测试自己拿权限。
    // 依赖「跑之前先 adb grant」的测试，迟早会在没授权的环境里跑出错误结论。
    androidTestImplementation("androidx.test:rules:1.5.0")
    /*
     * 界面测试跑在 JVM 上（Robolectric），不需要模拟器。
     *
     * 为什么这条比模拟器优先：模拟器一次要几十秒起，没人会在每次改动后都跑；
     * 而 JVM 上的界面测试和现有 99 个单测一起跑完只要几秒——**能被每次运行的
     * 测试才是真的测试**。
     *
     * 今天用户报的四个界面缺陷里有三个（刷新压在标题上、上传错误串到录音页、
     * 摘要没渲染 Markdown）都是这一层能抓住的。它们是我发出去的，
     * 由用户发现——那不该是用户的活。
     */
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.6.8")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.6.8")
    // 安卓自带 org.json，但 JVM 单测里那是个**空壳桩**——每个方法都抛
    // "Stub!" 异常。不补这一条，解析测试会全部挂掉，而且报错完全看不出原因。
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
