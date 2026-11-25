package com.ikoude.cnb2api.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ikoude.cnb2api.R
import com.ikoude.cnb2api.data.LogLevel
import com.ikoude.cnb2api.ui.ios.IosBadge
import com.ikoude.cnb2api.ui.ios.IosIconButton
import com.ikoude.cnb2api.ui.ios.IosButtonStyle
import com.ikoude.cnb2api.ui.ios.IosLargeTitle
import com.ikoude.cnb2api.ui.ios.IosSegmented
import com.ikoude.cnb2api.ui.ios.IosText
import com.ikoude.cnb2api.ui.ios.LocalIos
import com.ikoude.cnb2api.vm.MainViewModel

@Composable
fun LogsScreen(vm: MainViewModel) {
    val s by vm.snapshot.collectAsStateWithLifecycle()
    val p = LocalIos.current
    var filter by rememberSaveable { mutableIntStateOf(0) }

    val labels = listOf("全部", "信息", "警告", "错误")
    val levels = listOf(null, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)
    val shown = s.logs.filter { levels[filter] == null || it.level == levels[filter] }.asReversed()

    Column(
        Modifier.fillMaxSize().background(p.groupedBg).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 76.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IosLargeTitle("日志", modifier = Modifier.weight(1f))
            IosIconButton(R.drawable.ic_refresh, "刷新", onClick = { vm.refreshAll() })
        }
        IosSegmented(labels, filter, onSelect = { filter = it })
        Text(
            "共 ${s.logs.size} 条 · 显示 ${shown.size} 条",
            style = IosText.caption(), color = p.secondaryLabel,
        )
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.card)) {
            if (shown.isEmpty()) {
                Text("没有符合条件的日志", Modifier.padding(16.dp), style = IosText.footnote(), color = p.secondaryLabel)
            }
            shown.forEachIndexed { i, line ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(line.time, style = IosText.mono(), color = p.secondaryLabel)
                    val (label, color) = when (line.level) {
                        LogLevel.ERROR -> "错误" to p.red
                        LogLevel.WARN -> "警告" to p.orange
                        LogLevel.INFO -> "信息" to p.blue
                    }
                    IosBadge(label, color)
                    Text(line.text, style = IosText.mono(), color = p.label, modifier = Modifier.weight(1f))
                }
                if (i != shown.lastIndex) {
                    HorizontalDivider(color = p.separator, thickness = 0.5.dp, modifier = Modifier.padding(start = 14.dp))
                }
            }
        }
    }
}
