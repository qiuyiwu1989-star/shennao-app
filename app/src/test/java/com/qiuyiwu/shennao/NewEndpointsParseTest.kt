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
        val p = CardsParser.parse("""{"cards":[{"deviceNo":"AABBCCDDEEFF","boundAt":"2026-09-05","granted":300}],"grant":300}""")
        assertEquals(1, p.cards.size); assertEquals(300, p.cards[0].granted); assertEquals(300, p.grant)
        assertEquals(0, CardsParser.parse("{}").cards.size)
        assertEquals(0, CardsParser.card(org.json.JSONObject("""{"deviceNo":"X"}""")).granted)
    }
}
