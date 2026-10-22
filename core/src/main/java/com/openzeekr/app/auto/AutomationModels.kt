package com.openzeekr.app.auto

import kotlinx.serialization.Serializable

/**
 * Settings and runtime state for the on-phone automations (auto-sentry, auto-cool, lock guard).
 *
 * Everything here is plain data with no Android dependency, so [AutomationEngine] can be unit-tested
 * on the JVM. Persisted as JSON by [AutomationStore].
 */

/** A place where the car is considered safe (home, office...): auto-sentry does not arm here. */
@Serializable
data class SafeZone(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Int = DEFAULT_RADIUS_M,
) {
    companion object {
        const val DEFAULT_RADIUS_M = 150
    }
}

@Serializable
data class AutoSentryConfig(
    val enabled: Boolean = false,
    val zones: List<SafeZone> = emptyList(),
    /** Minutes the car must sit parked outside every safe zone before sentry is armed. */
    val armAfterMin: Int = 3,
    /** Also lock the doors (if they are unlocked and closed) when sentry is armed. */
    val lockWhenArming: Boolean = true,
    /** Turn sentry off again if it was armed by this app and the car turns out to be in a safe zone. */
    val disarmInSafeZone: Boolean = true,
)

@Serializable
data class AutoCoolConfig(
    val enabled: Boolean = false,
    /** Start cooling when the cabin is at or above this temperature (°C). */
    val triggerTempC: Double = 38.0,
    /** A/C target temperature sent to the car (°C). */
    val targetTempC: Double = 22.0,
    /** How long each cooling run lasts (minutes). The car stops the A/C itself after this. */
    val runMinutes: Int = 10,
    /** Minimum wait after a run ends before the next one may start (minutes). */
    val gapMinutes: Int = 20,
    /** Never start cooling below this battery level (%), and stop a run that drops below it. */
    val minSocPct: Int = 40,
    /** Maximum runs per parking stop; 0 = unlimited. */
    val maxRunsPerPark: Int = 0,
    /** Active hours (local time, 0-24). from == to, or 0..24, means all day. Wraps past midnight if from > to. */
    val activeFromHour: Int = 0,
    val activeToHour: Int = 24,
    /** Also cool while parked inside a safe zone (e.g. a hot carport at home). */
    val alsoInSafeZones: Boolean = true,
)

@Serializable
data class LockGuardConfig(
    /** Lock the car if it has been left unlocked while parked. */
    val autoLockEnabled: Boolean = false,
    val autoLockAfterMin: Int = 5,
    /** Auto-lock in safe zones too (turn off if you leave the car unlocked at home on purpose). */
    val autoLockInSafeZones: Boolean = true,
    /** Notify when a door, trunk, hood, window or sunroof stays open while parked. */
    val openAlertEnabled: Boolean = false,
    val openAlertAfterMin: Int = 10,
    /** Close windows / sunroof automatically when parked and left open. */
    val autoCloseWindows: Boolean = false,
    val autoCloseAfterMin: Int = 10,
    /** Only close them when rain is forecast at the car's location in the next hour. */
    val closeOnlyIfRain: Boolean = true,
)

@Serializable
data class AutomationConfig(
    val sentry: AutoSentryConfig = AutoSentryConfig(),
    val cool: AutoCoolConfig = AutoCoolConfig(),
    val lock: LockGuardConfig = LockGuardConfig(),
    /** Normal check interval while the car is parked (minutes). */
    val parkedCheckMin: Int = 15,
    /** Post a notification whenever an automation sends a command to the car. */
    val notifyOnActions: Boolean = true,
    /** Sign in again in the background when the session expires (uses the saved login). */
    val autoRelogin: Boolean = true,
) {
    val anyEnabled: Boolean
        get() = sentry.enabled || cool.enabled || lock.autoLockEnabled || lock.openAlertEnabled || lock.autoCloseWindows
}

@Serializable
data class LogEntry(val timeMs: Long, val text: String)

/** What the automations remember between checks. Reset (mostly) whenever the car drives off. */
@Serializable
data class AutomationState(
    val parkedSinceMs: Long? = null,
    val parkLat: Double? = null,
    val parkLng: Double? = null,
    /** True while sentry is on because this app turned it on. */
    val sentryArmedByUs: Boolean = false,
    val sentryAttempts: Int = 0,
    val lastSentryAttemptMs: Long? = null,
    val sentryFailNotified: Boolean = false,
    val coolRunsThisPark: Int = 0,
    val coolStartedMs: Long? = null,
    val coolNextAllowedMs: Long = 0,
    val coolLowSocNotified: Boolean = false,
    val unlockedSinceMs: Long? = null,
    val lockAttempts: Int = 0,
    val unlockedOpenNotified: Boolean = false,
    val openSinceMs: Long? = null,
    val openAlertKey: String? = null,
    val closeAttempts: Int = 0,
    val lastCheckMs: Long? = null,
    val lastRunSummary: String? = null,
    val lastReloginMs: Long = 0,
    val log: List<LogEntry> = emptyList(),
) {
    fun withLog(timeMs: Long, text: String): AutomationState =
        copy(log = (listOf(LogEntry(timeMs, text)) + log).take(MAX_LOG))

    companion object {
        const val MAX_LOG = 60
    }
}

/** One reading of the car, reduced to what the automations need. null = not reported. */
data class CarSnapshot(
    val nowMs: Long,
    /** Phone's local hour of day (0-23), for the auto-cool active hours. */
    val localHour: Int,
    /** true = engine off / parked, false = car on or driving, null = unknown. */
    val parked: Boolean?,
    val lat: Double?,
    val lng: Double?,
    val socPct: Double?,
    val interiorTempC: Double?,
    val acOn: Boolean?,
    val locked: Boolean?,
    /** Human-readable names of open doors / trunk / hood, e.g. "driver door", "trunk". */
    val openDoors: List<String> = emptyList(),
    val windowsOpen: Boolean = false,
    val sunroofOpen: Boolean = false,
    /** Sentry / guard mode reported on (vstdModeState), null if the read failed. */
    val sentryOn: Boolean? = null,
    /** Rain expected at the car within the next hour; null = not checked. */
    val rainSoon: Boolean? = null,
)

/** Something the engine wants done. [reason] is shown in the log and notifications. */
sealed interface AutoAction {
    val reason: String

    data class SentryOn(override val reason: String) : AutoAction
    data class SentryOff(override val reason: String) : AutoAction
    data class Lock(override val reason: String) : AutoAction
    data class AcOn(val tempC: Double, val minutes: Int, override val reason: String) : AutoAction
    data class AcOff(override val reason: String) : AutoAction
    data class CloseWindows(override val reason: String) : AutoAction
    data class CloseSunroof(override val reason: String) : AutoAction

    /** A notification only; no command. */
    data class Notify(val title: String, val text: String, override val reason: String = text) : AutoAction
}

data class Decision(
    val actions: List<AutoAction>,
    val state: AutomationState,
    /** When the next check should run (epoch ms). */
    val nextCheckMs: Long,
    /** One-line summary of this check for the status card. */
    val summary: String,
)
