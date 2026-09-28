package com.me.jiakao

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.me.jiakao.data.AppSettings
import com.me.jiakao.data.SettingsState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 持有全局设置,供根组件读取主题与字号缩放 */
@HiltViewModel
class MainViewModel @Inject constructor(
    settings: AppSettings,
    bankAutoSeeder: com.me.jiakao.data.BankAutoSeeder,
) : ViewModel() {

    init {
        bankAutoSeeder.seedAsync()
    }

    val settings: StateFlow<SettingsState> = settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())
}
