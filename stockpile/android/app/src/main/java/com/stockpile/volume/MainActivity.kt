package com.stockpile.volume

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stockpile.volume.ar.ArScanScreen
import com.stockpile.volume.capture.CaptureScreen
import com.stockpile.volume.chain.Anchoring
import com.stockpile.volume.ui.AppTheme
import com.stockpile.volume.ui.ProjectScreen
import com.stockpile.volume.ui.ProjectsScreen
import com.stockpile.volume.ui.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        lifecycleScope.launch {
            Anchoring.messages.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
        }
        if (savedInstanceState == null) Anchoring.handleUri(this, intent?.data)

        setContent {
            AppTheme {
                val nav = rememberNavController()
                // After returning from Xaman, jump to the project being written to the ledger.
                LaunchedEffect(Unit) {
                    Anchoring.openProject.collect { id ->
                        nav.navigate("project/$id") { popUpTo("projects") }
                    }
                }
                NavHost(nav, startDestination = "projects") {
                    composable("projects") {
                        ProjectsScreen(
                            onOpen = { nav.navigate("project/$it") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { nav.popBackStack() })
                    }
                    composable("project/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                        val id = it.arguments?.getString("id").orEmpty()
                        ProjectScreen(
                            projectId = id,
                            onBack = { nav.popBackStack() },
                            onScan = { nav.navigate("scan/$id") },
                            onCapture = { nav.navigate("capture/$id") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("scan/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                        ArScanScreen(
                            projectId = it.arguments?.getString("id").orEmpty(),
                            onFinished = { nav.popBackStack() },
                            onCancel = { nav.popBackStack() },
                        )
                    }
                    composable("capture/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                        CaptureScreen(
                            projectId = it.arguments?.getString("id").orEmpty(),
                            onDone = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Anchoring.handleUri(this, intent.data)
    }
}
