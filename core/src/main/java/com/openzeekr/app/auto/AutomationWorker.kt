package com.openzeekr.app.auto

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.openzeekr.app.Deps
import com.openzeekr.app.DepsHolder
import com.openzeekr.app.net.model.ServiceParameter
import com.openzeekr.app.net.model.VehicleStatusBean
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.remote.Command
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Locale

/**
 * One automation check: read the car, ask [AutomationEngine] what to do, send those commands,
 * then schedule the next check (sooner while driving or when something is about to fall due).
 */
class AutomationWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val deps = (app as? DepsHolder)?.deps ?: return Result.success()
        val store = AutomationStore.get(app)
        if (!store.config.value.anyEnabled) return Result.success()   // all off: stop the chain

        val nextMs = runCatching { AutomationRunner(app, deps, store).check() }
            .getOrElse {
                Logx.e("auto", "check crashed", it)
                store.updateState { s -> s.withLog(System.currentTimeMillis(), "Check failed: ${it.message ?: it.javaClass.simpleName}") }
                System.currentTimeMillis() + AutomationEngine.MIN_MS * 10
            }
        AutomationScheduler.scheduleNext(app, (nextMs - System.currentTimeMillis()).coerceAtLeast(AutomationEngine.MIN_MS))
        return Result.success()
    }
}

/** The actual check, separated from the WorkManager plumbing so "Run check now" can reuse it. */
class AutomationRunner(private val ctx: Context, private val deps: Deps, private val store: AutomationStore) {

    /** Runs one check and returns when the next one should happen (epoch ms). */
    suspend fun check(): Long {
        val cfg = store.config.value
        val now = System.currentTimeMillis()
        val retryMs = now + cfg.parkedCheckMin.coerceIn(5, 60) * AutomationEngine.MIN_MS

        if (!ensureSignedIn(cfg, now)) return retryMs

        val status = when (val r = deps.control.status()) {
            is CallResult.Ok -> r.value
            is CallResult.Err -> {
                logOnce("Couldn't read the car: ${r.message}")
                return retryMs
            }
        }
        val sentryOn: Boolean? = if (cfg.sentry.enabled) {
            (deps.control.controlState() as? CallResult.Ok)?.value?.get("vstdModeState")?.let { it == "1" }
        } else null

        var snap = toSnapshot(status, now, sentryOn)
        if (cfg.lock.autoCloseWindows && cfg.lock.closeOnlyIfRain && (snap.windowsOpen || snap.sunroofOpen) &&
            snap.lat != null && snap.lng != null
        ) {
            snap = snap.copy(rainSoon = RainForecast.rainSoon(snap.lat!!, snap.lng!!))
        }

        val prev = store.state.value
        val decision = AutomationEngine.decide(cfg, snap, prev)
        var st = decision.state.copy(lastReloginMs = store.state.value.lastReloginMs)
        if (prev.parkedSinceMs == null && st.parkedSinceMs != null) {
            st = st.withLog(now, decision.summary.substringBefore(" · "))
        }
        store.updateState { st }

        for ((i, action) in decision.actions.withIndex()) {
            if (i > 0 && action !is AutoAction.Notify) delay(COMMAND_GAP_MS)
            execute(cfg, action)
        }
        return decision.nextCheckMs
    }

    private suspend fun execute(cfg: AutomationConfig, action: AutoAction) {
        if (action is AutoAction.Notify) {
            AutoNotifier.notify(ctx, action.title, action.text)
            store.log("${action.title}: ${action.text}")
            return
        }
        val (label, cmd, extra) = when (action) {
            is AutoAction.SentryOn -> Triple("Sentry on", Command.SENTINEL_ON, emptyList())
            is AutoAction.SentryOff -> Triple("Sentry off", Command.SENTINEL_OFF, emptyList())
            is AutoAction.Lock -> Triple("Locked", Command.LOCK, emptyList())
            is AutoAction.AcOn -> Triple(
                "A/C on ${action.minutes} min",
                Command.AC_ON,
                listOf(
                    ServiceParameter("AC.temp", String.format(Locale.US, "%.1f", action.tempC)),
                    ServiceParameter("AC.duration", action.minutes.toString()),
                ),
            )
            is AutoAction.AcOff -> Triple("A/C off", Command.AC_OFF, emptyList())
            is AutoAction.CloseWindows -> Triple("Windows closed", Command.WINDOW_CLOSE, emptyList())
            is AutoAction.CloseSunroof -> Triple("Sunroof closed", Command.SUNROOF_CLOSE, emptyList())
            is AutoAction.Notify -> return
        }
        val result = deps.control.send(cmd, extra)
        val ok = result is CallResult.Ok
        val line = if (ok) "$label ✓ — ${action.reason}" else "$label ✗ — ${(result as CallResult.Err).message}"
        store.log(line)
        Logx.d("auto", line)

        if (!ok && action is AutoAction.AcOn) {
            // Don't make a failed start wait out a whole run + gap: allow a retry in 5 minutes.
            store.updateState {
                it.copy(
                    coolStartedMs = null,
                    coolNextAllowedMs = System.currentTimeMillis() + 5 * AutomationEngine.MIN_MS,
                    coolRunsThisPark = (it.coolRunsThisPark - 1).coerceAtLeast(0),
                )
            }
        }
        if (cfg.notifyOnActions || !ok) {
            AutoNotifier.notify(
                ctx,
                if (ok) label else "$label failed",
                if (ok) action.reason.replaceFirstChar { it.uppercase() } else (result as CallResult.Err).message,
                key = cmd.name,
            )
        }
    }

    /** Signed in, or signs in again with the saved login (at most every 6 h). */
    private suspend fun ensureSignedIn(cfg: AutomationConfig, now: Long): Boolean {
        val account = deps.config.current()
        if (account.accessToken.isNotBlank()) return true
        val canRelogin = cfg.autoRelogin && account.email.isNotBlank() && account.password.isNotBlank()
        val last = store.state.value.lastReloginMs
        if (canRelogin && now - last > RELOGIN_SPACING_MS) {
            store.updateState { it.copy(lastReloginMs = now) }
            when (val r = deps.auth.login()) {
                is CallResult.Ok -> { store.log("Signed in again"); return true }
                is CallResult.Err -> store.log("Sign-in failed: ${r.message}")
            }
        }
        logOnce("Paused: signed out of Zeekr. Open the app and sign in.")
        if (now - last > RELOGIN_SPACING_MS || !canRelogin) {
            AutoNotifier.notify(ctx, "Automations paused", "Signed out of your Zeekr account. Open the app to sign in.", key = "signed-out")
        }
        return false
    }

    /** Log a message unless it is the same as the newest log line (avoids a wall of repeats). */
    private fun logOnce(text: String) {
        if (store.state.value.log.firstOrNull()?.text != text) store.log(text)
    }

    companion object {
        private const val COMMAND_GAP_MS = 4_000L
        private const val RELOGIN_SPACING_MS = 6 * 60 * AutomationEngine.MIN_MS

        /** Status values are strings; "0"/blank = closed, anything else = open (same rule as the status screen). */
        private fun isOpen(v: String?) = !v.isNullOrBlank() && v != "0"

        fun toSnapshot(status: VehicleStatusBean, now: Long, sentryOn: Boolean?): CarSnapshot {
            val basic = status.basicVehicleStatus
            val add = status.additionalVehicleStatus
            val safety = add?.drivingSafetyStatus
            val climate = add?.climateStatus
            val elec = add?.electricVehicleStatus

            val parked = basic?.engineStatus?.takeIf { it.isNotBlank() }?.equals("engine-off", ignoreCase = true)
            val pos = basic?.position?.takeIf { it.posCanBeTrusted != false }
            val lat = pos?.latitude
            val lng = pos?.longitude
            val hasFix = lat != null && lng != null && !(lat == 0.0 && lng == 0.0)

            val doors = buildList {
                if (isOpen(safety?.doorOpenStatusDriver)) add("driver door")
                if (isOpen(safety?.doorOpenStatusPassenger)) add("passenger door")
                if (isOpen(safety?.doorOpenStatusDriverRear)) add("rear-left door")
                if (isOpen(safety?.doorOpenStatusPassengerRear)) add("rear-right door")
                if (isOpen(safety?.trunkOpenStatus)) add("trunk")
                if (isOpen(safety?.engineHoodOpenStatus)) add("hood")
            }
            val locked = safety?.centralLockingStatus?.takeIf { it.isNotBlank() }?.let { it == "1" }
            val soc = (elec?.stateOfCharge?.takeIf { it.isNotBlank() } ?: elec?.chargeLevel)?.toDoubleOrNull()

            return CarSnapshot(
                nowMs = now,
                localHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                parked = parked,
                lat = if (hasFix) lat else null,
                lng = if (hasFix) lng else null,
                socPct = soc,
                interiorTempC = climate?.interiorTemp?.toDoubleOrNull(),
                acOn = climate?.preClimateActive,
                locked = locked,
                openDoors = doors,
                windowsOpen = climate?.windowsOpen == true,
                sunroofOpen = climate?.sunroofOpen == true,
                sentryOn = sentryOn,
            )
        }
    }
}
