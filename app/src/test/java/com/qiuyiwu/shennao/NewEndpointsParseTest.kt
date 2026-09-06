package com.qiuyiwu.shennao

import org.junit.Assert.*
import org.junit.Test

/*
 * 2026-09-05 接的新字段与新端点。服务端没部署时这些字段不存在——
 * 解析必须把「没有」当成 null / 空，而不是崩或编。
 */
class NewEndpointsParseTest {

    @Test fun `sessions 的 captureClient 有就带、没有就 null`() {
        val body = """{"sessions":[
          {"sessionId":"a","title":"x","stage":"analyzed","captureClient":"android"},
          {"sessionId":"b","title":"y","stage":"recorded"}]}"""
        val rows = SessionsParser.parse(body)
        assertEquals(listOf("android", null), rows.map { it.captureClient })
        val withSource = SessionsParser.parse("""{"sessions":[{"sessionId":"a","title":"x","stage":"analyzed","source":"card"}]}""")
        assertEquals("card", withSource[0].source)
        assertNull("老服务端没有 source", rows[0].source)
    }

    @Test fun `meeting 的 segments 逐句解析，空文本丢掉，没有字段就是空列表`() {
        val body = """{"transcriptId":"t1","title":"会","speakers":[],"atoms":[],"commitments":[],
          "segments":[{"startMs":30800,"endMs":31900,"speaker":"张伟","text":"先把底座做完"},
                      {"startMs":null,"endMs":null,"speaker":null,"text":""}]}"""
        val m = SessionsParser.parseMeeting(body)!!
        assertEquals(1, m.segments.size)
        assertEquals(Line(30800, 31900, "张伟", "先把底座做完"), m.segments[0])
        val old = SessionsParser.parseMeeting("""{"transcriptId":"t1","title":"会"}""")!!
        assertTrue("老服务端没有 segments，退回空列表", old.segments.isEmpty())
    }

    @Test fun `认人页：说话人配样本，候选带角色，坏行跳过`() {
        val body = """{"speakers":[
          {"id":"s1","label":"说话人2","inferredIdentity":null,"confirmed":false,"sample":{"text":"预算下周三给准数","startMs":31190}},
          {"id":"s2","label":"说话人3","inferredIdentity":"王建国","confirmed":true,"sample":null},
          {"label":"没id"}],
          "candidates":[{"name":"王建国","role":"CTO","source":"entity"},{"name":"","source":"member"}]}"""
        val p = SpeakersParser.parse(body)
        assertEquals(2, p.speakers.size)
        assertEquals("预算下周三给准数", p.speakers[0].sampleText)
        assertEquals(31190L, p.speakers[0].sampleStartMs)
        assertNull(p.speakers[1].sampleText)
        assertTrue(p.speakers[1].confirmed)
        assertEquals(listOf(Candidate("王建国", "CTO", "entity")), p.candidates)
    }

    @Test fun `解析坏 JSON 不崩，给空页`() {
        val p = SpeakersParser.parse("not json")
        assertTrue(p.speakers.isEmpty() && p.candidates.isEmpty())
    }

    @Test fun `灵魂卡列表与绑定回包`() {
        val p = CardsParser.parse("""{"cards":[{"deviceNo":"AABBCCDDEEFF","boundAt":"2026-09-05","granted":30,"monthly":30}],"monthly":30}""")
        assertEquals(1, p.cards.size); assertEquals(30, p.cards[0].granted); assertEquals(30, p.cards[0].monthly); assertEquals(30, p.monthly)
        assertEquals(0, CardsParser.parse("{}").cards.size)
        assertEquals(0, CardsParser.card(org.json.JSONObject("""{"deviceNo":"X"}""")).granted)
    }

    @Test fun `积分回包：余额 + 本月用量；老服务端没有 month 就 null`() {
        val c = CreditsParser.parse("""{"balance":42,"month":{"deep":3,"quick":12,"credits":19,"since":"2026-09-01T00:00:00.000Z"}}""")
        assertEquals(42, c.balance); assertEquals(3, c.month!!.deep)
        assertEquals("这个月：深判断 3 次 · 快判断 12 次 · 用了 19 积分", CreditsParser.usageLine(c.month))
        assertNull(CreditsParser.parse("""{"balance":7}""").month)
        assertNull("没有 month 就不画这一行", CreditsParser.usageLine(null))
        assertEquals("这个月还没做过判断", CreditsParser.usageLine(MonthUsage(0, 0, 0)))
    }

    @Test fun `今天：认人列表与承诺的人物 id；老服务端没有就空`() {
        val t = TodayParser.parse("""{"counts":{"overdue":0,"total":1,"awaitingSpeaker":3},
            "commitments":[{"id":"c1","speakerName":"陈总","statement":"s","quote":"q","saidDate":"","personId":"p1"}],
            "awaitingSpeakerTranscripts":[{"transcriptId":"t1","title":"周会","count":2}]}""")
        assertEquals("p1", t.commitments[0].personId)
        assertEquals(listOf("t1"), t.awaitingSpeakerTranscripts.map { it.transcriptId })
        assertEquals(2, t.awaitingSpeakerTranscripts[0].count)
        val old = TodayParser.parse("""{"counts":{},"commitments":[{"id":"c1","speakerName":"陈总"}]}""")
        assertNull(old.commitments[0].personId); assertTrue(old.awaitingSpeakerTranscripts.isEmpty())
    }
    @Test fun `会议：people 名字对 id`() {
        val m = SessionsParser.parseMeeting("""{"transcriptId":"t","speakers":["陈总","说话人2"],"people":[{"name":"陈总","personId":"p1"}]}""")!!
        assertEquals("p1", m.people["陈总"]); assertNull(m.people["说话人2"])
    }
}
