package com.openzeekr.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.openzeekr.app.auto.AutoNotifier
import com.openzeekr.app.auto.AutomationNav
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.openzeekr.app.ui.AppRoot
import com.openzeekr.app.ui.theme.OpenZeekrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val deps = (application as App).deps
        handleAutomationIntent(intent)
        setContent {
            OpenZeekrTheme {
                AppRoot(deps)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutomationIntent(intent)
    }

    /** An automation notification was tapped: ask the UI to open the Automations screen. */
    private fun handleAutomationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(AutoNotifier.EXTRA_OPEN_AUTOMATIONS, false) == true) {
            AutomationNav.openRequested.value = true
            intent.removeExtra(AutoNotifier.EXTRA_OPEN_AUTOMATIONS)
        }
    }
}
