package com.qiuyiwu.shennao

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 第一版只证明一件事：整条路是通的。
 * Kotlin 编译 → APK → 装到手机 → 调 /api/mobile/today → 看到真实的承诺。
 * 登录、通知、下钻都还没有——先把这条路走通，再往上加。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("深脑", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text("骨架就位，下一步接登录与 /api/mobile/today",
                             style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
