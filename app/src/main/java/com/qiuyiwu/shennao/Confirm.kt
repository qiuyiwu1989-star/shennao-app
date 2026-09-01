package com.qiuyiwu.shennao

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

/**
 * 不可逆动作的确认。
 *
 * 判据很窄：**只有做完之后没法撤销的事才问**。
 * 什么都问会训练出「不看就点确定」——那时真正危险的那次也会被点掉。
 *
 * 目前只有两处符合：删除录音（音频没了）、退出登录（本地未传完的录音会留着，
 * 但要重新登录才能继续传）。
 */
@Composable
fun ConfirmDialog(
    title: String,
    /** 说清楚**做完会怎样**，不是重复标题。「确定删除吗」是句废话。 */
    detail: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DS.Radius.sheet,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(detail, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(
                    confirmLabel,
                    // 破坏性动作用 risk 色。它和取消之间隔着一段距离，
                    // 而不是紧挨着——误触的代价是一条录音。
                    color = if (destructive) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
