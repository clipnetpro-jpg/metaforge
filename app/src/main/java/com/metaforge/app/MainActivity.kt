package com.metaforge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.metaforge.ui.screens.*
import com.metaforge.ui.theme.MetaForgeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One activity, five destinations.
 *
 * The splash screen stays up until the engine has finished warming, which the
 * Application already started before this activity existed, so the first screen
 * the user touches is one where every action is immediately usable.
 */
class MainActivity : ComponentActivity() {

    private var warmed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !warmed }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            // Do not hold the splash hostage to a broken engine: after two
            // seconds the app opens anyway and reports the failure in the UI.
            val deadline = System.currentTimeMillis() + 2_000
            while (!MetaForgeApp.instance.engineReady && System.currentTimeMillis() < deadline) {
                delay(50)
            }
            warmed = true
        }

        setContent {
            MetaForgeTheme {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0B12)) {
                    MetaForgeNav()
                }
            }
        }
    }
}

@Composable
private fun MetaForgeNav() {
    val nav = rememberNavController()
    val app = MetaForgeApp.instance

    // The Application warms the engine on a background thread and reports plain
    // fields, so poll them until it settles rather than showing a stale label.
    var status by remember { mutableStateOf(app.engineStatus) }
    var ready by remember { mutableStateOf(app.engineReady) }
    LaunchedEffect(Unit) {
        // Bounded. If the engine cannot start, this used to poll every 200 ms
        // for as long as the app was open, keeping the CPU awake to re-read a
        // field that was never going to change.
        val deadline = System.currentTimeMillis() + 20_000
        while (!ready && System.currentTimeMillis() < deadline) {
            status = app.engineStatus
            ready = app.engineReady
            delay(200)
        }
        status = app.engineStatus
        ready = app.engineReady
    }

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                engineStatus = status,
                engineReady = ready,
                onInspect = { nav.navigate("inspect") },
                onTransplant = { nav.navigate("transplant") },
                onStrip = { nav.navigate("strip") },
                onDetect = { nav.navigate("detect") },
                onProfiles = { nav.navigate("profiles") },
                onDiagnostics = { nav.navigate("diagnostics") },
            )
        }
        composable("inspect") { InspectScreen(onBack = { nav.popBackStack() }) }
        composable("transplant") { TransplantScreen(onBack = { nav.popBackStack() }) }
        composable("strip") { StripScreen(onBack = { nav.popBackStack() }) }
        composable("detect") { DetectScreen(onBack = { nav.popBackStack() }) }
        composable("profiles") { ProfilesScreen(onBack = { nav.popBackStack() }) }
        composable("diagnostics") { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
    }
}
