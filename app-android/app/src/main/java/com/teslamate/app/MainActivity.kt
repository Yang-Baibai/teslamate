package com.teslamate.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.teslamate.app.ui.TeslaMateApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // HyperOS 2 / Android 15 对 targetSdk 35 强制无边框；显式启用以保证
        // 各系统版本行为一致（状态栏透明，Material3 TopAppBar/NavigationBar 自行消化 insets）
        enableEdgeToEdge()
        setContent {
            TeslaMateApp()
        }
    }
}
