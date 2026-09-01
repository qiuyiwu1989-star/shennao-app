package com.qiuyiwu.shennao.ble

import org.junit.Assert.*
import org.junit.Test

/*
 * 导入器的判断：什么时候该重试、断了从哪续、什么算传完。
 * 这些全是真机上最贵、也最容易写错的部分，所以整块做成纯逻辑在这里验。
 */

private class FakeTransport(var connected: Boolean = true) : BleTransport {
    val sent = mutableListOf<ByteArray>()
    override val state get() = if (connected) BleState.READY else BleState.DISCONNECTED
    override fun write(frame: ByteArray): Boolean {
        if (!connected) return false
        sent += frame; return true
    }
    override fun onNotify(char: String, cb: (ByteArray) -> Unit) {}
    override fun disconnect() { connected = false }
    /** 最后一帧里带的 offset（下载请求的 params 前 4 字节，小端） */
    fun lastOffset(): Long {
        val f = sent.last()
        return (f[8].toLong() and 0xFF) or ((f[9].toLong() and 0xFF) shl 8) or
               ((f[10].toLong() and 0xFF) shl 16) or ((f[11].toLong() and 0xFF) shl 24)
    }
    fun lastName(): String =
        String(sent.last().copyOfRange(12, 36).takeWhile { it != 0.toByte() }.toByteArray())
}

private fun frame(cmd: Int, body: ByteArray = ByteArray(0)) =
    Proto.Frame(0, byteArrayOf(Proto.T.FILE.toByte(), cmd.toByte()) + body)

private fun be32(v: Long) = byteArrayOf(
    ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
    ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

private fun listBody(vararg names: String): ByteArray {
    var out = byteArrayOf(0, 0, 0, names.size.toByte())
    for (n in names) {
        val nb = n.toByteArray()
        out += be32(60) + be32(1000) + nb + ByteArray(20 - nb.size)
    }
    return out
}

class ImporterListTest {
    @Test fun `列文件要等 2-18 才算完，中途不能提前报完成`() {
        val t = FakeTransport(); val im = Importer(t)
        im.startListing()
        im.onFrame(frame(Proto.FileCmd.LIST_DATA, listBody("a.opus")))
        // 只收到一帧数据时还不能算完——设备会分多帧发
        assertTrue("提前报完成会漏掉后面的文件", im.state is ImportState.Listing)
        im.onFrame(frame(Proto.FileCmd.LIST_DATA, listBody("b.opus")))
        im.onFrame(frame(Proto.FileCmd.LIST_DONE))
        val files = (im.state as ImportState.Listed).files
        assertEquals(listOf("a.opus", "b.opus"), files.map { it.name })
    }

    @Test fun `没连上时发不出去要说清楚，而不是干等`() {
        val im = Importer(FakeTransport(connected = false))
        im.startListing()
        val s = im.state as ImportState.Failed
        assertFalse("这是连接问题，不是设备拒绝", s.deviceSaid)
    }
}

class ImporterDownloadTest {

    private fun entry(name: String) = FileEntry(60, 1000, name)

    @Test fun `完整走一遍：请求 到 开始 到 数据 到 结束`() {
        val t = FakeTransport(); val im = Importer(t)
        im.startDownload(entry("note20260828-205856.opus"))
        im.onFrame(frame(Proto.FileCmd.IMPORT_BEGIN, be32(6)))
        im.onFrame(frame(Proto.FileCmd.IMPORT_DATA, byteArrayOf(1, 2, 3)))
        val mid = im.state as ImportState.Downloading
        assertEquals(3L, mid.got)
        assertEquals(0.5f, mid.fraction!!, 0.01f)
        im.onFrame(frame(Proto.FileCmd.IMPORT_DATA, byteArrayOf(4, 5, 6)))
        im.onFrame(frame(Proto.FileCmd.IMPORT_END, byteArrayOf(0)))
        val done = im.state as ImportState.Done
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), done.bytes)
    }

    @Test fun `总长还不知道时进度是 null，不是 0——界面该显示不确定而不是「一点没传」`() {
        val im = Importer(FakeTransport())
        im.startDownload(entry("a.opus"))
        assertNull((im.state as ImportState.Downloading).fraction)
    }

    @Test fun `设备说文件不存在就换下一个候选名，并从头开始`() {
        // 列表里的名字是 20B 截断的，note20260828-205856.opus 会被截成 note20260828-205856.
        val t = FakeTransport(); val im = Importer(t)
        im.startDownload(entry("note20260828-205856."))
        assertEquals("opus 要排在最前——wav 走 BLE 慢一个数量级",
                     "note20260828-205856.opus", t.lastName())
        im.onFrame(frame(Proto.FileCmd.IMPORT_DATA, byteArrayOf(9)))   // 先来了点垃圾
        im.onFrame(frame(Proto.FileCmd.IMPORT_END, byteArrayOf(1)))    // 1 = 文件不存在
        assertEquals("note20260828-205856.wav", t.lastName())
        assertEquals("换了文件就必须从头，之前收的字节不是它的", 0L, t.lastOffset())
        assertEquals(0L, im.received)
    }

    @Test fun `所有候选名都不存在时才算失败，且要标成「设备拒绝」`() {
        val t = FakeTransport(); val im = Importer(t)
        im.startDownload(entry("x."))
        repeat(3) { im.onFrame(frame(Proto.FileCmd.IMPORT_END, byteArrayOf(1))) }
        val s = im.state as ImportState.Failed
        assertTrue("换名字才是对的做法，重连没用", s.deviceSaid)
    }

    @Test fun `断线续传要从已收字节数继续，绝不能从头`() {
        // 27 KB/s 的链路上重头意味着再等四分钟——这正是断点续传的全部意义
        val t = FakeTransport(); val im = Importer(t)
        im.startDownload(entry("a.opus"))
        im.onFrame(frame(Proto.FileCmd.IMPORT_BEGIN, be32(1000)))
        im.onFrame(frame(Proto.FileCmd.IMPORT_DATA, ByteArray(400)))
        im.resume()
        assertEquals(400L, t.lastOffset())
        assertEquals("续传不能清掉已收的数据", 400L, im.received)
    }

    @Test fun `续传之后收到的数据要接在后面，不是覆盖`() {
        val t = FakeTransport(); val im = Importer(t)
        im.startDownload(entry("a.opus"))
        im.onFrame(frame(Proto.FileCmd.IMPORT_DATA, byteArrayOf(1, 2)))
        im.resume()
        im.onFrame(frame(Proto.FileCmd.IMPORT_DATA, byteArrayOf(3, 4)))
        im.onFrame(frame(Proto.FileCmd.IMPORT_END, byteArrayOf(0)))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), (im.state as ImportState.Done).bytes)
    }
}

class ImporterTimeoutTest {
    /*
     * 设备可能一声不吭。不主动查的话界面会永远转圈——
     * 而「永远转圈」是用户最不会来报的一种故障，他只会觉得这功能没做好。
     */
    @Test fun `设备不吭声要超时，并且算成断线而不是拒绝`() {
        var clock = 0L
        val im = Importer(FakeTransport()) { clock }
        im.startDownload(FileEntry(60, 1000, "a.opus"))
        clock += Importer.DATA_TIMEOUT_MS + 1
        val s = im.tick() as ImportState.Failed
        assertFalse("超时可能只是走远了，重连是对的做法", s.deviceSaid)
    }

    @Test fun `一直有数据进来就不该超时`() {
        var clock = 0L
        val im = Importer(FakeTransport()) { clock }
        im.startDownload(FileEntry(60, 1000, "a.opus"))
        repeat(5) {
            clock += Importer.DATA_TIMEOUT_MS - 1000
            im.onFrame(frame(Proto.FileCmd.IMPORT_DATA, byteArrayOf(1)))
            assertTrue("还在收数据却判了超时", im.tick() is ImportState.Downloading)
        }
    }

    @Test fun `列表的超时比下载短——列表是设备立刻能答的`() {
        assertTrue(Importer.LIST_TIMEOUT_MS < Importer.DATA_TIMEOUT_MS)
    }
}
