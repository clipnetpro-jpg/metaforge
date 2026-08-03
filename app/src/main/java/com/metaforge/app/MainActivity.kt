package com.metaforge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.metaforge.engine.ExifTool
import com.metaforge.engine.PerlRuntime
import com.metaforge.ui.theme.MetaForgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var engineReady by mutableStateOf(false)
    private var report by mutableStateOf("Starting engine...")

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !engineReady }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            report = withContext(Dispatchers.IO) { selfTest() }
            engineReady = true
        }

        setContent {
            MetaForgeTheme {
                Surface(Modifier.fillMaxSize()) { HomeScreen(report) }
            }
        }
    }

    private fun selfTest(): String = buildString {
        val stamp = BuildConfig.VERSION_NAME
        appendLine("MetaForge $stamp")
        appendLine()
        val ok = PerlRuntime.ensureReady(this@MainActivity)
        appendLine("Native lib dir : ${applicationInfo.nativeLibraryDir}")
        appendLine("Perl binary    : ${if (ok) "found" else "MISSING"}")
        if (!ok) return@buildString
        appendLine("Perl version   : ${PerlRuntime.runOnce("-e", "print \"\$^V on \$^O\"")}")
        val et = ExifTool.get(this@MainActivity)
        if (et == null) {
            appendLine("ExifTool       : FAILED TO START")
            return@buildString
        }
        appendLine("ExifTool ver   : ${et.version()}")
        appendLine()
        val t0 = System.nanoTime()
        et.execute("-ver")
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        appendLine("Round trip     : %.1f ms".format(ms))
        appendLine()
        appendLine("Supported file types:")
        appendLine(et.execute("-listf").stdout.take(600))
    }
}

@Composable
private fun HomeScreen(report: String) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale",
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0B0B12), Color(0xFF161427), Color(0xFF0B0B12))
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Text(
            "MetaForge",
            fontSize = 34.sp,
            fontWeight = FontWeight.Black,
            color = Color(0xFF22D3EE),
            modifier = Modifier.scale(scale),
        )
        Text(
            "metadata engine self-test",
            fontSize = 13.sp,
            color = Color(0xFF8B8BA7),
        )
        Spacer(Modifier.height(28.dp))
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF14141F)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                report,
                modifier = Modifier.padding(18.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Color(0xFFD7D7EA),
                lineHeight = 18.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
