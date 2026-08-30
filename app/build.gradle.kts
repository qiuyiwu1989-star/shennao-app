plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qiuyiwu.shennao"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qiuyiwu.shennao"
        minSdk = 26          // 8.0：低于这个连 Java 8 时间 API 都要脱糖，不值得
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
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

    testImplementation("junit:junit:4.13.2")
    // 安卓自带 org.json，但 JVM 单测里那是个**空壳桩**——每个方法都抛
    // "Stub!" 异常。不补这一条，解析测试会全部挂掉，而且报错完全看不出原因。
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
