package com.metaforge.app

import android.app.Application
import android.util.Log
import com.metaforge.engine.ExifTool
import com.metaforge.engine.PerlRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Starts the metadata engine while the splash screen is still on screen.
 *
 * Unpacking the Perl tree and booting the ExifTool daemon costs about a second
 * on first launch and a fraction of that afterwards. Doing it here means the
 * user never waits for it later: by the time they have picked a file, the engine
 * is already answering in tens of milliseconds.
 */
class MetaForgeApp : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var engineReady: Boolean = false
        private set
    @Volatile var engineStatus: String = "starting"
        private set
    @Volatile var warmupMs: Long = 0
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        scope.launch {
            val t0 = System.nanoTime()
            val stamp = BuildConfig.VERSION_NAME
            val ok = PerlRuntime.ensureReady(this@MetaForgeApp, stamp)
            if (!ok) {
                engineStatus = "runtime unpack failed"
                return@launch
            }
            val et = ExifTool.get(this@MetaForgeApp, stamp)
            warmupMs = (System.nanoTime() - t0) / 1_000_000
            engineReady = et != null
            engineStatus = when {
                et == null -> "engine unavailable"
                else -> "ExifTool ${et.version()} (${et.mode}) in ${warmupMs} ms"
            }
            Log.i("MetaForgeApp", engineStatus)
        }
    }

    companion object {
        @Volatile lateinit var instance: MetaForgeApp
            private set
    }
}
