package com.dsh.wechatshot.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** iOS 点击反馈：无水波纹，避免 Material 的涟漪感。 */
@Composable
fun Modifier.iosClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.then(
        Modifier.clickable(
            enabled = enabled,
            indication = null,
            interactionSource = source,
            onClick = onClick,
        )
    )
}

/** 页面底色：上浅下深的柔和渐变（深色模式为近黑）。 */
@Composable
fun IosGradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalIosColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.backgroundTop, colors.background))),
        content = content,
    )
}

/** 标准滚动页：自带状态栏留白、左右 20dp 留白和滚动。 */
@Composable
fun IosPage(
    modifier: Modifier = Modifier,
    topPadding: Dp = 4.dp,
    bottomPadding: Dp = 32.dp,
    verticalSpacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    IosGradientBackground(modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = topPadding, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            content = content,
        )
    }
}

@Composable
fun IosLargeTitle(title: String, subtitle: String? = null) {
    val colors = LocalIosColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = IosType.largeTitle, color = colors.textPrimary)
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = subtitle, style = IosType.callout, color = colors.textSecondary)
        }
    }
}

/** iOS 导航栏：左侧返回，中间标题。 */
@Composable
fun IosNavBar(
    title: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalIosColors.current
    Box(modifier = modifier.fillMaxWidth().height(46.dp)) {
        if (onBack != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .iosClickable(onClick = onBack)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.ChevronLeft,
                    contentDescription = "返回",
                    tint = colors.accent,
                    modifier = Modifier.size(26.dp),
                )
                Text(text = "返回", fontSize = 17.sp, color = colors.accent)
            }
        }
        if (title != null) {
            Text(
                text = title,
                style = IosType.navTitle,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.dp),
            )
        }
        if (trailing != null) {
            Box(modifier = Modifier.align(Alignment.CenterEnd)) { trailing() }
        }
    }
}

/** iOS 分组列表上方的小标题。 */
@Composable
fun IosSectionHeader(text: String, modifier: Modifier = Modifier) {
    val colors = LocalIosColors.current
    Text(
        text = text,
        style = IosType.footnote.copy(fontWeight = FontWeight.Medium),
        color = colors.textSecondary,
        modifier = modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}

/** 0.5dp 的 iOS 分隔线。 */
@Composable
fun Hairline(modifier: Modifier = Modifier, startIndent: Dp = 16.dp) {
    val colors = LocalIosColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(0.5.dp)
            .background(colors.separator)
    )
}

/** 圆角卡片 / 分组列表容器。 */
@Composable
fun IosCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalIosColors.current
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(colors.card)
        .border(0.5.dp, colors.cardBorder, shape)
        .then(if (onClick != null) Modifier.iosClickable(onClick = onClick) else Modifier)
        .padding(contentPadding)
    Column(modifier = base, content = content)
}

/** 分组列表行。 */
@Composable
fun IosRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    iconBackground: Color? = null,
    chevron: Boolean = false,
    titleColor: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalIosColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.iosClickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(iconBackground ?: colors.fill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint ?: colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = IosType.body,
                fontWeight = FontWeight.Medium,
                color = titleColor ?: colors.textPrimary,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(1.dp))
                Text(text = subtitle, style = IosType.footnote, color = colors.textSecondary)
            }
        }
        if (!value.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = value, style = IosType.body, color = colors.textSecondary)
        }
        trailing?.invoke()
        if (chevron) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** iOS 开关行（绿色系统开关）。 */
@Composable
fun IosSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalIosColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = IosType.body,
                fontWeight = FontWeight.Medium,
                color = if (enabled) colors.textPrimary else colors.textTertiary,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(1.dp))
                Text(text = subtitle, style = IosType.footnote, color = colors.textSecondary)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colors.green,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = colors.fillStrong,
                uncheckedBorderColor = Color.Transparent,
                disabledCheckedThumbColor = Color.White,
                disabledCheckedTrackColor = colors.green.copy(alpha = 0.4f),
            ),
        )
    }
}

/** 主按钮：iOS 蓝色填充、17sp 半粗、无涟漪。 */
@Composable
fun IosPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    color: Color? = null,
    onClick: () -> Unit,
) {
    val colors = LocalIosColors.current
    val fill = color ?: colors.accent
    val clickable = enabled && !loading
    Row(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (clickable) fill else fill.copy(alpha = 0.4f))
            .iosClickable(enabled = clickable, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = IosType.button,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 次按钮：填充底、蓝字（可指定红色用于危险操作）。 */
@Composable
fun IosSecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val colors = LocalIosColors.current
    val fg = tint ?: colors.accent
    Row(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.fill)
            .iosClickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = fg)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, style = IosType.button.copy(fontSize = 15.sp), color = fg)
    }
}

/** 仅有文字的 iOS 蓝色按钮。 */
@Composable
fun IosTextButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    onClick: () -> Unit,
) {
    val colors = LocalIosColors.current
    Text(
        text = text,
        style = IosType.body.copy(fontWeight = FontWeight.SemiBold),
        color = color ?: colors.accent,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .iosClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** iOS 分段控件。 */
@Composable
fun IosSegmentedControl(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelected: (Int) -> Unit,
) {
    val colors = LocalIosColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.fill)
            .padding(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (active) Modifier.background(colors.card) else Modifier)
                    .iosClickable { onSelected(index) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = IosType.footnote.copy(
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (active) colors.textPrimary else colors.textSecondary,
                )
            }
        }
    }
}

enum class IosBannerKind { Info, Warning, Error }

/** 圆角提示条。 */
@Composable
fun IosBanner(
    text: String,
    modifier: Modifier = Modifier,
    kind: IosBannerKind = IosBannerKind.Info,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalIosColors.current
    val background = when (kind) {
        IosBannerKind.Info -> colors.accentSoft
        IosBannerKind.Warning -> colors.orangeSoft
        IosBannerKind.Error -> colors.redSoft
    }
    val foreground = when (kind) {
        IosBannerKind.Info -> colors.accent
        IosBannerKind.Warning -> colors.orange
        IosBannerKind.Error -> colors.red
    }
    val icon = when (kind) {
        IosBannerKind.Info -> Icons.Filled.Info
        IosBannerKind.Warning -> Icons.Filled.Warning
        IosBannerKind.Error -> Icons.Filled.ErrorOutline
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(19.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = IosType.footnote,
            color = foreground,
            modifier = Modifier.weight(1f),
        )
        if (actionText != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = actionText,
                style = IosType.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = foreground,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .iosClickable { onAction?.invoke() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** 空状态。 */
@Composable
fun IosEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalIosColors.current
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(colors.fill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(text = title, style = IosType.headline, color = colors.textPrimary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = IosType.footnote,
            color = colors.textSecondary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

data class IosTabItem(val label: String, val icon: ImageVector)

/** iOS 底部标签栏。 */
@Composable
fun IosTabBar(
    items: List<IosTabItem>,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    val colors = LocalIosColors.current
    Column(modifier = Modifier.fillMaxWidth().background(colors.tabBar)) {
        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(54.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val active = index == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .iosClickable { onSelected(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = if (active) colors.accent else colors.textSecondary,
                        modifier = Modifier.size(23.dp),
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = item.label,
                        style = IosType.caption,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        color = if (active) colors.accent else colors.textSecondary,
                    )
                }
            }
        }
    }
}

/** 小标签（iOS 胶囊）。 */
@Composable
fun IosTagChip(text: String, tint: Color) {
    Text(
        text = text,
        style = IosType.caption.copy(fontWeight = FontWeight.Medium),
        color = tint,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(tint.copy(alpha = 0.13f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** 渐变圆角图标块。 */
@Composable
fun IosIconBadge(
    icon: ImageVector,
    gradient: List<Color>,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    tint: Color = Color.White,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.4f))
            .background(Brush.linearGradient(gradient)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** 首页方案卡片。 */
@Composable
fun IosChoiceCard(
    title: String,
    subtitle: String,
    description: String,
    tags: List<String>,
    icon: ImageVector,
    gradient: List<Color>,
    modifier: Modifier = Modifier,
    footnote: String? = null,
    onClick: () -> Unit,
) {
    val colors = LocalIosColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(colors.card)
            .border(0.5.dp, colors.cardBorder, RoundedCornerShape(22.dp))
            .iosClickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IosIconBadge(icon = icon, gradient = gradient)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = IosType.cardTitle, color = colors.textPrimary)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = IosType.footnote,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(text = description, style = IosType.callout, color = colors.textSecondary)
        if (tags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag -> IosTagChip(text = tag, tint = gradient.first()) }
            }
        }
        if (!footnote.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(text = footnote, style = IosType.caption, color = colors.textTertiary)
            }
        }
    }
}

/** iOS 填充分组输入框。 */
@Composable
fun IosTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalIosColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.fill)
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(19.dp),
            )
            Spacer(modifier = Modifier.width(9.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = TextStyle(
                fontFamily = FontFamily.Default,
                fontSize = 17.sp,
                color = if (enabled) colors.textPrimary else colors.textTertiary,
            ),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = keyboardOptions,
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            fontSize = 17.sp,
                            color = colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

/** 进度条（节奏感脉冲，导出时使用）。 */
@Composable
fun IosProgressBar(modifier: Modifier = Modifier) {
    val colors = LocalIosColors.current
    val transition = rememberInfiniteTransition(label = "ios-progress")
    val fraction by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ios-progress-fraction",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(colors.fill),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    Brush.horizontalGradient(listOf(colors.accent, colors.accent2))
                )
        )
    }
}

/** 数据小方框。 */
@Composable
fun IosStatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = LocalIosColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.fill)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(
            text = value,
            style = IosType.headline,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = label, style = IosType.caption, color = colors.textSecondary)
    }
}

/** 等宽代码/日志块。 */
@Composable
fun IosCodeBlock(text: String, modifier: Modifier = Modifier) {
    val colors = LocalIosColors.current
    val scroll = rememberScrollState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.fill)
            .heightIn(max = 220.dp)
            .verticalScroll(scroll)
            .padding(12.dp),
    ) {
        Text(
            text = text,
            style = IosType.caption.copy(fontFamily = FontFamily.Monospace),
            color = colors.textSecondary,
        )
    }
}

/** 页面底部说明文字。 */
@Composable
fun IosFooterNote(text: String, modifier: Modifier = Modifier) {
    val colors = LocalIosColors.current
    Text(
        text = text,
        style = IosType.caption,
        color = colors.textTertiary,
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}

/** 绿色勾选图标行（用于固定说明）。 */
@Composable
fun IosCheckRow(title: String, subtitle: String, modifier: Modifier = Modifier) {
    val colors = LocalIosColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = colors.green,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = IosType.body,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(text = subtitle, style = IosType.footnote, color = colors.textSecondary)
        }
        Text(
            text = "固定",
            style = IosType.caption.copy(fontWeight = FontWeight.SemiBold),
            color = colors.green,
        )
    }
}
