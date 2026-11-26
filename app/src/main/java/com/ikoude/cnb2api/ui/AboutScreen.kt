package com.ikoude.cnb2api.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ikoude.cnb2api.R
import com.ikoude.cnb2api.ui.ios.IosBadge
import com.ikoude.cnb2api.ui.ios.IosChevron
import com.ikoude.cnb2api.ui.ios.IosLargeTitle
import com.ikoude.cnb2api.ui.ios.IosRow
import com.ikoude.cnb2api.ui.ios.IosSection
import com.ikoude.cnb2api.ui.ios.IosText
import com.ikoude.cnb2api.ui.ios.LocalIos
import com.ikoude.cnb2api.vm.MainViewModel

private const val REPO_URL = "https://github.com/ikoude/android-cnb2api"
private const val RELEASES_URL = "$REPO_URL/releases"
private const val ISSUES_URL = "$REPO_URL/issues"

/** 关于页：版本、构建、开源地址与第三方组件。 */
@Composable
fun AboutScreen(@Suppress("UNUSED_PARAMETER") vm: MainViewModel) {
    val p = LocalIos.current
    val context = LocalContext.current

    val info = remember {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        }.getOrNull()
    }
    val versionName = info?.versionName ?: "—"
    val versionCode = info?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode
        else @Suppress("DEPRECATION") it.versionCode.toLong()
    } ?: 0L
    val minSdk = info?.applicationInfo?.minSdkVersion ?: 0
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Column(
        Modifier.fillMaxSize().background(p.groupedBg).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 76.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IosLargeTitle("关于")

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                tint = p.blue,
                modifier = Modifier.size(72.dp),
            )
            Text("CNB2API", style = IosText.title(), color = p.label)
            Text("把 CNB2API 网关放到手机上", style = IosText.footnote(), color = p.secondaryLabel)
            IosBadge("v$versionName", p.blue)
        }

        IosSection(header = "应用信息") {
            IosRow(title = "版本", value = "$versionName（$versionCode）")
            IosRow(title = "包名", value = context.packageName)
            IosRow(title = "构建类型", value = if (debuggable) "调试版" else "正式版")
            IosRow(title = "最低系统", value = if (minSdk > 0) "Android 8.0（API $minSdk）" else "—", showDivider = false)
        }

        IosSection(header = "链接") {
            IosRow(
                title = "开源仓库", subtitle = REPO_URL,
                onClick = { open(REPO_URL) }, trailing = { IosChevron(false) },
            )
            IosRow(
                title = "发行版本", subtitle = "查看更新与下载安装包",
                onClick = { open(RELEASES_URL) }, trailing = { IosChevron(false) },
            )
            IosRow(
                title = "问题反馈", subtitle = "报告 Bug 或提建议",
                onClick = { open(ISSUES_URL) }, trailing = { IosChevron(false) }, showDivider = false,
            )
        }

        IosSection(header = "开源组件") {
            IosRow(title = "Jetpack Compose", value = "Apache-2.0")
            IosRow(title = "OkHttp", value = "Apache-2.0")
            IosRow(title = "NanoHTTPD", value = "BSD-3-Clause")
            IosRow(title = "BouncyCastle", value = "MIT")
            IosRow(title = "Haze", value = "Apache-2.0", showDivider = false)
        }

        Text(
            "© 2026 ikoude · 本应用与 CNB 官方无关",
            style = IosText.caption(), color = p.tertiaryLabel,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}
