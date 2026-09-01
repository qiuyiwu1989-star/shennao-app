package com.qiuyiwu.shennao.ble

import com.qiuyiwu.shennao.record.Segment
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

class GattQueueTest {
    /*
     * Android 的 BluetoothGatt 一次只受理一个操作。第二个在前一个回调到达前发出去，
     * 会被**静默丢弃**——代码看起来一切正常，设备那边什么都没收到。
     */

    @Test fun `第二个操作要等第一个的回调，不能挤在一起发`() {
        val ran = mutableListOf<String>()
        val q = GattQueue { ran += it.label; true }
        q.enqueue("写CCCD-AE22", true) {true}
        q.enqueue("写CCCD-AE23", true) {true}
        q.enqueue("协商MTU", true) {true}
        assertEquals("只该发出第一个", listOf("写CCCD-AE22"), ran)
        q.onComplete()
        assertEquals(listOf("写CCCD-AE22", "写CCCD-AE23"), ran)
        q.onComplete()
        assertEquals(3, ran.size)
    }

    @Test fun `失败也要调 onComplete，否则队列永远卡住`() {
        // 只在成功时推进的话，一次失败之后「设备就不响应了」
        val ran = mutableListOf<String>()
        val q = GattQueue { ran += it.label; true }
        q.enqueue("A", true) {true}; q.enqueue("B", true) {true}
        q.onComplete()   // 假设 A 失败了，回调仍然到来
        assertEquals(listOf("A", "B"), ran)
    }

    @Test fun `发都没发出去时要立刻跳到下一个，不能等一个不会来的回调`() {
        val ran = mutableListOf<String>()
        // 第一个操作连发都发不出去（GATT 忙 / 未连接）
        val q = GattQueue { ran += it.label; it.label != "发不出去的" }
        q.enqueue("发不出去的", true) {true}
        q.enqueue("后面这个", true) {true}
        assertEquals("卡在发不出去的那个上，后面全都饿死", listOf("发不出去的", "后面这个"), ran)
    }

    @Test fun `不产生回调的合成操作绝不能卡住队列`() {
        /*
         * 2026-09-01 真实事故：连接建立的最后一步「就绪」走了队列，
         * 而它是个合成操作、不会有 GATT 回调。队列永久卡在它上面，
         * 之后每一次写入都排在后面发不出去——**表现是「连上了，
         * 但设备什么都不回」**，8 秒后超时。
         */
        val ran = mutableListOf<String>()
        val q = GattQueue { ran += it.label; true }
        q.enqueue("就绪", awaitsCallback = false) { true }
        q.enqueue("写下载请求", awaitsCallback = true) { true }
        assertEquals("合成操作后面的写入必须照常发出去", listOf("就绪", "写下载请求"), ran)
    }

    @Test fun `卡住时要能说清卡在哪一步`() {
        val q = GattQueue { true }
        q.enqueue("协商MTU", true) {true}
        assertEquals("协商MTU", q.current)
        q.enqueue("写CCCD", true) {true}
        assertEquals(1, q.waiting)
    }
}

class IngestTest {
    /*
     * 导进来的文件走和手机录音同一条上传链路。另开一条的话，
     * 断点续传、分片幂等、时间轴严丝合缝这些坑要再踩一遍，
     * 而且两条路会慢慢长歪——某天只有一条修好了另一条没有。
     */

    @Test fun `从设备文件名解出真实录音时刻`() {
        val zone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        val t = Ingest.startedAtFrom("note20260828-205856.opus", zone)!!
        val c = java.util.Calendar.getInstance(zone).apply { timeInMillis = t }
        assertEquals(2026, c.get(java.util.Calendar.YEAR))
        assertEquals(8, c.get(java.util.Calendar.MONTH) + 1)
        assertEquals(28, c.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(20, c.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(56, c.get(java.util.Calendar.SECOND))
    }

    @Test fun `解不出来要返回 null，绝不拿「现在」顶上`() {
        // 拿现在顶的话，一场三天前的会会排在今天——
        // 而深脑那边的整条时间轴都建立在这个时刻上
        assertNull(Ingest.startedAtFrom("随便一个名字.opus"))
        assertNull(Ingest.startedAtFrom(""))
    }

    @Test fun `不合法的日期要解失败，不能被「宽容」成别的日期`() {
        // 13 月 40 号：不设 isLenient=false 的话 Java 会把它算成下一年的某天
        assertNull(Ingest.startedAtFrom("note20261340-205856.opus"))
        assertNull(Ingest.startedAtFrom("note20260828-256099.opus"))
    }

    @Test fun `导进来的分段报的是 ogg，不是 aac`() {
        // 设备已经压好 opus，不重新编码——转 AAC 只会让音质白掉一层。
        // mimeType 报错的话服务端会按别的格式解，解出垃圾而且不报错。
        val seg = Segment(0, 0, 60_000, Segment.State.SEALED, ext = "opus")
        assertEquals("audio/ogg", seg.mimeType)
        assertEquals("seg-000000-000000000-000060000.opus", seg.fileName())
        assertEquals(seg, Segment.parse(seg.fileName()))
    }
}

class BlePermissionsTest {
    /*
     * 安卓 12 把蓝牙权限拆成了两套。少了旧系统那条定位权限的后果特别坏：
     * **扫描不报错，只是永远返回空列表**，看起来就像「附近没有录音笔」。
     */
    @Test fun `安卓 12 及以上要的是「附近的设备」`() {
        val p = BlePermissions.required(31)
        assertTrue(p.any { it.endsWith("BLUETOOTH_SCAN") })
        assertTrue(p.any { it.endsWith("BLUETOOTH_CONNECT") })
        assertFalse("新系统不该再要定位", p.any { it.contains("LOCATION") })
    }

    @Test fun `安卓 11 及以下必须要定位，否则扫描静默返回空`() {
        assertTrue(BlePermissions.required(30).any { it.contains("ACCESS_FINE_LOCATION") })
    }

    @Test fun `被拒的提示要说对是哪个权限——旧系统上说「蓝牙权限」是错的`() {
        assertTrue(BlePermissions.deniedHint(30).contains("定位"))
        assertTrue(BlePermissions.deniedHint(33).contains("附近的设备"))
    }
}

class ReadinessTest {
    /*
     * 2026-09-01：用户在手机蓝牙关着时扫描，得到空列表，而界面提示是
     * 「确认录音笔开着」——把他指向了完全错误的方向，为此折腾了很久。
     * 而 adapter.isEnabled 是随手就能查的。
     *
     * 判据：凡是程序自己知道答案的事，绝不让用户去猜。
     */
    @Test fun `每一种不就绪都要说清是什么问题`() {
        assertNotNull(Readiness.BLUETOOTH_OFF.message)
        assertNotNull(Readiness.NO_BLE.message)
        // 就绪时不该有多余的话
        assertNull(Readiness.READY.message)
    }

    @Test fun `蓝牙没开的提示要说怎么打开，不能只说「没开」`() {
        val m = Readiness.BLUETOOTH_OFF.message!!
        assertTrue("只说问题不给出路等于没说：$m", m.contains("打开"))
    }

    @Test fun `能自己解决的才给按钮——硬件不支持给按钮是骗人`() {
        assertTrue(Readiness.BLUETOOTH_OFF.fixable)
        assertTrue(Readiness.NO_PERMISSION.fixable)
        assertFalse("这台手机没有 BLE，给个按钮点了也没用", Readiness.NO_BLE.fixable)
    }
}

class IngestIdempotencyTest {
    /*
     * 深脑用 clientRequestId 做建会话的幂等键：同一个键重推只拿回同一个会话。
     * 我原来用随机 UUID——同一个文件导两次就是两条，Mac 导过再用手机导也是两条，
     * 而两条内容完全一样，用户还得自己去删。
     */
    private class Probe : com.qiuyiwu.shennao.record.Vault {
        val metas = mutableMapOf<String, com.qiuyiwu.shennao.record.SessionMeta>()
        override fun sessions() = metas.keys.toList()
        override fun readMeta(s: String) = metas[s]
        override fun writeMeta(s: String, m: com.qiuyiwu.shennao.record.SessionMeta) { metas[s] = m }
        override fun updateMeta(s: String, f: (com.qiuyiwu.shennao.record.SessionMeta) -> com.qiuyiwu.shennao.record.SessionMeta) =
            metas[s]?.let { f(it).also { n -> metas[s] = n } }
        override fun segments(s: String) = emptyList<Segment>()
        override fun readSegment(s: String, seg: Segment): ByteArray? = null
        override fun rename(s: String, a: Segment, b: Segment) = true
        override fun deleteSession(s: String) { metas.remove(s) }
    }

    @Test fun `同一个文件导两次，幂等键必须一样`() {
        // 键由文件名决定，不是随机的
        val a = keyFor("note20260901-140000")
        val b = keyFor("note20260901-140000")
        assertEquals("同一个文件两次导入拿到了不同的键——深脑里会变成两条", a, b)
    }

    @Test fun `不同文件的键必须不同`() {
        assertNotEquals(keyFor("note20260901-140000"), keyFor("note20260901-150000"))
    }

    @Test fun `键要带前缀，能看出是从录音笔导进来的`() {
        assertTrue(keyFor("note20260901-140000").startsWith("ble-"))
    }

    /** 复现 Ingest 里的构造规则。改那边就要改这里——这一条正是要钉住它。 */
    private fun keyFor(title: String) = "ble-" + title.take(80)
}
