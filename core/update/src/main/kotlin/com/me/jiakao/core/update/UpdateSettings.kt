package com.me.jiakao.core.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.me.jiakao.core.update.util.Urls
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * 更新设置的持久化端口（合同 §5：`sourceUrl` 持久化在 DataStore）。
 *
 * 抽成接口是为了让状态机可以在纯 JVM 单测里注入内存实现。
 */
interface UpdateSettingsSource {

    /** 题库源地址，恒以 `/` 结尾；未设置为空字符串。 */
    val sourceUrl: Flow<String>

    /** 上次 manifest 的 ETag，用于 `If-None-Match`。 */
    val etag: Flow<String?>

    /** 规范化（校验 http(s) 并补 `/`）后持久化；空字符串表示清除。 */
    suspend fun setSource(raw: String)

    /** 记录 / 清除 ETag。 */
    suspend fun setEtag(value: String?)
}

/** DataStore 实现（Preferences，文件名 `update_settings`）。 */
@Singleton
class UpdateSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) : UpdateSettingsSource {

    override val sourceUrl: Flow<String> = context.updateDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_SOURCE].orEmpty() }

    override val etag: Flow<String?> = context.updateDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_ETAG] }

    /**
     * 规范化并保存题库源地址。
     *
     * @throws IllegalArgumentException 不是合法的 http(s) 地址
     */
    override suspend fun setSource(raw: String) {
        val normalized = Urls.normalizeSource(raw)
        context.updateDataStore.edit { prefs ->
            if (normalized.isEmpty()) prefs.remove(KEY_SOURCE) else prefs[KEY_SOURCE] = normalized
        }
    }

    override suspend fun setEtag(value: String?) {
        context.updateDataStore.edit { prefs ->
            if (value.isNullOrEmpty()) prefs.remove(KEY_ETAG) else prefs[KEY_ETAG] = value
        }
    }

    private companion object {
        val KEY_SOURCE = stringPreferencesKey("source_url")
        val KEY_ETAG = stringPreferencesKey("manifest_etag")
    }
}

private val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(name = "update_settings")
