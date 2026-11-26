package com.ikoude.cnb2api.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikoude.cnb2api.data.AppSettings
import com.ikoude.cnb2api.data.Backup
import com.ikoude.cnb2api.data.BackupParseResult
import com.ikoude.cnb2api.ui.ios.IosAccentColors
import com.ikoude.cnb2api.ui.ios.IosAccentNames
import com.ikoude.cnb2api.ui.ios.IosButton
import com.ikoude.cnb2api.ui.ios.IosButtonStyle
import com.ikoude.cnb2api.ui.ios.IosColorDot
import com.ikoude.cnb2api.ui.ios.IosDragHandle
import com.ikoude.cnb2api.ui.ios.IosBadge
import com.ikoude.cnb2api.ui.ios.IosDialog
import com.ikoude.cnb2api.ui.ios.IosFieldRow
import com.ikoude.cnb2api.ui.ios.IosLargeTitle
import com.ikoude.cnb2api.ui.ios.IosRow
import com.ikoude.cnb2api.ui.ios.IosSection
import com.ikoude.cnb2api.ui.ios.IosSegmented
import com.ikoude.cnb2api.ui.ios.IosSwitch
import com.ikoude.cnb2api.ui.ios.IosText
import com.ikoude.cnb2api.ui.ios.LocalIos
import com.ikoude.cnb2api.vm.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.snapshot.collectAsStateWithLifecycle()
    val p = LocalIos.current
    var models by rememberSaveable { mutableStateOf(s.settings.models) }
    var checkSeconds by rememberSaveable { mutableStateOf(s.settings.checkSeconds.toString()) }
    var port by rememberSaveable { mutableStateOf(s.settings.port.toString()) }
    var gatewayPort by rememberSaveable { mutableStateOf(s.settings.gatewayPort.toString()) }
    var gatewayKey by rememberSaveable { mutableStateOf(s.settings.gatewayKey) }
    var low by rememberSaveable { mutableStateOf(s.settings.lowCreditPercent.toString()) }
    var floor by rememberSaveable { mutableStateOf(s.settings.creditFloorPercent.toString()) }
    var notice by rememberSaveable { mutableStateOf(s.settings.lowCreditMessage) }
    var autoStart by rememberSaveable { mutableStateOf(s.settings.autoStart) }
    var appearance by rememberSaveable { mutableStateOf(s.settings.appearance) }
    var accent by rememberSaveable { mutableStateOf(s.settings.accent) }
    var customAccent by rememberSaveable { mutableStateOf(if (s.settings.accent.startsWith("#")) s.settings.accent else "") }
    var listenHost by rememberSaveable { mutableStateOf(s.settings.listenHost) }
    var gatewayAutoStart by rememberSaveable { mutableStateOf(s.settings.gatewayAutoStart) }
    var tabOrder by rememberSaveable { mutableStateOf(s.settings.tabOrder) }
    var hiddenTabs by rememberSaveable { mutableStateOf(s.settings.hiddenTabs) }
    val context = LocalContext.current
    var backupScope by rememberSaveable { mutableIntStateOf(2) }
    var backupPassword by rememberSaveable { mutableStateOf(s.settings.backupPassword) }
    var backupMsg by rememberSaveable { mutableStateOf("") }
    var webdavUrl by rememberSaveable { mutableStateOf(s.settings.webdavUrl) }
    var webdavUser by rememberSaveable { mutableStateOf(s.settings.webdavUser) }
    var webdavPassword by rememberSaveable { mutableStateOf(s.settings.webdavPassword) }
    var autoBackup by rememberSaveable { mutableStateOf(s.settings.autoBackup) }
    // WebDAV 用独立草稿：输入时不落盘，点「保存 WebDAV」才生效。
    var savedWebdavUrl by rememberSaveable { mutableStateOf(s.settings.webdavUrl) }
    var savedWebdavUser by rememberSaveable { mutableStateOf(s.settings.webdavUser) }
    var savedWebdavPassword by rememberSaveable { mutableStateOf(s.settings.webdavPassword) }
    var savedAutoBackup by rememberSaveable { mutableStateOf(s.settings.autoBackup) }
    var pendingRestore by remember { mutableStateOf<BackupParseResult?>(null) }
    var pendingSource by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(pendingExport.toByteArray()) }
                        ?: error("无法写入所选文件")
                }.onSuccess { backupMsg = "已导出到所选文件" }
                    .onFailure { backupMsg = "导出失败 · ${it.message}" }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("无法读取所选文件")
                    val parsed = Backup.parse(text, backupPassword)
                    pendingRestore = parsed
                    pendingSource = "本地文件"
                    backupMsg = "已读取备份，请确认是否恢复"
                }.onFailure { backupMsg = "导入失败 · ${it.message}" }
            }
        }
    }

    fun currentSettings(): AppSettings = AppSettings(
        models = models,
        checkSeconds = checkSeconds.toIntOrNull() ?: 120,
        port = port.toIntOrNull() ?: 9001,
        gatewayPort = gatewayPort.toIntOrNull() ?: 41729,
        gatewayKey = gatewayKey,
        lowCreditPercent = low.toIntOrNull() ?: 5,
        creditFloorPercent = floor.toIntOrNull() ?: 1,
        lowCreditMessage = notice,
        autoStart = autoStart,
        accent = accent,
        appearance = appearance,
        listenHost = listenHost,
        gatewayAutoStart = gatewayAutoStart,
        tabOrder = tabOrder,
        hiddenTabs = hiddenTabs,
        webdavUrl = savedWebdavUrl,
        webdavUser = savedWebdavUser,
        webdavPassword = savedWebdavPassword,
        autoBackup = savedAutoBackup,
        backupPassword = backupPassword,
    )

    fun autosave() {
        vm.saveSettings(currentSettings())
    }

    fun applySettingsToUi(v: AppSettings) {
        models = v.models
        checkSeconds = v.checkSeconds.toString()
        port = v.port.toString()
        gatewayPort = v.gatewayPort.toString()
        gatewayKey = v.gatewayKey
        low = v.lowCreditPercent.toString()
        floor = v.creditFloorPercent.toString()
        notice = v.lowCreditMessage
        autoStart = v.autoStart
        appearance = v.appearance
        accent = v.accent
        customAccent = if (v.accent.startsWith("#")) v.accent else ""
        listenHost = v.listenHost
        gatewayAutoStart = v.gatewayAutoStart
        tabOrder = v.tabOrder
        hiddenTabs = v.hiddenTabs
        webdavUrl = v.webdavUrl
        webdavUser = v.webdavUser
        webdavPassword = v.webdavPassword
        autoBackup = v.autoBackup
        savedWebdavUrl = v.webdavUrl
        savedWebdavUser = v.webdavUser
        savedWebdavPassword = v.webdavPassword
        savedAutoBackup = v.autoBackup
        backupPassword = v.backupPassword
    }

    fun commitWebDav(notify: Boolean = true) {
        savedWebdavUrl = webdavUrl
        savedWebdavUser = webdavUser
        savedWebdavPassword = webdavPassword
        savedAutoBackup = autoBackup
        autosave()
        if (notify) backupMsg = "WebDAV 设置已保存"
    }

    fun renderNoticePreview(template: String): String {
        val samples = mapOf(
            "{account}" to "示例账号", "{used}" to "1166", "{frozen}" to "0",
            "{consumed}" to "1166", "{remain}" to "0", "{total}" to "1166",
            "{percent}" to "100.00", "{threshold}" to "5", "{unit}" to "cr",
        )
        return Regex("""\{(\w+)\}""").replace(template) { m ->
            samples["{${m.groupValues[1]}}"] ?: m.value
        }.ifBlank { "未填写提示文案" }
    }

    fun applyParsedBackup(parsed: com.ikoude.cnb2api.data.BackupParseResult): String {
        var msg = ""
        if (parsed.scope != "settings") {
            val merged = Backup.merge(vm.manager.accounts(), parsed.accounts)
            vm.saveAccounts(merged.accounts)
            msg = "账号：新增 ${merged.added} · 覆盖 ${merged.replaced}"
        }
        if (parsed.scope != "accounts" && parsed.settings != null) {
            // 本机凭据（网关密钥/WebDAV/备份口令）不随备份走，导入时保留当前值。
            val cur = currentSettings()
            val restored = parsed.settings.copy(
                gatewayKey = cur.gatewayKey,
                webdavUrl = cur.webdavUrl,
                webdavUser = cur.webdavUser,
                webdavPassword = cur.webdavPassword,
                autoBackup = cur.autoBackup,
                backupPassword = cur.backupPassword,
            )
            vm.saveSettings(restored)
            applySettingsToUi(restored)
            vm.previewTheme(restored.appearance, restored.accent)
            vm.previewTabs(restored.tabOrder, restored.hiddenTabs)
            msg = if (msg.isBlank()) "设置已还原" else "$msg；设置已还原"
        }
        return msg.ifBlank { "备份为空" }
    }

    Column(
        Modifier.fillMaxSize().background(p.groupedBg).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 76.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosLargeTitle("设置")

        IosSection(header = "外观") {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("外观模式", style = IosText.footnote(), color = p.secondaryLabel)
                IosSegmented(
                    options = listOf("跟随系统", "浅色", "深色"),
                    selected = when (appearance) { "light" -> 1; "dark" -> 2; else -> 0 },
                    onSelect = {
                        appearance = listOf("system", "light", "dark")[it]
                        autosave()
                        vm.previewTheme(appearance, accent)
                    },
                )
            }
            HorizontalDivider(color = p.separator, thickness = 0.5.dp, modifier = Modifier.padding(start = 14.dp))
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("主题色", style = IosText.footnote(), color = p.secondaryLabel)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IosAccentNames.forEach { (key, label) ->
                        IosColorDot(
                            color = IosAccentColors[key] ?: p.blue,
                            selected = accent == key,
                            label = label,
                        ) {
                            accent = key
                            customAccent = ""
                            autosave()
                            vm.previewTheme(appearance, key)
                        }
                    }
                }
                IosFieldRow(
                    "自定义", customAccent,
                    onValueChange = {
                        customAccent = it
                        if (Regex("#[0-9a-fA-F]{6}").matches(it)) {
                            accent = it
                            autosave()
                            vm.previewTheme(appearance, it)
                        }
                    },
                    placeholder = "#RRGGBB",
                    showDivider = false,
                )
            }
        }

        IosSection(header = "网关", footer = "仅本机 = 127.0.0.1；所有地址 = IPv4 + IPv6，须设密钥。") {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("监听地址", style = IosText.footnote(), color = p.secondaryLabel)
                IosSegmented(
                    options = listOf("仅本机", "所有地址"),
                    selected = if (listenHost.startsWith("127.") || listenHost == "localhost") 0 else 1,
                    onSelect = {
                        listenHost = if (it == 0) "127.0.0.1" else "::"
                        autosave()
                    },
                )
            }
            HorizontalDivider(color = p.separator, thickness = 0.5.dp, modifier = Modifier.padding(start = 14.dp))
            IosFieldRow("监听端口", gatewayPort, { gatewayPort = it; autosave() }, "41729", keyboardType = KeyboardType.Number)
            IosFieldRow("访问密钥", gatewayKey, { gatewayKey = it; autosave() }, "sk-...", secure = true)
            IosRow(
                title = "App 启动时自动运行网关",
                trailing = { IosSwitch(gatewayAutoStart) { gatewayAutoStart = it; autosave() } },
            )
            HorizontalDivider(color = p.separator, thickness = 0.5.dp, modifier = Modifier.padding(start = 14.dp))
            Row(Modifier.fillMaxWidth().padding(14.dp)) {
                if (s.stats.listening) {
                    IosButton("停止网关", IosButtonStyle.DESTRUCTIVE, modifier = Modifier.weight(1f)) { vm.stopService() }
                } else {
                    IosButton("启动网关", IosButtonStyle.TINTED, modifier = Modifier.weight(1f)) { vm.startService() }
                }
            }
            IosButton("后台保活设置", IosButtonStyle.PLAIN, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        IosSection(header = "额度阈值") {
            IosFieldRow("警戒线 %", low, { low = it; autosave() }, "只提示", keyboardType = KeyboardType.Number)
            IosFieldRow("保底线 %", floor, { floor = it; autosave() }, "低于则停用，0 关闭", keyboardType = KeyboardType.Number)
            IosFieldRow("提示文案", notice, { notice = it; autosave() }, "可留空", showDivider = false)
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text("效果预览", style = IosText.caption(), color = p.secondaryLabel)
                Text(renderNoticePreview(notice), style = IosText.footnote(), color = p.label)
            }
        }

        IosSection(header = "提示变量") {
            val vars = listOf(
                "{account}" to "账号名", "{used}" to "已结算消耗", "{frozen}" to "预扣冻结",
                "{consumed}" to "真实消耗 = 已用 + 冻结", "{remain}" to "账面可用", "{total}" to "总额（含赠送）",
                "{percent}" to "已耗百分比", "{threshold}" to "警戒线", "{unit}" to "单位 cr",
            )
            vars.forEachIndexed { i, (key, desc) ->
                IosRow(
                    title = key, value = desc,
                    onClick = { notice = if (notice.isBlank()) key else "$notice $key" },
                    trailing = { IosBadge("插入", p.blue) },
                    showDivider = i != vars.lastIndex,
                )
            }
        }

        IosSection(header = "巡检") {
            IosFieldRow("间隔（秒）", checkSeconds, { checkSeconds = it; autosave() }, "最小 30", keyboardType = KeyboardType.Number)
            IosFieldRow("工作区端口", port, { port = it; autosave() }, "9001", keyboardType = KeyboardType.Number)
            IosRow(
                title = "工作区停止后自动启动",
                trailing = { IosSwitch(autoStart) { autoStart = it; autosave() } },
                showDivider = false,
            )
        }

        IosSection(header = "模型") {
            IosFieldRow("模型清单", models, { models = it; autosave() }, "逗号分隔", showDivider = false)
        }


        IosSection(header = "备份与还原", footer = "备份口令输入即生效；WebDAV 配置需点保存，且不随备份导出。") {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("备份范围", style = IosText.footnote(), color = p.secondaryLabel)
                IosSegmented(
                    options = listOf("账号", "设置", "全部"),
                    selected = backupScope,
                    onSelect = { backupScope = it; backupMsg = "" },
                )
                IosFieldRow("备份口令", backupPassword, { backupPassword = it; autosave() }, "留空则明文", secure = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IosButton("导出备份", IosButtonStyle.TINTED, modifier = Modifier.weight(1f)) {
                        pendingExport = try {
                            Backup.export(listOf("accounts", "settings", "all")[backupScope],
                                vm.manager.accounts(), currentSettings(), backupPassword)
                        } catch (e: Exception) {
                            backupMsg = "导出失败 · ${e.message}"; ""
                        }
                        if (pendingExport.isNotBlank()) exportLauncher.launch("cnb2api-backup.json")
                    }
                    IosButton("导入备份", IosButtonStyle.TINTED, modifier = Modifier.weight(1f)) {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                }
                HorizontalDivider(color = p.separator, thickness = 0.5.dp)
                Text("WebDAV 备份", style = IosText.footnote(), color = p.secondaryLabel)
                IosFieldRow("地址", webdavUrl, { webdavUrl = it }, "https://dav.example.com/dav")
                IosFieldRow("用户名", webdavUser, { webdavUser = it }, "留空表示匿名")
                IosFieldRow("密码", webdavPassword, { webdavPassword = it }, "WebDAV 密码", secure = true)
                IosRow(
                    title = "保存后自动上传",
                    subtitle = "需先设备份口令；改动停止 1.5 秒后自动上传",
                    trailing = { IosSwitch(autoBackup) { autoBackup = it } },
                )
                IosButton("保存 WebDAV", IosButtonStyle.TINTED, modifier = Modifier.fillMaxWidth()) { commitWebDav() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IosButton("上传备份", IosButtonStyle.TINTED, modifier = Modifier.weight(1f)) {
                        val sc = listOf("accounts", "settings", "all")[backupScope]
                        commitWebDav(false)
                        val doc = try {
                            Backup.export(sc, vm.manager.accounts(), currentSettings(), backupPassword)
                        } catch (e: Exception) {
                            backupMsg = "导出失败 · ${e.message}"; null
                        }
                        if (doc != null) {
                            scope.launch {
                                runCatching { vm.uploadWebDav(doc) }
                                    .onSuccess { backupMsg = "已上传 · ${it.first}" }
                                    .onFailure { backupMsg = "上传失败 · ${it.message}" }
                            }
                        }
                    }
                    IosButton("下载备份", IosButtonStyle.TINTED, modifier = Modifier.weight(1f)) {
                        commitWebDav(false)
                        scope.launch {
                            runCatching {
                                val text = vm.downloadWebDav()
                                val parsed = Backup.parse(text, backupPassword)
                                pendingRestore = parsed
                                pendingSource = "WebDAV"
                                "已下载备份，请确认是否恢复"
                            }.onSuccess { backupMsg = it }
                                .onFailure { backupMsg = "下载失败 · ${it.message}" }
                        }
                    }
                }
                if (backupMsg.isNotBlank()) Text(backupMsg, style = IosText.footnote(), color = p.secondaryLabel)
            }
        }

        pendingRestore?.let { parsed ->
            IosDialog(
                onDismiss = { pendingRestore = null; backupMsg = "已取消恢复" },
                title = "恢复备份？",
                confirmText = "恢复",
                dismissText = "取消",
                onConfirm = {
                    backupMsg = applyParsedBackup(parsed)
                    pendingRestore = null
                },
            ) {
                Text("来源：$pendingSource", style = IosText.footnote(), color = p.secondaryLabel)
                Text("范围：${when (parsed.scope) { "accounts" -> "账号"; "settings" -> "设置"; else -> "账号 + 设置" }}", style = IosText.footnote(), color = p.secondaryLabel)
                Text("账号：${parsed.accounts.size} 个 · 设置：${if (parsed.settings != null) "有" else "无"}", style = IosText.footnote(), color = p.secondaryLabel)
                Text("恢复会按仓库合并，同仓库以备份为准；本机网关密钥/WebDAV 凭据不变。", style = IosText.footnote(), color = p.tertiaryLabel)
            }
        }

        IosSection(header = "底部导航栏", footer = "上下拖动调整顺序；设置页固定显示，其余页可隐藏。") {
            val ids = tabOrder.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .let { base -> (base + listOf("dashboard", "accounts", "settings", "logs", "about").filter { it !in base }) }
            val labels = mapOf(
                "dashboard" to "仪表盘", "accounts" to "账号", "settings" to "设置", "logs" to "日志", "about" to "关于",
            )
            val idsState = rememberUpdatedState(ids)
            val density = androidx.compose.ui.platform.LocalDensity.current
            val haptic = LocalHapticFeedback.current
            val dragThreshold = with(density) { 56.dp.toPx() }
            var dragY by remember { mutableFloatStateOf(0f) }
            var draggingId by remember { mutableStateOf<String?>(null) }
            var dropIndex by remember { mutableIntStateOf(-1) }
            ids.forEachIndexed { index, id ->
                val hiddenSet = hiddenTabs.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                Column {
                    if (draggingId != null && draggingId != id && dropIndex == index) {
                        Box(Modifier.fillMaxWidth().height(2.dp).background(p.blue))
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .height(56.dp)
                            .alpha(if (draggingId != null && draggingId != id) 0.45f else 1f)
                            .graphicsLayer {
                                val active = draggingId == id
                                val scale = if (active) 1.04f else 1f
                                scaleX = scale
                                scaleY = scale
                                shadowElevation = if (active) 16.dp.toPx() else 0f
                                translationY = if (active) dragY.coerceIn(-dragThreshold * 3, dragThreshold * 3) else 0f
                            }
                            .clip(RoundedCornerShape(if (draggingId == id) 12.dp else 0.dp))
                            .background(if (draggingId == id) p.card else Color.Transparent)
                            .padding(horizontal = 14.dp)
                            .pointerInput(id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingId = id
                                        dragY = 0f
                                        dropIndex = idsState.value.indexOf(id)
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragEnd = {
                                        val current = idsState.value
                                        val from = current.indexOf(id)
                                        if (from >= 0 && dropIndex in current.indices && dropIndex != from) {
                                            val next = current.toMutableList()
                                            next.removeAt(from)
                                            next.add(dropIndex, id)
                                            tabOrder = next.joinToString(",")
                                            autosave()
                                            vm.previewTabs(tabOrder, hiddenTabs)
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            vm.notify("顺序已调整")
                                        }
                                        draggingId = null
                                        dragY = 0f
                                        dropIndex = -1
                                    },
                                    onDragCancel = {
                                        draggingId = null
                                        dragY = 0f
                                        dropIndex = -1
                                    },
                                ) { change, amount ->
                                    change.consume()
                                    dragY += amount.y
                                    val current = idsState.value
                                    val from = current.indexOf(id)
                                    if (from >= 0) {
                                        val steps = kotlin.math.round(dragY / dragThreshold).toInt()
                                        dropIndex = (from + steps).coerceIn(0, current.lastIndex)
                                    }
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IosDragHandle(modifier = Modifier.padding(end = 12.dp))
                        Text(labels[id] ?: id, style = IosText.body(), color = p.label, modifier = Modifier.weight(1f))
                        if (draggingId == id) IosBadge("拖动中", p.blue)
                        if (id == "settings") {
                            Text("固定", style = IosText.caption(), color = p.secondaryLabel)
                        } else {
                            IosSwitch(id !in hiddenSet) { checked ->
                                val set = hiddenSet.toMutableSet()
                                if (checked) set.remove(id) else set.add(id)
                                hiddenTabs = set.joinToString(",")
                                autosave()
                                vm.previewTabs(tabOrder, hiddenTabs)
                            }
                        }
                    }
                    if (index != ids.lastIndex) {
                        HorizontalDivider(color = p.separator, thickness = 0.5.dp, modifier = Modifier.padding(start = 14.dp))
                    }
                }
            }
        }

    }
}
