package com.qiuyiwu.shennao

/**
 * 通知 id 只在这里定。
 * 2026-09-06 审计（012 P0-6）：Remind 和 BleImportService 都用了 2——每天 9 点的到期提醒会把
 * 灵魂卡导入的前台通知顶掉。散着写就会撞，集中了一眼能看出来。
 */
object Notif {
    const val RECORDING = 1          // 录音前台
    const val BLE_IMPORT = 2         // 灵魂卡导入前台
    const val BLE_SYNC_DONE = 3      // 一批同步完的结果
    const val DAILY_REMIND = 10      // 每天 9 点到期提醒
    const val NEW_JUDGMENTS_BASE = 4000
}
