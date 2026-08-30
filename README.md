# 深脑 · 安卓端

深脑的第二个采集面 + 「看」的入口。第一期只做一条闭环：到期的承诺。

## 为什么先做安卓不做 iOS

分发。iOS 不上架就只能 TestFlight（100 人、会过期），安卓可以直接挂 APK 让人下载。
签名公证那道坎在 iOS 上绕不过，在安卓上根本不存在。

代价是 Mac 端那 1000 多行 Swift 一行都搬不过来——但设计能搬：
契约、踩过的坑、判据，都在 `clients/mac-recorder` 里写着。

## 为什么先做「看」不做「录」

「看」是几天，「录」是几周（安卓各家 ROM 各自杀后台，是个泥潭）。
先证明这个 App 在一天里有位置，再花那几周。

而「看」要成立有个前提：必须有通知。否则它就是个更差的浏览器。

## 结构

- `Api.kt` —— 契约层。纯 Kotlin、零安卓依赖，能在 JVM 上单测。
- `Client.kt` —— 网络与鉴权判断。`Http` 是接口，测试里替换成假的，
  所以「401 该怎么办」「半截 JSON 会不会崩」全部脱离网络验证。
- `MainActivity.kt` —— 界面。目前是骨架。

这个拆法是刻意的：Mac 端今天反复吃的亏是「编译过 ≠ 能跑」，
逻辑一埋进 Activity 就只能装到设备上才知道对不对。

## 本机工具链

不用 Android Studio，命令行即可：

```
JAVA_HOME=~/Developer/android-toolchain/jdk/Contents/Home
ANDROID_HOME=~/Library/Android/sdk
./gradlew :app:testDebugUnitTest   # 单测
./gradlew :app:assembleDebug       # 出 APK
```

## 两条踩过的坑

- **Kotlin 块注释可以嵌套。** 注释里写「斜杠 + 星号」（比如一个带通配符的路径）
  会开一层嵌套注释，报错说文件末尾未闭合，跟真正的位置差几十行。
- **JVM 单测里的 org.json 是空壳桩**，每个方法都抛 Stub!。
  要显式 `testImplementation("org.json:json")`，否则解析测试全挂且看不出原因。
