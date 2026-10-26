package app.memo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.memo.ui.MemoRoot
import app.memo.ui.MemoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtime = (application as MemoApp).runtime
        setContent {
            MemoTheme {
                MemoRoot(runtime)
            }
        }
    }
}
