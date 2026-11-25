package com.ikoude.cnb2api.ui.ios

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun IosLargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 4.dp, top = 6.dp, bottom = 0.dp),
        style = IosText.largeTitle(),
        color = LocalIos.current.label,
    )
}

@Composable
fun IosSection(
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalIos.current
    Column(Modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header.uppercase(),
                Modifier.padding(start = 14.dp, bottom = 4.dp),
                style = IosText.caption(),
                color = p.secondaryLabel,
            )
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.card)) {
            content()
        }
        if (footer != null) {
            Text(
                footer,
                Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp),
                style = IosText.caption(),
                color = p.secondaryLabel,
            )
        }
    }
}

@Composable
fun IosRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    val p = LocalIos.current
    Column {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) { leading(); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, style = IosText.body(), color = p.label)
                if (subtitle != null) Text(subtitle, style = IosText.caption(), color = p.secondaryLabel)
            }
            if (value != null) {
                Text(value, style = IosText.body(), color = p.secondaryLabel, maxLines = 1)
            }
            if (trailing != null) { Spacer(Modifier.width(6.dp)); trailing() }
        }
        if (showDivider) HorizontalDivider(color = p.separator, thickness = 0.5.dp, modifier = Modifier.padding(start = 14.dp))
    }
}

enum class IosButtonStyle { FILLED, TINTED, PLAIN, DESTRUCTIVE }

@Composable
fun IosButton(
    text: String,
    style: IosButtonStyle = IosButtonStyle.TINTED,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val p = LocalIos.current
    val bg = when (style) {
        IosButtonStyle.FILLED -> p.blue
        IosButtonStyle.TINTED -> p.blue.copy(alpha = 0.12f)
        IosButtonStyle.PLAIN -> Color.Transparent
        IosButtonStyle.DESTRUCTIVE -> p.red.copy(alpha = 0.12f)
    }
    val fg = when (style) {
        IosButtonStyle.FILLED -> Color.White
        IosButtonStyle.TINTED -> p.blue
        IosButtonStyle.PLAIN -> p.blue
        IosButtonStyle.DESTRUCTIVE -> p.red
    }
    Box(
        modifier.height(40.dp).clip(RoundedCornerShape(10.dp))
            .background(if (enabled) bg else p.fill)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = IosText.subheadline().copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) fg else p.gray,
            maxLines = 1,
        )
    }
}

@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val p = LocalIos.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = p.green,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = p.gray4,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

data class IosTab(val label: String, @DrawableRes val iconRes: Int)

@Composable
fun IosTabBar(tabs: List<IosTab>, selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalIos.current
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = p.separator.copy(alpha = 0.6f), thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)) {
            tabs.forEachIndexed { i, tab ->
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed) 0.88f else 1f, label = "tabScale")
                Column(
                    Modifier.weight(1f)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clickable(interactionSource = interaction, indication = null) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        painterResource(id = tab.iconRes),
                        contentDescription = tab.label,
                        tint = if (i == selected) p.blue else p.gray,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(tab.label, style = IosText.tab(), color = if (i == selected) p.blue else p.gray)
                }
            }
        }
    }
}

@Composable
fun IosProgressRing(
    progress: Float,
    color: Color,
    centerText: String,
    size: Dp = 52.dp,
    strokeWidth: Dp = 4.5.dp,
) {
    val p = LocalIos.current
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = strokeWidth.toPx()
            drawArc(color = p.gray4, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = Stroke(width = stroke))
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(centerText, style = IosText.caption().copy(fontWeight = FontWeight.SemiBold), color = p.label)
    }
}

@Composable
fun IosBadge(text: String, color: Color) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = IosText.caption().copy(fontWeight = FontWeight.Medium),
        color = color,
    )
}

@Composable
fun IosIconButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val p = LocalIos.current
    Box(
        Modifier.size(size).then(modifier).clip(CircleShape).background(p.blue.copy(alpha = 0.12f)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(id = iconRes), contentDescription = contentDescription, tint = p.blue, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun IosDragHandle(modifier: Modifier = Modifier) {
    val p = LocalIos.current
    Canvas(modifier.size(14.dp)) {
        val stroke = 1.5.dp.toPx()
        for (i in 0..2) {
            val y = (4 + i * 4).dp.toPx()
            drawLine(p.gray, Offset(2.dp.toPx(), y), Offset(12.dp.toPx(), y), stroke, StrokeCap.Round)
        }
    }
}

@Composable
fun IosChevron(expanded: Boolean, modifier: Modifier = Modifier) {
    val p = LocalIos.current
    Canvas(modifier.size(12.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()
        if (expanded) {
            drawLine(p.gray, Offset(w * 0.16f, h * 0.38f), Offset(w * 0.5f, h * 0.66f), stroke, StrokeCap.Round)
            drawLine(p.gray, Offset(w * 0.5f, h * 0.66f), Offset(w * 0.84f, h * 0.38f), stroke, StrokeCap.Round)
        } else {
            drawLine(p.gray, Offset(w * 0.38f, h * 0.16f), Offset(w * 0.66f, h * 0.5f), stroke, StrokeCap.Round)
            drawLine(p.gray, Offset(w * 0.66f, h * 0.5f), Offset(w * 0.38f, h * 0.84f), stroke, StrokeCap.Round)
        }
    }
}

@Composable
fun IosSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalIos.current
    Row(modifier.clip(RoundedCornerShape(8.dp)).background(p.fill).padding(2.dp)) {
        options.forEachIndexed { i, opt ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                    .background(if (i == selected) p.card else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    opt,
                    style = IosText.caption().copy(fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (i == selected) p.label else p.secondaryLabel,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun IosColorDot(color: Color, selected: Boolean, label: String = "", onClick: () -> Unit) {
    val p = LocalIos.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(color).clickable { onClick() }
                .then(if (selected) Modifier.border(2.dp, p.label.copy(alpha = 0.75f), CircleShape) else Modifier),
        )
        if (label.isNotBlank()) {
            Text(label, style = IosText.caption(), color = if (selected) p.label else p.secondaryLabel)
        }
    }
}

@Composable
fun IosFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    secure: Boolean = false,
    showDivider: Boolean = true,
) {
    val p = LocalIos.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = IosText.body(), color = p.label, modifier = Modifier.width(92.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = IosText.body().copy(color = p.label, textAlign = TextAlign.Start),
                cursorBrush = SolidColor(p.blue),
                visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                placeholder,
                                Modifier.fillMaxWidth(),
                                style = IosText.body().copy(color = p.tertiaryLabel, textAlign = TextAlign.Start),
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        if (showDivider) HorizontalDivider(color = p.separator, thickness = 0.5.dp, modifier = Modifier.padding(start = 14.dp))
    }
}

@Composable
fun IosDialog(
    onDismiss: () -> Unit,
    title: String,
    confirmText: String = "确定",
    dismissText: String = "取消",
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalIos.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(p.card)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = IosText.headline().copy(fontSize = 16.sp), color = p.label)
            content()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IosButton(dismissText, IosButtonStyle.PLAIN, modifier = Modifier.weight(1f)) { onDismiss() }
                IosButton(confirmText, IosButtonStyle.FILLED, modifier = Modifier.weight(1f)) { onConfirm() }
            }
        }
    }
}
