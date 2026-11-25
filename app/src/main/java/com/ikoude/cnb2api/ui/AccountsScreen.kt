package com.ikoude.cnb2api.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikoude.cnb2api.R
import com.ikoude.cnb2api.data.AccountConfig
import com.ikoude.cnb2api.data.num1
import com.ikoude.cnb2api.data.OrgOption
import com.ikoude.cnb2api.ui.ios.IosButton
import com.ikoude.cnb2api.ui.ios.IosButtonStyle
import com.ikoude.cnb2api.ui.ios.IosFieldRow
import com.ikoude.cnb2api.ui.ios.IosIconButton
import com.ikoude.cnb2api.ui.ios.IosLargeTitle
import com.ikoude.cnb2api.ui.ios.IosText
import com.ikoude.cnb2api.ui.ios.LocalIos
import com.ikoude.cnb2api.vm.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun AccountsScreen(vm: MainViewModel) {
    val s by vm.snapshot.collectAsStateWithLifecycle()
    val focusId by vm.focusAccount.collectAsStateWithLifecycle()
    val p = LocalIos.current
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var expandedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(focusId) {
        if (focusId != null) {
            expandedIds = expandedIds + focusId!!
            vm.consumeFocusAccount()
        }
    }

    Column(
        Modifier.fillMaxSize().background(p.groupedBg).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 76.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IosLargeTitle("账号", modifier = Modifier.weight(1f))
            IosIconButton(R.drawable.ic_refresh, "刷新全部", onClick = { vm.refreshAll() })
            Spacer(Modifier.width(8.dp))
            IosIconButton(R.drawable.ic_add, "添加账号", onClick = { showAdd = true })
        }
        if (s.accounts.isNotEmpty()) {
            val allOpen = s.accounts.all { it.identity in expandedIds }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("共 ${s.accounts.size} 个账号", style = IosText.caption(), color = p.secondaryLabel, modifier = Modifier.weight(1f))
                Text(
                    if (allOpen) "收起全部" else "展开全部",
                    style = IosText.caption(), color = p.blue,
                    modifier = Modifier.clickable {
                        expandedIds = if (allOpen) emptyList<String>() else s.accounts.map { it.identity }
                    }.padding(vertical = 6.dp, horizontal = 4.dp),
                )
            }
        }
        val sortedAccounts = remember(s.accounts) {
            s.accounts.sortedWith(
                compareByDescending<com.ikoude.cnb2api.data.AccountSnapshot> {
                    it.ledger?.credit?.remain ?: -1.0
                }.thenByDescending { it.usable },
            )
        }
        sortedAccounts.forEach { a ->
            val isOpen = a.identity in expandedIds
            AccountCard(
                a = a,
                vm = vm,
                onDelete = {
                    val next = vm.manager.accounts().filterNot { acc ->
                        "${com.ikoude.cnb2api.core.Monitor.fingerprint(acc.token)}@${acc.normalizedRepo}" == a.identity
                    }
                    vm.saveAccounts(next)
                },
                collapsible = true,
                expanded = isOpen,
                onToggle = { expandedIds = if (isOpen) expandedIds - a.identity else expandedIds + a.identity },
            )
        }
        if (s.accounts.isEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("还没有账号", style = IosText.body(), color = p.label)
                Text("点「添加账号」，按引导一步步填令牌、组织、仓库与密钥", style = IosText.footnote(), color = p.secondaryLabel)
            }
        }
    }

    if (showAdd) AddAccountWizard(
        vm = vm,
        onDismiss = { showAdd = false },
        onSaved = { id -> expandedIds = expandedIds + id },
    )
}

@Composable
private fun StepDots(step: Int, total: Int) {
    val p = LocalIos.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for (i in 1..total) {
            Box(
                Modifier.size(if (i == step) 7.dp else 6.dp).clip(CircleShape)
                    .background(if (i <= step) p.blue else p.gray4),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text("第 $step 步 / 共 $total 步", style = IosText.caption(), color = p.secondaryLabel)
    }
}

@Composable
private fun SelectRow(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalIos.current
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 9.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = IosText.body(), color = if (selected) p.blue else p.label)
        if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(p.blue))
    }
}

@Composable
private fun AddAccountWizard(vm: MainViewModel, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val p = LocalIos.current
    var step by remember { mutableIntStateOf(1) }
    var token by remember { mutableStateOf("") }
    var orgs by remember { mutableStateOf<List<OrgOption>>(emptyList()) }
    var org by remember { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var repo by remember { mutableStateOf("") }
    var proxyKey by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val total = 4

    fun finalRepo(): String = if (repo.contains("/")) repo else if (org.isNotBlank() && repo.isNotBlank()) "$org/$repo" else repo

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("添加 CNB 账号", style = IosText.headline().copy(fontWeight = FontWeight.SemiBold), color = p.label)
            StepDots(step, total)

            Column(
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (step) {
                    1 -> {
                        Text("粘贴 CNB 访问令牌（不以 sk- 开头）", style = IosText.footnote(), color = p.secondaryLabel)
                        IosFieldRow("令牌", token, { token = it; message = "" }, "访问令牌", secure = true, showDivider = false)
                    }
                    2 -> {
                        Text("选择组织（来自令牌可访问的列表）", style = IosText.footnote(), color = p.secondaryLabel)
                        orgs.forEach { o ->
                            val base = if (o.personal) "${o.name}（个人空间）" else o.name
                            val suffix = o.credit?.let { c ->
                                if (c.exhausted) "已用尽" else "可用 ${num1(c.remain)}/${num1(c.total)} cr"
                            } ?: "额度未知"
                            SelectRow("$base · $suffix", o.name == org) { org = o.name }
                        }
                        if (orgs.isEmpty()) Text("没有读到组织 · 返回重试验证", style = IosText.footnote(), color = p.orange)
                    }
                    3 -> {
                        if (org.isNotBlank()) {
                            Text("组织：$org", style = IosText.caption(), color = p.blue)
                        }
                        Text("选择仓库（优先选「运行中」的）", style = IosText.footnote(), color = p.secondaryLabel)
                        repos.forEach { (name, running) ->
                            SelectRow("$name${if (running) " · 运行中" else ""}", name == repo) { repo = name }
                        }
                        IosFieldRow("或手填", repo, { repo = it }, "组织/仓库", showDivider = false)
                    }
                    else -> {
                        Text("填写该仓库的 PROXY_KEY（来自 .cnb.yml 的 env）", style = IosText.footnote(), color = p.secondaryLabel)
                        IosFieldRow("PROXY_KEY", proxyKey, { proxyKey = it }, "sk-...", secure = true)
                        IosFieldRow("分支", branch, { branch = it }, "留空用全局", showDivider = false)
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(p.fill).padding(10.dp)) {
                            Text("即将添加", style = IosText.caption(), color = p.secondaryLabel)
                            Text(finalRepo(), style = IosText.body(), color = p.label)
                        }
                        IosButton(
                            "验证 PROXY_KEY",
                            IosButtonStyle.TINTED,
                            enabled = !busy && proxyKey.isNotBlank() && finalRepo().contains("/"),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            busy = true; message = "验证中…"
                            scope.launch {
                                val err = vm.verifyProxyKey(token.trim(), finalRepo(), proxyKey.trim())
                                message = err ?: "密钥可用 · 网关接受该 PROXY_KEY"
                                busy = false
                            }
                        }
                    }
                }
                if (message.isNotBlank()) {
                    val error = Regex("失败|拒绝|错误|不可用|无效").containsMatchIn(message)
                    Text(
                        message,
                        style = IosText.footnote(),
                        color = if (error) p.red else p.secondaryLabel,
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IosButton(if (step == 1) "取消" else "上一步", IosButtonStyle.PLAIN, enabled = !busy, modifier = Modifier.weight(1f)) {
                    if (step == 1) onDismiss() else { step -= 1; message = "" }
                }
                IosButton(
                    if (step == total) "保存" else "下一步",
                    IosButtonStyle.FILLED,
                    enabled = !busy && when (step) {
                        1 -> token.isNotBlank()
                        2 -> org.isNotBlank()
                        3 -> finalRepo().contains("/")
                        else -> proxyKey.isNotBlank()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    when (step) {
                        1 -> {
                            busy = true; message = ""
                            scope.launch {
                                vm.probeToken(token.trim()).fold(
                                    onSuccess = { orgs = it; org = it.firstOrNull()?.name.orEmpty(); step = 2 },
                                    onFailure = { message = it.message ?: "令牌验证失败" },
                                )
                                busy = false
                            }
                        }
                        2 -> {
                            busy = true
                            scope.launch {
                                repos = vm.probeRepos(token.trim(), org)
                                repo = repos.firstOrNull()?.first ?: ""
                                step = 3
                                busy = false
                            }
                        }
                        3 -> step = 4
                        else -> {
                            if (finalRepo().contains("/") && proxyKey.isNotBlank()) {
                                val next = vm.manager.accounts() + AccountConfig(
                                    token = token.trim(), repo = finalRepo(), proxyKey = proxyKey.trim(), branch = branch.trim(),
                                )
                                val id = "${com.ikoude.cnb2api.core.Monitor.fingerprint(token.trim())}@${com.ikoude.cnb2api.data.normalizeRepo(finalRepo())}"
                                vm.saveAccounts(next)
                                onSaved(id)
                                onDismiss()
                            }
                        }
                    }
                }
            }
        }
    }
}
