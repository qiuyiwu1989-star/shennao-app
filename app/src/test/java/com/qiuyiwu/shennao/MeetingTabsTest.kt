package com.qiuyiwu.shennao

import org.junit.Assert.*
import org.junit.Test

/*
 * 详情三 tab 的两条判据：标签上的数字、原话 tab 收哪些引用。
 * 原话 tab 是证据链的落地面，收错了（收进空白、重复、把猜想当原话）比缺一条更糟。
 */
class MeetingTabsTest {

    private fun meeting(
        atoms: List<MeetingAtom> = emptyList(),
        commitments: List<Commitment> = emptyList(),
    ) = Meeting(
        transcriptId = "t1", title = "方法评审", summary = null, durationSec = 2820,
        speakers = listOf("张伟", "李明"), atoms = atoms, commitments = commitments,
        analysis = null, analysisAbsentReason = null,
    )

    private fun atom(id: String, quote: String, epistemic: String = "attested", subject: String? = "张伟") =
        MeetingAtom(id, "三个人带着三个答案散场了", "contradiction", quote, epistemic, subject)

    private fun commitment(id: String, quote: String, who: String = "王建国") = Commitment(
        id = id, speakerName = who, statement = "下周三前给预算口径", quote = quote,
        saidDate = "9月1日", context = "战略会", dueDate = "2026-09-10",
        overdueDays = 1, status = "open", transcriptId = "t1",
    )

    @Test fun `标签上的数字是各 tab 的条数，没有就不带数字`() {
        val m = meeting(atoms = listOf(atom("a1", "先把底座做完")), commitments = emptyList())
        val t = MeetingTabs.of(m)
        assertEquals("判断 1", MeetingTab.JUDGMENTS.label(t))
        assertEquals("承诺", MeetingTab.COMMITMENTS.label(t))
        assertEquals("原话 1", MeetingTab.QUOTES.label(t))
    }

    @Test fun `原话 tab 只收有原话的，猜想没有原话就不占一行`() {
        val m = meeting(atoms = listOf(
            atom("a1", "先把底座做完"),
            atom("a2", "", epistemic = "conjecture"),
        ))
        val q = MeetingTabs.quotesOf(m)
        assertEquals(1, q.size)
        assertEquals("先把底座做完", q[0].text)
    }

    @Test fun `承诺的原话带说话人，判断的原话带主体`() {
        val m = meeting(
            atoms = listOf(atom("a1", "那就先把底座做完", subject = "张伟")),
            commitments = listOf(commitment("c1", "我下周三之前给你个准数")),
        )
        val q = MeetingTabs.quotesOf(m)
        assertEquals(2, q.size)
        assertEquals("王建国", q[0].who)
        assertTrue(q[0].supports.contains("承诺"))
        assertEquals("张伟", q[1].who)
        assertTrue(q[1].supports.startsWith("支撑："))
    }

    @Test fun `同一句被两条判断引用只列一次`() {
        val m = meeting(atoms = listOf(atom("a1", "同一句"), atom("a2", "同一句")))
        val q = MeetingTabs.quotesOf(m)
        assertEquals(1, q.size)
        assertTrue("要注明支撑不止一条", q[0].supports.contains("另一条"))
    }

    @Test fun `没有主体的判断标成会上，不留空白`() {
        val m = meeting(atoms = listOf(atom("a1", "某句", subject = null)))
        assertEquals("会上", MeetingTabs.quotesOf(m)[0].who)
    }
}
