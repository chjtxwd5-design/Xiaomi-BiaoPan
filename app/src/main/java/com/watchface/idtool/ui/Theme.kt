package com.watchface.idtool.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watchface.idtool.R

// ====================================================================
// 字体（保留原 Barlow / 几何黑体，全语言覆盖）
// ====================================================================

val AppFonts = FontFamily(
    Font(R.font.barlow_regular, weight = FontWeight.Normal),
    Font(R.font.barlow_medium, weight = FontWeight.Medium),
    Font(R.font.barlow_medium, weight = FontWeight.SemiBold),
    Font(R.font.barlow_regular, weight = FontWeight.Bold)
)

/** 数字/ID 专用：窄长机械数字（表盘 ID、版本号、计数器） */
val NumericFonts = FontFamily(
    Font(R.font.barlow_regular, weight = FontWeight.Normal),
    Font(R.font.barlow_medium, weight = FontWeight.Medium)
)

// ====================================================================
// iOS 设计令牌（Design Tokens）· Tomoyo Sakagami 壁纸深度适配
//
// 全部语义色从壁纸取色（WE 工坊 2784334826 schemecolor 0.459/0.522/0.698）：
//   冰蓝发色高光 #BFD2F2 / 蓝灰主色 #7585B2 / 眼瞳深蓝 #4A6FA5 / 雪白 #F6F8FC
//
// 层级模型（从下到上）：
//   groupedBackground  #EDF1F9  雪白微蓝底（壁纸雪景色）
//   card               玻璃面板（液体玻璃，非实心）
//   separator          #DCE3F0  蓝灰分割线
//   label              #1F2A44  墨蓝主文本（原纯黑改壁纸暗部色）
//   secondaryLabel     #6B7A9B  蓝灰次级文本
//   tint               #4A6FA5  眼瞳蓝（高亮，原 #007AFF 改壁纸同系）
//   capsuleSelected    #E3EAF8  选中胶囊底
// ====================================================================

object IOSPalette {

    // ---- 层级底色（壁纸雪景色系） ----
    /** 页面底色（壁纸雪景微蓝白） */
    val groupedBackground = Color(0xFFEDF1F9)

    /** 卡片底色（玻璃面板降级兜底） */
    val card = Color(0xFFF8FAFE)

    /** 次级卡片 / 嵌插槽位底（如输入框、代码块） */
    val cardSecondary = Color(0xFFF3F6FC)

    /** TabBar 胶囊底 */
    val tabBar = Color(0xFFF8FAFE)

    /** TabBar 选中胶囊底（壁纸蓝灰的半透明光效） */
    val capsuleSelected = Color(0x2E7585B2)

    // ---- 分割线 ----
    /** 项间 1px 分割线 */
    val separator = Color(0xFFDCE3F0)

    /** 卡片内缩进后的分割线起点留白后的视觉变体 */
    val separatorSoft = Color(0xFFE8EDF7)

    // ---- 文本（壁纸暗部墨蓝色系，对比度仍达 AA） ----
    val label = Color(0xFF1F2A44)
    val secondaryLabel = Color(0xFF6B7A9B)
    val tertiaryLabel = Color(0xFFA8B4CC)

    // ---- 高亮（壁纸眼瞳蓝色系） ----
    val tint = Color(0xFF4A6FA5)
    val destructive = Color(0xFFE0554D)
    val success = Color(0xFF3FA97C)
    val warning = Color(0xFFE09A3E)

    // ---- 彩色图标瓦片底（统一到壁纸蓝灰家族 + 少量对比色）----
    val tileRed = Color(0xFFE0554D)
    val tileOrange = Color(0xFFE09A3E)
    val tileYellow = Color(0xFFE5C05A)
    val tileGreen = Color(0xFF3FA97C)
    val tileBlue = Color(0xFF4A6FA5)
    val tilePurple = Color(0xFF8B7FC7)
    val tileTeal = Color(0xFF4E9FB8)
    val tileIndigo = Color(0xFF6272BE)
    val tileGray = Color(0xFF8E9AB5)
    val purple = Color(0xFF8B7FC7)

    /** 瓦片内图标统一白色 */
    val tileGlyph = Color(0xFFFFFFFF)

    // ---- 壁纸取色扩展令牌（供玻璃/特效组件使用） ----
    /** 冰蓝发色高光 */
    val wallpaperIceBlue = Color(0xFFBFD2F2)

    /** 蓝灰主色（schemecolor） */
    val wallpaperBlueGray = Color(0xFF7585B2)

    /** 雪白高光 */
    val wallpaperSnow = Color(0xFFF6F8FC)

    /** 雾紫暗部 */
    val wallpaperMistPurple = Color(0xFFD4D2EF)

    // ---- 交互 ----
    /** 按下时整体透明度（iOS Active state，26 风格更轻透） */
    val pressedOpacity = 0.65f
    val pressedOpacityStrong = 0.45f

    // ---- 圆角 ----
    /** InsetGrouped 卡片圆角（iOS 26 连续大圆角观感） */
    val cardRadius = 26.dp
    /** ListItem 高亮块圆角 */
    val rowRadius = 12.dp
    /** 彩色图标瓦片圆角 */
    val tileRadius = 9.dp
    /** TabBar 胶囊圆角 */
    val tabBarRadius = 28.dp
    /** 圆形搜索按钮半径（直径 52dp → 26dp） */
    val fabRadius = 26.dp
}

// ====================================================================
// Material3 颜色方案（映射到 iOS 令牌）
// ====================================================================

private val IOSLightColors = lightColorScheme(
    primary = IOSPalette.tint,
    onPrimary = Color.White,
    primaryContainer = IOSPalette.capsuleSelected,
    onPrimaryContainer = IOSPalette.tint,
    secondary = IOSPalette.secondaryLabel,
    onSecondary = Color.White,
    secondaryContainer = IOSPalette.cardSecondary,
    onSecondaryContainer = IOSPalette.label,
    tertiary = IOSPalette.purple,
    onTertiary = Color.White,
    tertiaryContainer = IOSPalette.cardSecondary,
    onTertiaryContainer = IOSPalette.label,
    error = IOSPalette.destructive,
    onError = Color.White,
    errorContainer = Color(0xFFFBE9E8),
    onErrorContainer = IOSPalette.destructive,
    background = IOSPalette.groupedBackground,
    onBackground = IOSPalette.label,
    surface = IOSPalette.card,
    onSurface = IOSPalette.label,
    surfaceVariant = IOSPalette.cardSecondary,
    onSurfaceVariant = IOSPalette.secondaryLabel,
    outline = IOSPalette.separator,
    outlineVariant = IOSPalette.separator
)

// ====================================================================
// 排版（iOS 规格：17pt 大标题 / 17pt 正文 / 13pt 次级 / 11pt 脚注）
// ====================================================================

private fun iosTextStyle(
    weight: FontWeight,
    size: Int,
    line: Int,
    spacing: Float = 0f
) = TextStyle(
    fontFamily = AppFonts,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp
)

private val IOSTypography = Typography(
    displayLarge = iosTextStyle(FontWeight.Bold, 32, 38, -0.4f),
    displayMedium = iosTextStyle(FontWeight.Bold, 28, 34, -0.3f),
    displaySmall = iosTextStyle(FontWeight.Bold, 24, 30, -0.2f),
    headlineLarge = iosTextStyle(FontWeight.SemiBold, 22, 28, -0.2f),
    headlineMedium = iosTextStyle(FontWeight.SemiBold, 20, 26, -0.2f),
    headlineSmall = iosTextStyle(FontWeight.SemiBold, 17, 22),
    titleLarge = iosTextStyle(FontWeight.Medium, 17, 22, -0.2f),
    titleMedium = iosTextStyle(FontWeight.Medium, 16, 21, -0.1f),
    titleSmall = iosTextStyle(FontWeight.Medium, 15, 20, -0.1f),
    bodyLarge = iosTextStyle(FontWeight.Normal, 17, 22, -0.2f),
    bodyMedium = iosTextStyle(FontWeight.Normal, 15, 20, -0.1f),
    bodySmall = iosTextStyle(FontWeight.Normal, 13, 18),
    labelLarge = iosTextStyle(FontWeight.Medium, 15, 20, -0.1f),
    labelMedium = iosTextStyle(FontWeight.Normal, 13, 18, -0.05f),
    labelSmall = iosTextStyle(FontWeight.Normal, 11, 14, 0.06f)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/**
 * iOS 浅色主题（固定浅色，参照系统「设置」App）。
 */
@Composable
fun WatchFaceTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = IOSLightColors,
        typography = IOSTypography,
        shapes = AppShapes,
        content = content
    )
}
