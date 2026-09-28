package com.me.jiakao

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.me.jiakao.nav.JiakaoRoot
import com.me.jiakao.perf.JankMonitor
import com.me.jiakao.ui.theme.JiakaoTheme
import dagger.hilt.android.AndroidEntryPoint

/** 单 Activity + Compose,edge-to-edge */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            JankMonitor.start(this)
        }
        setContent {
            val settings by mainViewModel.settings.collectAsStateWithLifecycle()
            JiakaoTheme(themeMode = settings.themeMode, fontScale = settings.fontScale) {
                JiakaoRoot()
            }
        }
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG) JankMonitor.stop()
        super.onDestroy()
    }
}
