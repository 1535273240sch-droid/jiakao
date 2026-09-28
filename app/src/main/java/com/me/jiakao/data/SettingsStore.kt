package com.me.jiakao.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 主题模式 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 可选车型(与合同 `vehicles` 取值一致) */
val VEHICLES: List<Pair<String, String>> = listOf(
    "car" to "小车",
    "truck" to "货车",
    "bus" to "客车",
    "moto" to "摩托车",
)

/** 字号缩放档位 */
val FONT_SCALES: List<Float> = listOf(1.0f, 1.15f, 1.3f)

/** 用户偏好快照(Immutable,供 Compose 安全重组) */
@androidx.compose.runtime.Immutable
data class SettingsState(
    val subject: Int = 1,
    val vehicle: String = "car",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontScale: Float = 1.0f,
    val reciteMode: Boolean = false,
    val autoUpdate: Boolean = true,
) {
    val fontScaleLabel: String
        get() = when {
            fontScale <= 1.0f -> "标准"
            fontScale <= 1.15f -> "大"
            else -> "特大"
        }
}

/** 应用内偏好(接口化,便于测试替换) */
interface AppSettings {
    val settings: Flow<SettingsState>
    suspend fun setSubject(subject: Int)
    suspend fun setVehicle(vehicle: String)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setFontScale(scale: Float)
    suspend fun setReciteMode(enabled: Boolean)
    suspend fun setAutoUpdate(enabled: Boolean)
}

private val Context.settingsDataStore by preferencesDataStore(name = "jiakao_settings")

/** 基于 DataStore 的真实实现 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppSettings {

    private object Keys {
        val SUBJECT = intPreferencesKey("subject")
        val VEHICLE = stringPreferencesKey("vehicle")
        val THEME = stringPreferencesKey("theme_mode")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val RECITE = booleanPreferencesKey("recite_mode")
        val AUTO_UPDATE = booleanPreferencesKey("auto_update")
    }

    override val settings: Flow<SettingsState> = context.settingsDataStore.data.map { p ->
        SettingsState(
            subject = p[Keys.SUBJECT] ?: 1,
            vehicle = p[Keys.VEHICLE] ?: "car",
            themeMode = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            fontScale = p[Keys.FONT_SCALE] ?: 1.0f,
            reciteMode = p[Keys.RECITE] ?: false,
            autoUpdate = p[Keys.AUTO_UPDATE] ?: true,
        )
    }

    override suspend fun setSubject(subject: Int) {
        context.settingsDataStore.edit { it[Keys.SUBJECT] = subject }
    }

    override suspend fun setVehicle(vehicle: String) {
        context.settingsDataStore.edit { it[Keys.VEHICLE] = vehicle }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME] = mode.name }
    }

    override suspend fun setFontScale(scale: Float) {
        context.settingsDataStore.edit { it[Keys.FONT_SCALE] = scale }
    }

    override suspend fun setReciteMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.RECITE] = enabled }
    }

    override suspend fun setAutoUpdate(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_UPDATE] = enabled }
    }
}

/** 应用内部的绑定(合同核心接口的绑定来自 01/03/04 的模块,此处只绑定 :app 自己的接口) */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    @Singleton
    fun provideAppSettings(@ApplicationContext context: Context): AppSettings = SettingsStore(context)
}
