package com.qiuyiwu.shennao.ble

/*
 * 灵魂卡的三个数：电量、容量、固件。
 *
 * 解码是纯函数，JVM 可测。**解不出来返回 null，不猜**——
 * 一个编出来的「78%」比「—」危险得多：人会照着它决定要不要充电。
 */
data class DeviceInfo(
    /** 0..100；null = 还没读到或读不懂 */
    val batteryPct: Int? = null,
    val charging: Boolean = false,
    /** 单位 KB。协议文档说厂商原文标 8KB，真机验证过的实现按 1KB——跟后者。 */
    val remainKb: Long? = null,
    val totalKb: Long? = null,
    val firmware: String? = null,
) {
    val usedKb: Long? get() = if (remainKb != null && totalKb != null) totalKb - remainKb else null

    /** 「12.4 / 64 GB」这种给人看的说法；缺一个就不给。 */
    val storageLabel: String? get() {
        val u = usedKb ?: return null
        val t = totalKb ?: return null
        return "%.1f / %.0f GB".format(u / 1048576.0, t / 1048576.0)
    }

    val batteryLabel: String? get() = when {
        charging && batteryPct == null -> "充电中"
        charging -> "充电中 · $batteryPct%"
        batteryPct != null -> "$batteryPct%"
        else -> null
    }

    companion object {
        /** 把一帧应答并进来。不是控制命令、或者解不出，就原样返回。 */
        fun merge(cur: DeviceInfo, f: Proto.Frame): DeviceInfo {
            if (f.type != Proto.T.CTRL) return cur
            return when (f.cmd) {
                Proto.CtrlCmd.BAT_ACK -> decodeBattery(f.body)?.let { (p, c) -> cur.copy(batteryPct = p, charging = c) } ?: cur
                Proto.CtrlCmd.CAP_ACK -> decodeCapacity(f.body)?.let { (r, t) -> cur.copy(remainKb = r, totalKb = t) } ?: cur
                Proto.CtrlCmd.FW_ACK -> decodeFirmware(f.body)?.let { cur.copy(firmware = it) } ?: cur
                else -> cur
            }
        }

        /** 1B：0~100；110 表示充电中。别的值不认。 */
        /** 110 = 充电中，协议没给电量——就显示「充电中」，不编 100%（012 P1-11）。 */
        fun decodeBattery(b: ByteArray): Pair<Int?, Boolean>? {
            if (b.isEmpty()) return null
            val v = b[0].toInt() and 0xFF
            return when {
                v == 110 -> null to true
                v in 0..100 -> v to false
                else -> null
            }
        }

        /** remain:4B LE + total:4B LE。total 为 0 或 remain > total 都当读错。 */
        fun decodeCapacity(b: ByteArray): Pair<Long, Long>? {
            if (b.size < 8) return null
            val r = le32(b, 0); val t = le32(b, 4)
            if (t <= 0 || r < 0 || r > t) return null
            return r to t
        }

        /** 6B ASCII，如 V1.0.0。去掉补零；不是可打印字符就不认。 */
        fun decodeFirmware(b: ByteArray): String? {
            val s = b.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII).trim()
            if (s.isEmpty() || s.any { it.code !in 32..126 }) return null
            return s
        }

        private fun le32(b: ByteArray, off: Int): Long =
            (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)
    }
}
