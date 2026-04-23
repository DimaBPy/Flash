package com.example.flash

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.example.flash.navigation.NavGraph
import com.example.flash.navigation.Screen
import com.example.flash.nfc.NfcManager
import com.example.flash.ui.theme.FlashTheme

class MainActivity : ComponentActivity() {

    private lateinit var nfcManager: NfcManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as FlashApplication
        nfcManager = NfcManager(this)
        nfcManager.initialize(this)

        // Handle NFC intent that launched this Activity
        intent?.let { nfcManager.handleIntent(it) }

        setContent {
            val themeMode by app.themeRepository.themeMode.collectAsStateWithLifecycle()
            val onboardingShown by app.themeRepository.onboardingShown.collectAsStateWithLifecycle()
            val navController = rememberNavController()

            FlashTheme(themeMode = themeMode) {
                NavGraph(
                    navController = navController,
                    startDestination = if (onboardingShown) Screen.Main.route else Screen.Onboarding.route,
                    themeRepository = app.themeRepository,
                    transferRepository = app.transferRepository,
                    nfcManager = nfcManager
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE
            else
                0
        )
        val filters = arrayOf(
            IntentFilter(android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                try { addDataType("application/vnd.flash.handshake") } catch (_: Exception) {}
            }
        )
        nfcManager.enableForegroundDispatch(this, pendingIntent, filters)
    }

    override fun onPause() {
        super.onPause()
        nfcManager.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        nfcManager.handleIntent(intent)
    }
}
