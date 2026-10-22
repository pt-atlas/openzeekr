package com.openzeekr.app.auto

import kotlinx.coroutines.flow.MutableStateFlow

/** Set when a notification tap should open the Automations screen; the UI clears it once shown. */
object AutomationNav {
    val openRequested = MutableStateFlow(false)
}
