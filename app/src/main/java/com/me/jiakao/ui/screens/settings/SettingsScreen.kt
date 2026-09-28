package com.me.jiakao.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.me.jiakao.core.model.UpdateState
import com.me.jiakao.data.FONT_SCALES
import com.me.jiakao.data.ThemeMode
import com.me.jiakao.data.VEHICLES
import com.me.jiakao.ui.components.SectionTitle
import com.me.jiakao.ui.components.SegmentedControl
import com.me.jiakao.ui.screens.home.formatBytes

/** 设置:主题/字号/车型/背题/自动更新/题库源/检查更新/离线包/备份恢复 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var sourceInput by rememberSaveable(state.sourceUrl) { mutableStateOf(state.sourceUrl) }

    // 文档选择器
    val importPackLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        viewModel.importLocalPack(uri?.let { context.contentResolver.openInputStream(it) })
    }
    val exportBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        viewModel.exportBackup(uri?.let { context.contentResolver.openOutputStream(it) })
    }
    val importBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        viewModel.importBackup(uri?.let { context.contentResolver.openInputStream(it) })
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("外观")
            SettingsCard {
                SettingRow("主题") {
                    SegmentedControl(
                        options = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
                        selected = state.themeMode,
                        onSelected = viewModel::setThemeMode,
                        label = { when (it) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" } },
                        modifier = Modifier.fillMaxWidth(0.82f),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                SettingRow("字号缩放") {
                    SegmentedControl(
                        options = FONT_SCALES,
                        selected = state.fontScale,
                        onSelected = viewModel::setFontScale,
                        label = { f -> if (f <= 1.0f) "标准" else if (f <= 1.15f) "大" else "特大" },
                        modifier = Modifier.fillMaxWidth(0.82f),
                    )
                }
            }

            SectionTitle("学习")
            SettingsCard {
                SettingRow("车型") {
                    SegmentedControl(
                        options = VEHICLES.map { it.first },
                        selected = state.vehicle,
                        onSelected = viewModel::setVehicle,
                        label = { v -> VEHICLES.firstOrNull { it.first == v }?.second ?: v },
                        modifier = Modifier.fillMaxWidth(0.82f),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                SettingRow("背题模式(直接显示答案)") {
                    Switch(checked = state.reciteMode, onCheckedChange = viewModel::setReciteMode)
                }
            }

            SectionTitle("题库更新")
            SettingsCard {
                SettingRow("题库版本") {
                    Text("v${state.bankVersion}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("题库源地址", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = sourceInput,
                        onValueChange = { sourceInput = it },
                        singleLine = true,
                        placeholder = { Text("https://example.com/bank/") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { viewModel.setSource(sourceInput) }) { Text("保存地址") }
                        OutlinedButton(onClick = { importPackLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                            Text("导入离线包")
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                UpdatePanel(
                    state = state,
                    onCheck = viewModel::checkUpdate,
                    onStart = viewModel::startUpdate,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                SettingRow("Wi-Fi 下每日自动检查更新") {
                    Switch(checked = state.autoUpdate, onCheckedChange = viewModel::setAutoUpdate)
                }
            }

            SectionTitle("数据")
            SettingsCard {
                SettingRow("备份学习进度") {
                    TextButton(onClick = { exportBackupLauncher.launch("jiakao-backup.json") }) { Text("导出") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                SettingRow("恢复学习进度") {
                    TextButton(onClick = { importBackupLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) { Text("导入") }
                }
            }

            SectionTitle("关于")
            SettingsCard {
                SettingRow("版本") { Text("v1.0.0", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                SettingRow("界面与动效") { Text("02-app-ui", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { content() }
    }
}

@Composable
private fun SettingRow(label: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** 检查更新面板:完整呈现 UpdateState 各状态与进度 */
@Composable
private fun UpdatePanel(
    state: SettingsUiState,
    onCheck: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val u = state.updateState) {
            is UpdateState.Idle -> {
                Text("检查题库更新,保持题目最新", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("检查更新") }
            }
            is UpdateState.Checking -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                    Text("正在检查更新…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            is UpdateState.Available -> {
                Text(
                    "发现新版本 v${u.fromVersion} → v${u.toVersion}(${formatBytes(u.downloadBytes)})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("立即更新") }
            }
            is UpdateState.DownloadingPack -> {
                Text("正在下载题库包 ${formatBytes(u.doneBytes)}/${formatBytes(u.totalBytes)}", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { if (u.totalBytes > 0) u.doneBytes.toFloat() / u.totalBytes else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is UpdateState.Importing -> {
                Text("正在导入题目…", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is UpdateState.DownloadingMedia -> {
                Text("正在补齐媒体 ${u.done}/${u.total}", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { if (u.total > 0) u.done.toFloat() / u.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is UpdateState.UpToDate -> {
                Text("题库 v${u.version} 已是最新 ✓", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("再次检查") }
            }
            is UpdateState.Failed -> {
                Text("更新失败:${u.message}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                if (u.retryable) {
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("重试") }
                } else {
                    OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("重新检查") }
                }
            }
        }
    }
}
