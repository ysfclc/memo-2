package app.memo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal but real startup shell for M0: proves the CI pipeline produces an
 * installable APK. M1 replaces this screen with the full Memo UI shell
 * (Live / Talk / Work / Studio / Browser / Approvals / Models / Settings)
 * on the unified Paper+Mint design system.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("?")
        setContent { MemoSplash(version) }
    }
}

@Composable
private fun MemoSplash(version: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(paperBackground)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(evergreen),
        )
        Spacer(Modifier.height(14.dp))
        Spacer(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(mint),
        )
        Spacer(Modifier.height(22.dp))
        Text(
            "memo",
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ink,
            letterSpacing = (-0.6).sp,
        )
        Spacer(Modifier.height(6.dp))
        Text("Your private execution environment.", fontSize = 14.sp, color = mutedInk)
        Spacer(Modifier.height(10.dp))
        Text(
            "v$version · pipeline bootstrap OK",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = mutedInk,
        )
    }
}

// Seed tokens; the canonical source after M1 is app/ui/MemoTheme.kt.
private val paperBackground = Color(0xFFF4F2EC)
private val evergreen = Color(0xFF26524A)
private val mint = Color(0xFFB2EFCF)
private val ink = Color(0xFF222920)
private val mutedInk = Color(0xFF6F776B)
