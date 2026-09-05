package com.qiuyiwu.shennao.ble

import org.junit.Assert.*
import org.junit.Test

/*
 * TYPE=0 控制命令按 CB08 协议 §4 写，没有真机验证。
 * 这里只能把「帧长得对不对」「解码按文档走不走」钉住，
 * 值对不对要等真机——所以解不出一律 null，界面显示「—」。
 */
class DeviceInfoTest {
    private fun frame(cmd: Int, body: ByteArray, type: Int = Proto.T.CTRL) =
        Proto.Frame(1, byteArrayOf(type.toByte(), cmd.toByte()) + body)

    @Test fun `电量 0到100 直接认，110 是充电中，别的不认`() {
        assertEquals(78 to false, DeviceInfo.decodeBattery(byteArrayOf(78)))
        assertEquals(100 to true, DeviceInfo.decodeBattery(byteArrayOf(110)))
        assertNull("超出范围不能编一个数出来", DeviceInfo.decodeBattery(byteArrayOf(200.toByte())))
        assertNull(DeviceInfo.decodeBattery(byteArrayOf()))
    }

    @Test fun `容量是两个小端 4B，remain 大于 total 当读错`() {
        // remain = 0x00C80000 (13107200 KB ≈ 12.5 GB), total = 0x04000000 (67108864 KB = 64 GB)
        val b = byteArrayOf(0x00, 0x00, 0xC8.toByte(), 0x00, 0x00, 0x00, 0x00, 0x04)
        val (r, t) = DeviceInfo.decodeCapacity(b)!!
        assertEquals(13107200L, r); assertEquals(67108864L, t)
        assertNull(DeviceInfo.decodeCapacity(byteArrayOf(9, 0, 0, 0, 1, 0, 0, 0)))
        assertNull(DeviceInfo.decodeCapacity(byteArrayOf(1, 2, 3)))
    }

    @Test fun `固件是 ASCII，补零去掉，乱码不认`() {
        assertEquals("V1.0.0", DeviceInfo.decodeFirmware("V1.0.0".toByteArray() + byteArrayOf(0, 0)))
        assertNull(DeviceInfo.decodeFirmware(byteArrayOf(0xFF.toByte(), 0xFE.toByte())))
        assertNull(DeviceInfo.decodeFirmware(byteArrayOf(0)))
    }

    @Test fun `三帧并进来，别的类型的帧不影响`() {
        var d = DeviceInfo()
        d = DeviceInfo.merge(d, frame(Proto.CtrlCmd.BAT_ACK, byteArrayOf(78)))
        d = DeviceInfo.merge(d, frame(Proto.CtrlCmd.FW_ACK, "V1.2.0".toByteArray()))
        d = DeviceInfo.merge(d, frame(Proto.FileCmd.LIST_DONE, byteArrayOf(), type = Proto.T.FILE))
        assertEquals("78%", d.batteryLabel)
        assertEquals("V1.2.0", d.firmware)
        assertNull("容量还没读到就不给", d.storageLabel)
        d = DeviceInfo.merge(d, frame(Proto.CtrlCmd.CAP_ACK, byteArrayOf(0x00, 0x00, 0xC8.toByte(), 0x00, 0x00, 0x00, 0x00, 0x04)))
        assertEquals("51.5 / 64 GB", d.storageLabel)
    }

    @Test fun `同步时间帧：TYPE 0 CMD 0，年小端，其余各 1B`() {
        val zone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        val cal = java.util.Calendar.getInstance(zone).apply { set(2026, 8, 5, 13, 7, 9); set(java.util.Calendar.MILLISECOND, 0) }
        val f = Proto.buildSyncTime(cal.timeInMillis, zone)
        assertEquals(0x5A, f[0].toInt() and 0xFF)
        assertEquals(6 + 2 + 7, f.size)
        assertEquals(Proto.T.CTRL, f[6].toInt()); assertEquals(Proto.CtrlCmd.SYNC_TIME, f[7].toInt())
        assertEquals(2026 and 0xFF, f[8].toInt() and 0xFF); assertEquals(2026 shr 8, f[9].toInt() and 0xFF)
        assertEquals(listOf(9, 5, 13, 7, 9), f.slice(10..14).map { it.toInt() })
    }

    @Test fun `查询帧只有 TYPE 和 CMD，没有参数`() {
        for (cmd in listOf(Proto.CtrlCmd.BAT_REQ, Proto.CtrlCmd.CAP_REQ, Proto.CtrlCmd.FW_REQ)) {
            val f = Proto.buildFrame(Proto.T.CTRL, cmd)
            assertEquals(8, f.size)
            assertEquals(2, (f[4].toInt() and 0xFF) or ((f[5].toInt() and 0xFF) shl 8))
        }
    }
}
