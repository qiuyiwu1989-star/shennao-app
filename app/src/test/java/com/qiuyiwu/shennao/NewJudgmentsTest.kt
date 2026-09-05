package com.qiuyiwu.shennao

import org.junit.Assert.*
import org.junit.Test

/*
 * 「有新判断」的三条判据。推错了是打扰，打扰两次人就把通知关了——
 * 而通知是这个 App 比网页多出来的唯一一件事。
 */
class NewJudgmentsTest {
    private fun card(id: String, stage: Stage) = SessionCard(
        sessionId = "s$id", title = "会$id", startedAt = null, durationMs = null,
        stage = stage, problem = null, transcriptId = "t$id",
    )
    private fun meeting(vararg atoms: MeetingAtom) = Meeting(
        transcriptId = "t1", title = "方法评审", summary = null, durationSec = null,
        speakers = emptyList(), atoms = atoms.toList(), commitments = emptyList(),
        analysis = null, analysisAbsentReason = null,
    )
    private fun atom(s: String, e: String) = MeetingAtom("a", s, "signal", "", e, null)

    @Test fun `第一次跑只记基线，不推历史`() {
        val cards = listOf(card("1", Stage.ANALYZED), card("2", Stage.ANALYZED), card("3", Stage.TRANSCRIBED))
        assertTrue("装上 App 那一刻不该收到几十条", NewJudgments.newlyAnalyzed(null, cards).isEmpty())
        assertEquals(setOf("t1", "t2"), NewJudgments.baselineOf(cards))
    }

    @Test fun `只推这一轮刚分析完的`() {
        val known = setOf("t1")
        val cards = listOf(card("1", Stage.ANALYZED), card("2", Stage.ANALYZED), card("3", Stage.DELIVERED))
        assertEquals(listOf("t2"), NewJudgments.newlyAnalyzed(known, cards).map { it.transcriptId })
    }

    @Test fun `正文是最狠的那一条，亲证优先，没判断就不推`() {
        assertEquals("三个人带着三个答案散场了",
            NewJudgments.headline(meeting(atom("猜的", "conjecture"), atom("三个人带着三个答案散场了", "attested"))))
        assertEquals("推断的也行", NewJudgments.headline(meeting(atom("推断的也行", "inferred"))))
        assertNull("只有猜想不推——点开发现没原话，下次就不点了", NewJudgments.headline(meeting(atom("猜的", "conjecture"))))
        assertNull("一条判断都没有就不推", NewJudgments.headline(meeting()))
    }

    @Test fun `标题带场次名和条数`() {
        assertEquals("方法评审 · 出了 2 条判断", NewJudgments.title(meeting(atom("a", "attested"), atom("b", "attested"))))
    }
}
