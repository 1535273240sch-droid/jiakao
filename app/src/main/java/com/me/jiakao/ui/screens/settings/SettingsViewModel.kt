package com.me.jiakao.ui.screens.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.me.jiakao.core.model.BankUpdater
import com.me.jiakao.core.model.UpdateState
import com.me.jiakao.core.model.UserRepository
import com.me.jiakao.data.AppSettings
import com.me.jiakao.data.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

@Immutable
data class SettingsUiState(
    val subject: Int = 1,
    val vehicle: String = "car",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontScale: Float = 1.0f,
    val reciteMode: Boolean = false,
    val autoUpdate: Boolean = true,
    val bankVersion: Int = 0,
    val sourceUrl: String = "",
    val updateState: UpdateState = UpdateState.Idle,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AppSettings,
    private val updater: BankUpdater,
    private val user: UserRepository,
    quiz: com.me.jiakao.core.model.QuizRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.settings,
        updater.state,
        updater.sourceUrl,
        quiz.bankVersion,
    ) { s, update, source, bankVersion ->
        SettingsUiState(
            subject = s.subject,
            vehicle = s.vehicle,
            themeMode = s.themeMode,
            fontScale = s.fontScale,
            reciteMode = s.reciteMode,
            autoUpdate = s.autoUpdate,
            bankVersion = bankVersion,
            sourceUrl = source,
            updateState = update,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }
    fun setFontScale(scale: Float) = viewModelScope.launch { settings.setFontScale(scale) }
    fun setVehicle(vehicle: String) = viewModelScope.launch { settings.setVehicle(vehicle) }
    fun setReciteMode(enabled: Boolean) = viewModelScope.launch { settings.setReciteMode(enabled) }
    fun setAutoUpdate(enabled: Boolean) = viewModelScope.launch {
        settings.setAutoUpdate(enabled)
        updater.scheduleAuto(enabled)
    }

    fun setSource(url: String) = viewModelScope.launch {
        updater.setSource(url)
        _messages.tryEmit("题库源已保存")
    }

    fun checkUpdate() = viewModelScope.launch { updater.check() }
    fun startUpdate() = updater.startUpdate()

    fun importLocalPack(input: InputStream?) {
        if (input == null) return
        viewModelScope.launch {
            runCatching { updater.importLocalPack(input) }
                .onSuccess { _messages.tryEmit("离线包导入成功") }
                .onFailure { _messages.tryEmit("导入失败:${it.message}") }
            runCatching { input.close() }
        }
    }

    fun exportBackup(out: OutputStream?) {
        if (out == null) return
        viewModelScope.launch {
            runCatching { user.exportBackup(out) }
                .onSuccess { _messages.tryEmit("备份已导出") }
                .onFailure { _messages.tryEmit("备份失败:${it.message}") }
            runCatching { out.close() }
        }
    }

    fun importBackup(input: InputStream?) {
        if (input == null) return
        viewModelScope.launch {
            runCatching { user.importBackup(input) }
                .onSuccess { _messages.tryEmit("学习进度已恢复") }
                .onFailure { _messages.tryEmit("恢复失败:${it.message}") }
            runCatching { input.close() }
        }
    }
}
