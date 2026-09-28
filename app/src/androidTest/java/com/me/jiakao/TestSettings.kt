package com.me.jiakao

import com.me.jiakao.data.AppSettings
import com.me.jiakao.data.SettingsState
import com.me.jiakao.data.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** UI 测试用假设置 */
class TestSettings(initial: SettingsState = SettingsState()) : AppSettings {
    private val backing = MutableStateFlow(initial)
    override val settings: Flow<SettingsState> = backing
    override suspend fun setSubject(subject: Int) { backing.value = backing.value.copy(subject = subject) }
    override suspend fun setVehicle(vehicle: String) { backing.value = backing.value.copy(vehicle = vehicle) }
    override suspend fun setThemeMode(mode: ThemeMode) { backing.value = backing.value.copy(themeMode = mode) }
    override suspend fun setFontScale(scale: Float) { backing.value = backing.value.copy(fontScale = scale) }
    override suspend fun setReciteMode(enabled: Boolean) { backing.value = backing.value.copy(reciteMode = enabled) }
    override suspend fun setAutoUpdate(enabled: Boolean) { backing.value = backing.value.copy(autoUpdate = enabled) }
}
