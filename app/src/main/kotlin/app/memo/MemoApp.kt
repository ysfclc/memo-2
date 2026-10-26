package app.memo

import android.app.Application
import app.memo.runtime.MemoRuntime

/** Process-wide owner of the runtime (workspace, engine, audit, models). */
class MemoApp : Application() {
    lateinit var runtime: MemoRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = MemoRuntime(this)
    }
}
