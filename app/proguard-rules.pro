# R8 规则。**minify 还没开**（build.gradle.kts 里 isMinifyEnabled=false）：
# 开之前要在真机上用真实账号回归登录 / 上传 / 蓝牙 / 网页版（spec 013 §5）。规则先备好。

# 崩溃栈要能对回源码
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# OkHttp / Okio 自带 consumer 规则；这两条是它们文档里额外要求的
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# security-crypto 底下是 Tink，靠反射找 key template
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# WorkManager 的 Worker 靠类名反射实例化
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# 我们自己的：Service / Receiver 由系统按类名起
-keep class com.qiuyiwu.shennao.record.RecordingService { *; }
-keep class com.qiuyiwu.shennao.ble.BleImportService { *; }
