package app.memo.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.memo.ui.motion.MemoMotion
import app.memo.ui.motion.MotionFamily
import app.memo.ui.motion.ambientLoopSpec
import app.memo.ui.motion.motionSpec
import app.memo.ui.motion.rememberReduceMotion
import app.memo.ui.theme.MemoColorScheme
import app.memo.ui.theme.MemoTheme
import kotlin.math.min

/** The conversational state the orb shows. */
enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING, WORKING, WAITING }

/** Colour role for each state. Read from the theme, never a literal. */
internal fun orbTint(state: OrbState, colors: MemoColorScheme): Color = when (state) {
    OrbState.IDLE -> colors.accent.copy(alpha = 0.6f)
    OrbState.LISTENING -> colors.accent
    OrbState.THINKING -> colors.link
    OrbState.SPEAKING -> colors.success
    OrbState.WORKING -> colors.link
    OrbState.WAITING -> colors.attention
}

/**
 * A breathing gradient blob that reflects Memo's state. [level] (0..1) is the mic or speech
 * amplitude; it swells the orb and is eased so it never jumps. On API 33 and later the orb is
 * drawn with an AGSL [RuntimeShader]; earlier versions get a radial gradient on Canvas. With
 * reduce-motion on, it holds still at its resting size.
 */
@Composable
fun PresenceOrb(
    state: OrbState,
    level: Float,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
) {
    val colors = MemoTheme.colors
    val tint = orbTint(state, colors)
    val reduced = rememberReduceMotion()
    val target = level.coerceIn(0f, 1f)
    val amplitude by animateFloatAsState(
        targetValue = target,
        animationSpec = motionSpec(MotionFamily.SMOOTH, reduced),
        label = "memo-orb-level",
    )
    val sized = modifier
        .size(size)
        .semantics { this.contentDescription = "$contentDescription, ${state.name.lowercase()}" }
    if (reduced) {
        OrbFrame(sized, tint, amplitude, breath = 1f, time = 0f)
    } else {
        AnimatedOrb(sized, tint, amplitude)
    }
}

@Composable
private fun AnimatedOrb(modifier: Modifier, tint: Color, level: Float) {
    val transition = rememberInfiniteTransition(label = "memo-orb")
    val breath by transition.animateFloat(
        initialValue = MemoMotion.ORB_MIN_SCALE,
        targetValue = MemoMotion.ORB_MAX_SCALE,
        animationSpec = ambientLoopSpec(MemoMotion.ORB_BREATH_MS, RepeatMode.Reverse),
        label = "memo-orb-breath",
    )
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = ambientLoopSpec(MemoMotion.ORB_FLOW_MS, RepeatMode.Restart),
        label = "memo-orb-time",
    )
    OrbFrame(modifier, tint, level, breath, time)
}

@Composable
private fun OrbFrame(modifier: Modifier, tint: Color, level: Float, breath: Float, time: Float) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ShaderOrb(modifier, tint, level, breath, time)
    } else {
        GradientOrb(modifier, tint, level, breath)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderOrb(modifier: Modifier, tint: Color, level: Float, breath: Float, time: Float) {
    val shader = remember { RuntimeShader(ORB_AGSL) }
    Box(
        modifier.drawBehind {
            shader.setFloatUniform("iResolution", size.width, size.height)
            shader.setFloatUniform("iTime", time)
            shader.setFloatUniform("iLevel", level)
            shader.setFloatUniform("iBreath", breath / MemoMotion.ORB_MAX_SCALE)
            shader.setFloatUniform("iColor", tint.red, tint.green, tint.blue)
            drawRect(brush = ShaderBrush(shader))
        },
    )
}

@Composable
private fun GradientOrb(modifier: Modifier, tint: Color, level: Float, breath: Float) {
    Canvas(modifier) {
        val half = min(size.width, size.height) / 2f
        val radius = (half * 0.62f * (breath / MemoMotion.ORB_MAX_SCALE) * (1f + 0.3f * level)).coerceAtMost(half)
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tint.copy(alpha = 0.95f), tint.copy(alpha = 0.4f), tint.copy(alpha = 0f)),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
}

/**
 * The orb's fragment shader. Uniforms: iResolution, iTime, iLevel (0..1), iBreath (0..1, the
 * breath scale over its maximum) and iColor. Output is premultiplied so the edge fades to clear.
 */
private const val ORB_AGSL = """
uniform float2 iResolution;
uniform float iTime;
uniform float iLevel;
uniform float iBreath;
uniform float3 iColor;

half4 main(float2 fragCoord) {
    float2 uv = (fragCoord - 0.5 * iResolution) / min(iResolution.x, iResolution.y);
    float dist = length(uv);
    float angle = atan(uv.y, uv.x);
    float wobble = 0.025 * sin(iTime * 0.006 * 6.2832 + angle * 3.0) * (0.4 + iLevel);
    float radius = (0.22 + 0.10 * iBreath) * (1.0 + 0.3 * iLevel) + wobble;
    float edge = 1.0 - smoothstep(radius * 0.6, radius, dist);
    float shade = 0.75 + 0.25 * (1.0 - min(dist / max(radius, 0.001), 1.0));
    float3 col = iColor * shade;
    return half4(col * edge, edge);
}
"""
