package com.qiuyiwu.shennao

import org.json.JSONObject

/*
 * 夹具模式（只在调试包、只由 adb 起）：
 *   adb shell am start -n com.qiuyiwu.shennao/.MainActivity --ez demo true
 *
 * 为什么要有它：登录后的每一屏在模拟器上都要真实后端和账号才看得到——
 * 于是 UI 的细节一直没人真正看过。这里把每个端点用一份像样的假数据顶上，
 * 不联网、不登录，一屏一屏截图审。正式包里没有这条路（BuildConfig.DEBUG 守着）。
 * 数据是编的，人名场景都不是真的。
 */
object Demo {
    fun install() {
        val store = object : CredentialStore {
            private var c: Credentials? = Credentials("demo-refresh", "org-demo", "demo@shennao.app")
            override fun load() = c
            override fun save(c: Credentials) { this.c = c }
            override fun clear() { c = null }
        }
        Session.installForDemo(DeepBrainClient(DemoHttp(), store, "https://demo.invalid", "https://demo.invalid", "anon"))
    }

    private class DemoHttp : Http {
        override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
            val path = url.substringAfter("demo.invalid")
            return when {
                path.startsWith("/auth/v1/token") -> ok("""{"access_token":"demo-access","refresh_token":"demo-refresh"}""")
                path == "/api/mobile/today" -> ok(TODAY)
                path == "/api/mobile/sessions" -> ok(SESSIONS)
                path.startsWith("/api/mobile/transcript/") && path.endsWith("/speakers") -> ok(SPEAKERS)
                path.startsWith("/api/mobile/transcript/") -> ok(MEETING)
                path.startsWith("/api/mobile/people/") -> ok(PERSON)
                path.startsWith("/api/mobile/search") -> ok(SEARCH)
                path == "/api/mobile/credits" -> ok("""{"balance":126,"month":{"deep":3,"quick":11,"credits":19}}""")
                path == "/api/mobile/card" -> ok("""{"cards":[{"deviceNo":"CB08-AA:BB:CC:DD:EE:01","boundAt":"2026-08-30T10:00:00Z","granted":0,"monthly":30}],"monthly":30}""")
                path.startsWith("/downloads/latest.json") -> ok("""{"versionName":"3.5.1","versionCode":40,"size":10587187,"sha256":"x","url":"https://demo.invalid/x.apk"}""")
                method == "POST" || method == "PATCH" -> ok("""{"ok":true}""")
                else -> HttpResponse(404, """{"error":"demo 没有这个端点"}""")
            }
        }
        override fun requestBytes(method: String, url: String, headers: Map<String, String>, body: ByteArray) = ok("{}")
        private fun ok(b: String) = HttpResponse(200, b)
    }

    private val TODAY = """
    {"counts":{"overdue":2,"total":5,"awaitingSpeaker":3},
     "commitments":[
       {"id":"c1","speakerName":"陈总","statement":"下周五前给试点方案","quote":"试点方案我下周五之前给你们，这个我拍板。","saidDate":"8月28日","context":"Q3 复盘会","dueDate":"9月5日","overdueDays":1,"status":"open","transcriptId":"t1","personId":"p1"},
       {"id":"c2","speakerName":"李工","statement":"把接口文档补齐","quote":"接口文档我这两天补齐，周三发群里。","saidDate":"8月31日","context":"技术对齐","dueDate":"9月3日","overdueDays":3,"status":"open","transcriptId":"t2"},
       {"id":"c3","speakerName":"周敏","statement":"约客户二次沟通","quote":"客户那边我来约，下周。","saidDate":"9月2日","context":"销售周会","dueDate":"9月12日","overdueDays":null,"status":"open","transcriptId":"t1","personId":"p2"}],
     "insights":[
       {"id":"i1","statement":"陈总对试点的时间线比团队乐观两周","atomType":"signal","quote":"两周就够了，你们别把它想得太复杂。","epistemic":"attested","subject":"陈总","transcriptId":"t1"},
       {"id":"i2","statement":"预算口径前后不一致：会上说 30 万，纪要写 50 万","atomType":"contradiction","quote":"这次先按 30 万做。","epistemic":"attested","subject":null,"transcriptId":"t1"},
       {"id":"i3","statement":"李工可能在回避接口改动的工作量","atomType":"judgment","quote":"","epistemic":"conjecture","subject":"李工","transcriptId":"t2"}],
     "predictions":[
       {"id":"pr1","statement":"客户会在 9 月内签首批试点","observableSignal":"收到盖章的试点协议","subject":"周敏","dueAt":"9月30日","overdueDays":null}],
     "awaitingSpeakerTranscripts":[{"transcriptId":"t2","title":"技术对齐 · 8月31日","count":3}]}
    """.trimIndent()

    private val SESSIONS = """
    {"sessions":[
      {"sessionId":"s1","title":"Q3 复盘会","startedAt":"2026-09-05T06:00:00Z","durationMs":3120000,"stage":"analyzed","transcriptId":"t1","captureClient":"android","source":"card"},
      {"sessionId":"s2","title":"技术对齐","startedAt":"2026-09-04T02:30:00Z","durationMs":1500000,"stage":"transcribed","transcriptId":"t2","captureClient":"android","source":"phone"},
      {"sessionId":"s3","title":"飞书导出 · 客户访谈","startedAt":"2026-09-03T08:10:00Z","durationMs":2400000,"stage":"delivered","transcriptId":null,"captureClient":"android","source":"share"},
      {"sessionId":"s4","title":"手机录音","startedAt":"2026-09-01T01:00:00Z","durationMs":45000,"stage":"failed","problem":"不到 5 分钟，默认不分析。要分析可以在详情里点一下。","transcriptId":null,"captureClient":"android","source":"phone"}]}
    """.trimIndent()

    private val MEETING = """
    {"transcriptId":"t1","title":"Q3 复盘会","summary":"围绕试点方案的时间线与预算。陈总拍板下周五给方案，预算口径会上按 30 万；两周的时间线团队有保留。",
     "durationSec":3120,"speakers":["陈总","周敏","说话人3"],"people":[{"name":"陈总","personId":"p1"},{"name":"周敏","personId":"p2"}],
     "atoms":[
       {"id":"i1","statement":"陈总对试点的时间线比团队乐观两周","atomType":"signal","quote":"两周就够了，你们别把它想得太复杂。","epistemic":"attested","subject":"陈总"},
       {"id":"i2","statement":"预算口径前后不一致：会上说 30 万，纪要写 50 万","atomType":"contradiction","quote":"这次先按 30 万做。","epistemic":"attested","subject":null},
       {"id":"i4","statement":"周敏倾向先做小范围试点再谈扩量","atomType":"decision","quote":"先跑一个客户，跑通了再说扩。","epistemic":"inferred","subject":"周敏"}],
     "commitments":[{"id":"c1","speaker":"陈总","quote":"试点方案我下周五之前给你们，这个我拍板。","dueDate":"9月5日"},{"id":"c3","speaker":"周敏","quote":"客户那边我来约，下周。","dueDate":"9月12日"}],
     "analysis":{"markdown":"## 这场会定了什么\n\n- **试点方案**：陈总承诺下周五前给出\n- **预算**：会上按 30 万执行，纪要中的 50 万需核对\n\n## 没谈成的\n\n时间线。团队对「两周」有保留，但会上没有人明说。","methods":["决策复盘","承诺追踪"],"routingReason":"有明确的拍板与承诺","status":"done"},
     "analysisAbsentReason":null,
     "segments":[
       {"startMs":12000,"endMs":18000,"speaker":"陈总","text":"试点方案我下周五之前给你们，这个我拍板。"},
       {"startMs":19000,"endMs":26000,"speaker":"周敏","text":"先跑一个客户，跑通了再说扩。"},
       {"startMs":27000,"endMs":33000,"speaker":"陈总","text":"两周就够了，你们别把它想得太复杂。"},
       {"startMs":34000,"endMs":40000,"speaker":"说话人3","text":"预算这块，这次先按 30 万做。"}]}
    """.trimIndent()

    private val PERSON = """
    {"id":"p1","name":"陈总","role":"业务负责人","kept":6,"broken":2,"open":1,"keptRate":75,
     "judgments":[{"id":"i1","statement":"对试点的时间线比团队乐观两周","epistemic":"attested","transcriptId":"t1"},{"id":"i5","statement":"倾向在会上先定方向再补细节","epistemic":"inferred","transcriptId":"t1"}],
     "openCommitments":[{"id":"c1","quote":"试点方案我下周五之前给你们，这个我拍板。","dueDate":"9月5日","transcriptId":"t1"}]}
    """.trimIndent()

    private val SEARCH = """{"hits":[{"kind":"commitment","id":"c1","text":"试点方案我下周五之前给你们，这个我拍板。","who":"陈总","transcriptId":"t1"},{"kind":"judgment","id":"i2","text":"预算口径前后不一致：会上说 30 万，纪要写 50 万","who":null,"transcriptId":"t1"}]}"""

    private val SPEAKERS = """
    {"speakers":[{"id":"sp3","label":"说话人3","inferredIdentity":null,"confirmed":false,"sample":{"text":"预算这块，这次先按 30 万做。","startMs":34000}}],
     "candidates":[{"name":"王芳","role":"财务","source":"member"},{"name":"李工","role":"技术","source":"entity"}]}
    """.trimIndent()
}
