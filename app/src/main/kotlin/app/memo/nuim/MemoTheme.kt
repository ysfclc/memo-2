package app.memo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Paper = Color(0xFFF4F2EC)
private val Ink = Color(0xFF222920)
private val Evergreen = Color(0xFF26524A)
private val Mint = Color(0xFFB2EFCF)
private val Amber = Color(0xFFB25A24)

@Composable
fun MemoTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (darkTheme) {
        darkColorScheme(
            primary = Mint,
            onPrimary = Ink,
            secondary = Evergreen,
            background = Color(0xFF17201B),
            surface = Color(0xFF202A24),
            onBackground = Paper,
            onSurface = Paper,
            error = Amber,
        )
    } else {
        lightColorScheme(
            primary = Evergreen,
            onPrimary = Color.White,
            secondary = Mint,
            background = Paper,
            surface = Color(0xFFFFFEFA),
            onBackground = Ink,
            onSurface = Ink,
            error = Amber,
        )
    }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 32.sp),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            labelSmall = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
        ),
        content = content,
    )
}
