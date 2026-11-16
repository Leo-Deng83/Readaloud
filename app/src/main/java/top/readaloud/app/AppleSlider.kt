package top.readaloud.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * **Apple 风格的滑条**（Leo 说 Material 默认那个"太丑"，要求参考 Apple 设计语言）。
 *
 * ## 为什么完全自己画，而不去改 Material3 的 Slider
 *
 * 试过 `Slider(track = …, thumb = …)` 插槽改造，**做不到**：
 * M3 内部的 thumb 用 `Layout` 分配固定的 touchTargetSize（48dp 触摸目标），
 * 无论传 `size()` 还是 `requiredSize()`，圆钮都会被**纵向压扁成椭圆** ——
 * 肉眼看整体没问题，**放大截图才看出来**（这个坑查了两轮才定位）。
 *
 * 与其跟它的内部约束较劲，不如自己实现：这个滑条的需求其实很简单
 * （一条线 + 一个圆钮 + 拖动），自己画还能完全按 Apple 的视觉规范来。
 *
 * ## Apple HIG 的视觉要点（与 Material 3 的差别）
 *
 * | | Material 3 | Apple HIG |
 * |---|---|---|
 * | 轨道 | 粗（~16dp）、两端方形 | **很细**（~4dp）、**全圆角** |
 * | 拇指 | 宽胶囊，与轨道同高 | **白色圆钮**，明显大于轨道，带柔和阴影 |
 * | 刻度 | 有时显示密集刻度 | **不显示刻度** |
 * | 按下反馈 | 颜色变化 | **圆钮轻微放大**（1.0→1.14）+ 阴影加深 |
 * | 触感 | 无 | **每过一档给一次轻震**（iOS 的"咔哒"感） |
 *
 * ## 自己实现踩到的四件事
 *
 * 1. **触摸目标必须够大**：视觉圆钮 26dp，但可点/可拖区域要≥48dp（无障碍要求）。
 * 2. **两端不能把圆钮拖出屏幕**：拇指中心要夹在 `[半径, 宽度-半径]`。
 * 3. **圆钮要用带 `shadow` 的 Box，不能在 Canvas 里画圆** —— Canvas 画不出柔和阴影。
 * 4. **圆钮定位要用 `onSizeChanged` 拿到实测宽度**再 `offset`：
 *    写死宽度会在"左右有 padding / 不同机型"时错位（这类 bug 在固定机型上测不出来）。
 *
 * @param steps 相邻两档之间的格数。98 格 → 1~99 sp 每一 sp 一档
 */
@Composable
fun AppleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    accent: Color = Color(0xFF2B5CFF),
    trackHeight: Dp = 4.dp,
    thumbSize: Dp = 26.dp
) {
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span > 0f) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f

    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val vibrator = remember(context) { vibratorOf(context) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // 拖动时跟手的临时值；松手才真正改 value（否则拖一下写几十次存储）
    var dragging by remember { mutableFloatStateOf(fraction) }
    val shown = if (pressed) dragging else fraction

    val thumbScale by animateFloatAsState(
        targetValue = if (pressed) 1.14f else 1f,
        label = "thumbScale"
    )

    // 轨道两端各留一点余量：圆钮半径 + 一点呼吸空间
    val trackPadPx = with(density) { thumbSize.toPx() / 2f + with(density) { 2.dp.toPx() } }
    val thumbPx = with(density) { thumbSize.toPx() }
    val trackPx = with(density) { trackHeight.toPx() }

    // ④ 用实测宽度定位圆钮（不写死，避免不同机型/ padding 下错位）
    var boxWidthPx by remember { mutableIntStateOf(0) }
    var boxHeightPx by remember { mutableIntStateOf(0) }
    val usablePx = (boxWidthPx - trackPadPx * 2).coerceAtLeast(1f)

    // 每一档震一下（iOS 的"咔哒"）
    var lastTick = remember { value.roundToInt() }
    fun report(f: Float) {
        val raw = valueRange.start + f * span
        val stepSize = if (steps > 0) span / (steps + 1) else 0f
        val snapped = if (stepSize > 0f) {
            valueRange.start + (raw / stepSize).roundToInt() * stepSize
        } else raw
        val t = snapped.roundToInt()
        if (t != lastTick) {
            lastTick = t
            // ⚠️ 之前用 haptics.performHapticFeedback(TextHandleMove)，
            //    **手机上完全没有感觉**（实测）。原因不是硬件问题 ——
            //    荣耀 9X 的震动器工作正常（dumpsys vibrator 能看到别的 App 在触发），
            //    而是 TextHandleMove 映射的是"极轻触反馈"，本机
            //    `mHapticFeedbackIntensity=2`（弱档），轻到察觉不到。
            //    改用 Vibrator 的 EFFECT_TICK/CLICK —— 这是系统"时钟/滚动"那类
            //    明确要让人感知的场景用的效果，强度高一档。
            tick(vibrator, haptics)
        }
        onValueChange(snapped.coerceIn(valueRange.start, valueRange.endInclusive))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 触摸目标高度 = 拇指直径的两倍（≥48dp 无障碍要求），比视觉高度大得多
            .height(thumbSize * 2)
            .onSizeChanged { boxWidthPx = it.width; boxHeightPx = it.height }
            .semantics { contentDescription = "字号 ${value.roundToInt()} sp" }
            .pointerInput(valueRange, steps, boxWidthPx) {
                // x 坐标 → 0~1。夹在 [半径, 宽度-半径]，圆钮才不会被拖出屏幕
                fun posToFraction(x: Float): Float {
                    val usable = size.width - trackPadPx * 2
                    if (usable <= 0f) return 0f
                    return ((x - trackPadPx) / usable).coerceIn(0f, 1f)
                }
                detectTapGestures(
                    onPress = { offset ->
                        dragging = posToFraction(offset.x)
                        report(dragging)
                        tryAwaitRelease()
                    }
                )
            }
            .pointerInput(valueRange, steps, boxWidthPx) {
                fun posToFraction(x: Float): Float {
                    val usable = size.width - trackPadPx * 2
                    if (usable <= 0f) return 0f
                    return ((x - trackPadPx) / usable).coerceIn(0f, 1f)
                }
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = posToFraction(offset.x)
                        report(dragging)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragging = posToFraction(change.position.x)
                        report(dragging)
                    },
                    onDragEnd = { dragging = fraction },
                    onDragCancel = { dragging = fraction }
                )
            },
        // ⚠️ 必须 TopStart：圆钮要用绝对坐标定位。
        //    若用 Center 对齐再 `offset`，位置会变成"居中位置 + 偏移"（叠加），
        //    圆钮会跑到轨道中段（实测：进度 17% 时圆钮出现在 55% 处）。
        contentAlignment = Alignment.TopStart
    ) {
        // ── 轨道 ──
        // ⚠️ Canvas 必须 **fillMaxSize**（撑满整个 52dp 的盒子），不能是 height(thumbSize)。
        //    外层已改成 TopStart 对齐，Canvas 只有跟圆钮一样占满整盒，两者中心才在同一条线上。
        //    （踩过：Canvas 用 height=thumbSize → 轨道贴在盒子顶部，
        //      而圆钮按整盒居中 → 圆钮看起来落在轨道**下方**。）
        Canvas(modifier = Modifier.fillMaxSize()) {
            val left = trackPadPx
            val usable = usablePx
            val cy = size.height / 2f
            val r = CornerRadius(trackPx / 2f, trackPx / 2f)
            // 底：整条浅灰
            drawRoundRect(
                color = Color(0xFFE3E3E8),
                topLeft = Offset(left, cy - trackPx / 2f),
                size = Size(usable, trackPx),
                cornerRadius = r
            )
            // 上：已走过的主色段
            if (shown > 0f) {
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(left, cy - trackPx / 2f),
                    size = Size(usable * shown, trackPx),
                    cornerRadius = r
                )
            }
        }

        // ── 圆钮（白圆 + 柔和阴影 + 极细描边）──────────────
        Box(
            modifier = Modifier
                .offset {
                    val x = trackPadPx + usablePx * shown - thumbPx / 2f
                    // y 要自己算垂直居中（外层已改成 TopStart 对齐）
                    val y = (boxHeightPx - thumbPx) / 2f
                    IntOffset(x.roundToInt(), y.roundToInt())
                }
                .size(thumbSize)
                .scale(thumbScale)
                .shadow(
                    elevation = if (pressed) 6.dp else 3.dp,
                    shape = CircleShape,
                    ambientColor = Color(0x2E000000),
                    spotColor = Color(0x2E000000)
                )
                .background(Color.White, CircleShape)
                // 极细描边：纯白圆钮在白底上会"消失"（iOS 白色滑块也带这一圈）
                .border(0.5.dp, Color(0x1F000000), CircleShape)
                .alpha(if (pressed) 0.95f else 1f)
        )
    }
}

/**
 * 取系统的 [Vibrator]。
 *
 * ⚠️ Android 12（API 31）起`VibratorManager` 取代了旧的 `getSystemService(VIBRATOR_SERVICE)`，
 *    旧写法在新版本上返回 null（拿到就等于"没震动"）—— 用版本分支处理。
 */
private fun vibratorOf(context: Context): Vibrator? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        mgr?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
}.getOrNull()

/**
 * 滑条每过一档的"咔哒"震感。
 *
 * 依次尝试，拿到哪个用哪个（保证在各种设备上都有反馈）：
 * 1. `VibrationEffect.createPredefined(EFFECT_TICK)` —— Android 11+ 的标准 tick，Clearest
 * 2. `EFFECT_CLICK` —— 次选，稍重一点
 * 3. `performOneShot(10ms, 60)` —— 老系统的兜底
 * 4. `performHapticFeedback` —— 连 Vibrator 都没有时（部分模拟器/定制 ROM）
 *
 * @param enabled 系统里"触感反馈"没关时才有效果；
 *                用户在系统设置里关了振动，`hasVibrator` 会返回 false → 静默跳过。
 */
private fun tick(vibrator: Vibrator?, haptics: androidx.compose.ui.hapticfeedback.HapticFeedback) {
    if (vibrator != null && vibrator.hasVibrator()) {
        val effect = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            else -> null
        }
        // ⚠️ 两个写法上的坑（都踩过）：
        //   1. 不用 `runCatching{...}.onSuccess { return }` —— onSuccess 的 lambda
        //      返回类型推断不出来（编译器报 "Cannot infer type R"）。
        //   2. 不用废弃的 `vibrate(millis, amplitude)` 重载 —— 它和别的重载一起
        //      会让推断失败。一律走 `VibrationEffect` 单参数版本，最干净。
        if (effect != null && runCatching { vibrator.vibrate(effect); Unit }.isSuccess) return
        val fallback = VibrationEffect.createOneShot(10L, 60)
        if (runCatching { vibrator.vibrate(fallback); Unit }.isSuccess) return
    }
    // 兜底：系统的触感反馈（受系统"触感"开关控制）
    runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
}
