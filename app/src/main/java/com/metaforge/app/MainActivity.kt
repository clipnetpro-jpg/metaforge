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

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                engineStatus = app.engineStatus,
                engineReady = app.engineReady,
                onInspect = { nav.navigate("inspect") },
                onTransplant = { nav.navigate("transplant") },
                onStrip = { nav.navigate("strip") },
                onDetect = { nav.navigate("detect") },
                onDiagnostics = { nav.navigate("diagnostics") },
            )
        }
        composable("inspect") { InspectScreen(onBack = { nav.popBackStack() }) }
        composable("transplant") { TransplantScreen(onBack = { nav.popBackStack() }) }
        composable("strip") { StripScreen(onBack = { nav.popBackStack() }) }
        composable("detect") { DetectScreen(onBack = { nav.popBackStack() }) }
        composable("diagnostics") { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
    }
}
