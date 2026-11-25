package com.ikoude.cnb2api.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikoude.cnb2api.data.AccountSnapshot
import com.ikoude.cnb2api.data.Bucket
import com.ikoude.cnb2api.data.AppSnapshot
import com.ikoude.cnb2api.data.WorkState
import com.ikoude.cnb2api.data.formatUptime
import com.ikoude.cnb2api.data.num1
import com.ikoude.cnb2api.ui.ios.IosBadge
import com.ikoude.cnb2api.ui.ios.IosButton
import com.ikoude.cnb2api.ui.ios.IosButtonStyle
import com.ikoude.cnb2api.ui.ios.IosChevron
import com.ikoude.cnb2api.ui.ios.IosLargeTitle
import com.ikoude.cnb2api.ui.ios.IosProgressRing
import com.ikoude.cnb2api.ui.ios.IosRow
import com.ikoude.cnb2api.ui.ios.IosSection
import com.ikoude.cnb2api.ui.ios.IosText
import com.ikoude.cnb2api.ui.ios.LocalIos
import com.ikoude.cnb2api.vm.MainViewModel

@Composable
fun DashboardScreen(vm: MainViewModel) {
    val s by vm.snapshot.collectAsStateWithLifecycle()
    val p = LocalIos.current
    val clipboard = LocalClipboardManager.current
    val active = s.accounts.firstOrNull { it.active }
    var gatewayOpen by rememberSaveable { mutableStateOf(true) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var showAllAddresses by rememberSaveable { mutableStateOf(false) }
    val loopback = s.stats.listenHost.startsWith("127.") || s.stats.listenHost == "localhost" || s.stats.listenHost == "::1"
    val addresses = s.stats.entries.ifEmpty {
        listOf(com.ikoude.cnb2api.data.GatewayAddress(
            "本机", "127.0.0.1", s.stats.localUrl, true,
            note = "仅本机回环，手机自己访问", kind = "local",
        ))
    }
    // 默认：本机 + 局域网（Wi‑Fi/以太网）地址。没有局域网时才用移动数据 IPv6 兜底；
    // 有局域网时移动数据 IPv6 收进「其它地址」，避免误导局域网用户。
    fun isMobileEntry(e: com.ikoude.cnb2api.data.GatewayAddress): Boolean =
        e.kind == "mobile" || e.detail.contains("移动数据") ||
            Regex("rmnet|ccmni|pdp", RegexOption.IGNORE_CASE).containsMatchIn(e.detail)
    val localAddresses = addresses.filter { it.kind == "local" || it.family == "本机" }
    val mobileAddresses = addresses.filter { isMobileEntry(it) }
    val lanAddresses = addresses.filter {
        it !in localAddresses && it !in mobileAddresses && it.family != "本机"
    }
    val primaryAddresses = (
        if (lanAddresses.isEmpty()) {
            localAddresses + mobileAddresses.filter { it.primary }.ifEmpty { mobileAddresses.take(1) }
        } else {
            localAddresses + lanAddresses
        }
        ).distinctBy { it.url }
    val visibleAddresses = if (showAllAddresses) addresses else primaryAddresses

    Column(
        Modifier.fillMaxSize().background(p.groupedBg).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 76.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosLargeTitle("仪表盘")
        StatsCard(s)
        val orgLedgers = s.accounts.distinctBy { it.org }.mapNotNull { it.ledger }
        val totalCredit = combineBuckets(orgLedgers.mapNotNull { it.credit })
        val totalHours = combineBuckets(orgLedgers.mapNotNull { it.coreHours })
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.card)) {
            Row(
                Modifier.fillMaxWidth().clickable { gatewayOpen = !gatewayOpen }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(if (s.stats.listening) p.green else p.red)
                Text(
                    if (s.stats.listening) "网关运行中" else "网关未运行",
                    style = IosText.headline(),
                    color = if (s.stats.listening) p.label else p.red,
                )
                Spacer(Modifier.weight(1f))
                if (!gatewayOpen) {
                    Text(active?.label ?: "无可用账号", style = IosText.caption(), color = p.secondaryLabel, maxLines = 1)
                }
                IosChevron(gatewayOpen)
            }
            if (gatewayOpen) {
                HorizontalDivider(color = p.separator, thickness = 0.5.dp, modifier = Modifier.padding(start = 14.dp))
                LabeledRow("当前派活", active?.label ?: "无可用账号")
                HorizontalDivider(color = p.separator, thickness = 0.5.dp, modifier = Modifier.padding(start = 14.dp))
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("访问地址", style = IosText.footnote(), color = p.secondaryLabel, modifier = Modifier.weight(1f))
                        Text(
                            if (loopback) "仅本机" else "所有地址",
                            style = IosText.caption(), color = p.secondaryLabel,
                        )
                    }
                    visibleAddresses.forEach { e ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val familyColor = when (e.family) {
                                        "IPv4" -> p.blue
                                        "IPv6" -> p.orange
                                        else -> p.gray
                                    }
                                    IosBadge(e.family, familyColor)
                                    if (e.primary && e.family != "本机") IosBadge("主", p.green)
                                    Text(e.detail, style = IosText.caption(), color = p.secondaryLabel, maxLines = 1)
                                }
                                Text(
                                    e.url, style = IosText.mono(), color = p.label,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                if (e.note.isNotBlank()) {
                                    Text(e.note, style = IosText.caption(), color = p.tertiaryLabel, maxLines = 2)
                                }
                            }
                            IosButton("复制", IosButtonStyle.TINTED, modifier = Modifier.width(54.dp)) {
                                clipboard.setText(AnnotatedString(e.url))
                            }
                        }
                    }
                    if (addresses.size > primaryAddresses.size) {
                        Text(
                            if (showAllAddresses) "收起地址" else "查看全部地址（${addresses.size}）",
                            style = IosText.caption(), color = p.blue,
                            modifier = Modifier.clickable { showAllAddresses = !showAllAddresses },
                        )
                    }
                    Text(
                        if (loopback) "仅本机可访问；局域网设备需切到「所有地址」" else "地址自动更新；局域网设备优先用 Wi‑Fi IPv4",
                        style = IosText.caption(), color = p.secondaryLabel,
                    )
                }
                HorizontalDivider(color = p.separator, thickness = 0.5.dp, modifier = Modifier.padding(start = 14.dp))
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("访问密钥", style = IosText.body(), color = p.label)
                        Text(
                            if (showKey) s.stats.key else maskKey(s.stats.key),
                            style = IosText.caption(), color = p.secondaryLabel, maxLines = 1,
                        )
                    }
                    IosButton(if (showKey) "隐藏" else "显示", IosButtonStyle.PLAIN, modifier = Modifier.width(46.dp)) {
                        showKey = !showKey
                    }
                    IosButton("复制", IosButtonStyle.TINTED, modifier = Modifier.width(52.dp)) {
                        clipboard.setText(AnnotatedString(s.stats.key))
                    }
                }
                HorizontalDivider(color = p.separator, thickness = 0.5.dp, modifier = Modifier.padding(start = 14.dp))
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.stats.listening) {
                        IosButton("停止", IosButtonStyle.DESTRUCTIVE, modifier = Modifier.weight(1f)) { vm.stopService() }
                    } else {
                        IosButton("启动", IosButtonStyle.FILLED, modifier = Modifier.weight(1f)) { vm.startService() }
                    }
                    IosButton("刷新", IosButtonStyle.TINTED, modifier = Modifier.weight(1f)) { vm.refreshAll() }
                }
            }
        }
        if (totalCredit != null || totalHours != null) {
            IosSection(header = "工作区状态") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusDot(if (totalCredit?.exhausted == true) p.red else p.green)
                    Text(
                        if (totalCredit?.exhausted == true) "总额度已用尽" else "总额度可用",
                        style = IosText.headline(), color = p.label, modifier = Modifier.weight(1f),
                    )
                    IosBadge("${s.accounts.count { it.usable }} / ${s.accounts.size} 可用", p.blue)
                }
                HorizontalDivider(color = p.separator, thickness = 0.5.dp, modifier = Modifier.padding(start = 12.dp))
                totalCredit?.let { StatusQuotaLine("AI credits", "cr", it) }
                totalHours?.let {
                    HorizontalDivider(color = p.separator, thickness = 0.5.dp, modifier = Modifier.padding(start = 12.dp))
                    StatusQuotaLine("dev core-hours", "h", it)
                }
            }
        }


    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    val p = LocalIos.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = IosText.body(), color = p.label, modifier = Modifier.weight(1f))
        Text(value, style = IosText.body(), color = p.secondaryLabel, maxLines = 1)
    }
}

@Composable
private fun StatsCard(s: AppSnapshot) {
    val p = LocalIos.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card).padding(vertical = 14.dp),
    ) {
        Stat("账号", s.accounts.size.toString(), Modifier.weight(1f))
        VDivider()
        Stat("可派活", s.accounts.count { it.usable }.toString(), Modifier.weight(1f))
        VDivider()
        Stat("请求", s.stats.requests.toString(), Modifier.weight(1f))
        VDivider()
        Stat("换号", s.stats.failovers.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    val p = LocalIos.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = IosText.title(), color = p.label)
        Text(label, style = IosText.caption(), color = p.secondaryLabel)
    }
}

@Composable
private fun VDivider() {
    val p = LocalIos.current
    Box(Modifier.width(0.5.dp).height(28.dp).background(p.separator))
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun AccountCard(
    a: AccountSnapshot,
    vm: MainViewModel,
    onDelete: (() -> Unit)? = null,
    collapsible: Boolean = false,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
) {
    val p = LocalIos.current
    val clipboard = LocalClipboardManager.current
    val percent = ((a.ledger?.credit?.usedPercent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    val ringColor = when (a.state) {
        WorkState.MISCONFIGURED -> p.red
        WorkState.NO_CREDIT, WorkState.STARTING, WorkState.UNREACHABLE -> p.orange
        WorkState.UNKNOWN -> p.gray
        else -> p.blue
    }
    val quotaBrief = a.ledger?.credit?.let { c ->
        "${num1(c.used ?: 0.0)} / ${num1(c.total)} cr"
    } ?: a.state.badge

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.card)) {
        if (!expanded) {
            Row(
                Modifier.fillMaxWidth()
                    .then(if (onToggle != null) Modifier.clickable { onToggle() } else Modifier)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IosProgressRing(percent, ringColor, "${(percent * 100).toInt()}", size = 30.dp, strokeWidth = 3.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(a.label, style = IosText.body(), color = p.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val uptime = formatUptime(a.runningSince)
                    val runText = if (uptime.isNotBlank()) " · 运行 $uptime" else ""
                    Text("${a.state.badge} · $quotaBrief$runText", style = IosText.caption(), color = p.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IosChevron(false)
            }
        } else {
            Row(
                Modifier.fillMaxWidth()
                    .then(if (collapsible && onToggle != null) Modifier.clickable { onToggle() } else Modifier)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusDot(ringColor)
                        Text(a.label, style = IosText.headline(), color = p.label,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (collapsible) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("收起", style = IosText.caption(), color = p.secondaryLabel)
                                IosChevron(true)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IosBadge(a.state.badge, ringColor)
                        if (a.active) IosBadge("当前", p.blue)
                        // 三个额度徽标互斥：无额 > 保底停用 > 额度告急，避免重复/冲突。
                        if (a.state != WorkState.NO_CREDIT) {
                            if (a.belowFloor) IosBadge("保底停用", p.red)
                            else if (a.lowCredit) IosBadge("额度告急", p.orange)
                        }
                        if (a.state != WorkState.NO_CREDIT) {
                            IosBadge("已用 ${(percent * 100).toInt()}%", ringColor)
                        }
                    }
                    val diagnosis = a.lastError?.takeIf { it.isNotBlank() }
                        ?: if (a.coolingUntil > System.currentTimeMillis()) "冷却中 · ${a.coolingReason}" else null
                    if (!diagnosis.isNullOrBlank()) {
                        Text(
                            diagnosis,
                            style = IosText.footnote(),
                            color = if (a.state == WorkState.MISCONFIGURED) p.red else p.orange,
                            maxLines = 2,
                        )
                    }
                    HorizontalDivider(color = p.separator, thickness = 0.5.dp)
                    AccountQuotaSummary("AI credits", "cr", a.ledger?.credit)
                    AccountQuotaSummary("核时", "h", a.ledger?.coreHours)
                    val uptime = formatUptime(a.runningSince)
                    val meta = buildList {
                        if (a.branch.isNotBlank()) add("分支 ${a.branch}")
                        if (uptime.isNotBlank()) add("运行 $uptime")
                    }.joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, style = IosText.footnote(), color = p.secondaryLabel)
                    if (a.lastCheckedAt > 0) {
                        val checked = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                            .format(java.util.Date(a.lastCheckedAt))
                        Text("最近检查 $checked", style = IosText.caption(), color = p.tertiaryLabel)
                    }
                    if (a.url.isNotBlank()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                a.url, style = IosText.mono(), color = p.label,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IosButton("复制", IosButtonStyle.TINTED, modifier = Modifier.width(54.dp)) {
                                clipboard.setText(AnnotatedString(a.url))
                            }
                        }
                    } else if (a.host.isNotBlank()) {
                        Text("主机 ${a.host}", style = IosText.footnote(), color = p.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            HorizontalDivider(color = p.separator, thickness = 0.5.dp)
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (a.canStart) {
                    IosButton("启动", IosButtonStyle.TINTED, modifier = Modifier.weight(1f)) { vm.startAccount(a.identity) }
                }
                IosButton("刷新", IosButtonStyle.TINTED, modifier = Modifier.weight(1f)) { vm.refreshAccount(a.identity) }
                if (onDelete != null) {
                    IosButton("删除", IosButtonStyle.DESTRUCTIVE, modifier = Modifier.weight(1f)) { onDelete() }
                }
            }
        }
    }
}

@Composable
private fun AccountQuotaSummary(label: String, unit: String, b: Bucket?) {
    if (b == null) return
    val p = LocalIos.current
    val progress = ((b.usedPercent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = IosText.caption(), color = p.secondaryLabel, modifier = Modifier.width(64.dp))
            Text(
                "${num1(b.used ?: 0.0)} / ${num1(b.total)} $unit",
                style = IosText.footnote(),
                color = if (b.exhausted) p.red else p.label,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                maxLines = 1,
            )
        }
        Box(
            Modifier.fillMaxWidth().height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(p.separator.copy(alpha = 0.4f)),
        ) {
            Box(
                Modifier.fillMaxWidth(progress).fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (b.exhausted) p.red else p.blue),
            )
        }
        Text(
            "剩余 ${num1(b.remain)} $unit" + if (b.frozen > 0.0) " · 冻结 ${num1(b.frozen)}" else "",
            style = IosText.caption(), color = p.secondaryLabel, maxLines = 1,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

private fun maskKey(key: String): String {
    if (key.isBlank()) return "未设置"
    return if (key.startsWith("sk-")) "sk-" + "•".repeat(8) else "•".repeat(10)
}

@Composable
private fun StatusQuotaLine(name: String, unit: String, b: Bucket) {
    val p = LocalIos.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = IosText.body(), color = p.label, modifier = Modifier.weight(1f))
            val head = if (b.exhausted) "已用尽 · " else ""
            Text(
                "$head${num1(b.used ?: 0.0)} / ${num1(b.total)} $unit",
                style = IosText.caption(),
                color = if (b.exhausted) p.red else p.secondaryLabel,
            )
        }
        val progress = ((b.usedPercent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
        Box(
            Modifier.fillMaxWidth().height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(p.separator.copy(alpha = 0.45f)),
        ) {
            Box(
                Modifier.fillMaxWidth(progress).fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (b.exhausted) p.red else p.blue),
            )
        }
        Text("冻结 ${num1(b.frozen)} $unit · 可用 ${num1(b.remain)} $unit",
            style = IosText.caption(), color = p.secondaryLabel)
    }
}

private fun combineBuckets(list: List<Bucket>): Bucket? {
    if (list.isEmpty()) return null
    val total = list.sumOf { it.total }
    val frozen = list.sumOf { it.frozen }
    val remain = list.sumOf { it.remain }
    val used = if (list.all { it.used != null }) list.sumOf { it.used!! } else null
    val pct = if (total > 0 && used != null) ((used + frozen) / total * 100).coerceIn(0.0, 100.0) else null
    return list.first().copy(total = total, used = used, frozen = frozen, remain = remain, usedPercent = pct)
}
