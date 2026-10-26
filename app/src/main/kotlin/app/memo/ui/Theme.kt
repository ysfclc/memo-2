package app.memo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Paper + Mint design tokens. The single palette for the whole app. */
object MemoColors {
    val Paper = Color(0xFFF4F2EC)
    val Ink = Color(0xFF222920)
    val MutedInk = Color(0xFF6F776B)
    val Evergreen = Color(0xFF26524A)
    val Mint = Color(0xFFB2EFCF)
    val Amber = Color(0xFFB25A24)
    val Surface = Color(0xFFFFFEFA)
    val Hairline = Color(0xFFE2DED2)
}

@Composable
fun MemoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) {
        darkColorScheme(
            primary = MemoColors.Mint,
            onPrimary = MemoColors.Ink,
            secondary = MemoColors.Evergreen,
            background = Color(0xFF17201B),
            surface = Color(0xFF202A24),
            onBackground = MemoColors.Paper,
            onSurface = MemoColors.Paper,
            error = MemoColors.Amber,
        )
    } else {
        lightColorScheme(
            primary = MemoColors.Evergreen,
            onPrimary = Color.White,
            secondary = MemoColors.Mint,
            background = MemoColors.Paper,
            surface = MemoColors.Surface,
            onBackground = MemoColors.Ink,
            onSurface = MemoColors.Ink,
            error = MemoColors.Amber,
        )
    }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 30.sp),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            labelSmall = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
        ),
        content = content,
    )
}
