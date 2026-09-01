package com.qiuyiwu.shennao

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 轻提示。
 *
 * **只用来说屏幕自己说不出来的事。**
 *
 * 落账成功不用提示——卡片已经变成「已记：兑现了」了，再弹一句是废话，
 * 而废话弹多了，真正要紧的那一句就被当成背景噪音划走。
 * 落账**失败**必须提示：现在的做法是悄悄把界面收回原样，
 * 用户会以为是自己点空了，然后再点一次。
 *
 * 判据：**这件事屏幕上已经看得见吗？看得见就别弹。**
 */
val LocalNotice = staticCompositionLocalOf<(String) -> Unit> {
    // 默认什么都不做，而不是抛异常：一个提示弹不出来不该让整个界面崩掉。
    // 但预览和测试里就此静默——所以 App 里必须真的提供一个。
    {}
}

/** 挂在 Scaffold 上的那一份状态。 */
class NoticeHost(val state: SnackbarHostState = SnackbarHostState())
