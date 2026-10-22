package com.openzeekr.app.auto

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The automation rules. Pure function of (settings, car reading, previous state) -> what to do.
 *
 * All decisions use the CAR's reported position, not the phone's, so they behave the same whoever
 * drives. Nothing here talks to the network: [AutomationWorker] reads the car, calls [decide], then
 * sends the returned commands.
 *
 *  - Auto-sentry: parked outside every safe zone for `armAfterMin` -> sentry on (and lock).
 *    Parked inside a safe zone with sentry on because of us -> sentry off.
 *  - Auto-cool: parked, cabin >= trigger, battery >= floor, inside active hours -> A/C on for
 *    `runMinutes`, then wait `gapMinutes` before the next run. Stops a run if the battery hits the floor.
 *  - Lock guard: lock if left unlocked; alert if something stays open; close windows (optionally only
 *    when rain is forecast).
 */
object AutomationEngine {

    const val MIN_MS = 60_000L
    /** Check this often while the car is being driven, to catch the moment it parks. */
    const val DRIVING_CHECK_MIN = 3
    /** Car found this far from where it was parked last check = it moved; treat as a new stop. */
    const val MOVED_M = 300.0
    const val MAX_SENTRY_ATTEMPTS = 3
    const val SENTRY_RETRY_MIN = 4
    const val MAX_LOCK_ATTEMPTS = 2
    const val MAX_CLOSE_ATTEMPTS = 2

    fun decide(cfg: AutomationConfig, car: CarSnapshot, prev: AutomationState): Decision {
        val now = car.nowMs
        val parkedCheckMs = cfg.parkedCheckMin.coerceIn(5, 60) * MIN_MS

        when (car.parked) {
            null -> return Decision(
                actions = emptyList(),
                state = prev.copy(lastCheckMs = now, lastRunSummary = "Car status unknown"),
                nextCheckMs = now + parkedCheckMs,
                summary = "Car status unknown",
            )
            false -> {
                val wasParked = prev.parkedSinceMs != null
                var st = newStop(prev).copy(
                    parkedSinceMs = null, parkLat = null, parkLng = null,
                    lastCheckMs = now, lastRunSummary = "Car is on / driving",
                )
                if (wasParked) st = st.withLog(now, "Car is on / driving")
                return Decision(emptyList(), st, now + DRIVING_CHECK_MIN * MIN_MS, "Car is on / driving")
            }
            true -> Unit
        }

        // ---------- parked ----------
        val locKnown = car.lat != null && car.lng != null
        var st = prev
        // A stop we didn't see begin (car drove between two checks) - reset the per-stop counters.
        val moved = locKnown && prev.parkLat != null && prev.parkLng != null &&
            distanceM(prev.parkLat, prev.parkLng, car.lat!!, car.lng!!) > MOVED_M
        if (prev.parkedSinceMs == null || moved) {
            st = newStop(prev).copy(parkedSinceMs = now)
        }
        if (locKnown) st = st.copy(parkLat = car.lat, parkLng = car.lng)
        val parkedSince = st.parkedSinceMs ?: now
        val parkedMin = ((now - parkedSince) / MIN_MS).toInt()
        val zone = if (locKnown) zoneAt(cfg.sentry.zones, car.lat!!, car.lng!!) else null
        val placeText = when {
            !locKnown -> "parked (location unknown)"
            zone != null -> "parked at ${zone.name}"
            else -> "parked away from safe places"
        }

        val actions = mutableListOf<AutoAction>()
        val wake = mutableListOf(now + parkedCheckMs)
        val notes = mutableListOf<String>()
        val isLocked = { car.locked == true || actions.any { it is AutoAction.Lock } }

        // ---------- auto-sentry ----------
        if (cfg.sentry.enabled) {
            if (!locKnown) {
                notes += "sentry: waiting for car location"
            } else if (zone == null) {
                when {
                    car.sentryOn == true -> {
                        notes += "sentry on"
                    }
                    parkedMin < cfg.sentry.armAfterMin -> {
                        notes += "sentry arms in ${cfg.sentry.armAfterMin - parkedMin} min"
                        wake += parkedSince + cfg.sentry.armAfterMin * MIN_MS
                    }
                    st.sentryAttempts >= MAX_SENTRY_ATTEMPTS -> {
                        if (!st.sentryFailNotified) {
                            actions += AutoAction.Notify(
                                "Couldn't turn on sentry",
                                "The car didn't confirm sentry mode after $MAX_SENTRY_ATTEMPTS tries. Check it in the app.",
                            )
                            st = st.copy(sentryFailNotified = true)
                        }
                    }
                    st.lastSentryAttemptMs != null && now - st.lastSentryAttemptMs!! < SENTRY_RETRY_MIN * MIN_MS -> {
                        wake += st.lastSentryAttemptMs!! + SENTRY_RETRY_MIN * MIN_MS
                    }
                    else -> {
                        if (cfg.sentry.lockWhenArming && car.locked == false && car.openDoors.isEmpty()) {
                            actions += AutoAction.Lock("locking before sentry")
                        }
                        actions += AutoAction.SentryOn("parked $parkedMin min away from safe places")
                        st = st.copy(
                            sentryArmedByUs = true,
                            sentryAttempts = st.sentryAttempts + 1,
                            lastSentryAttemptMs = now,
                        )
                        wake += now + SENTRY_RETRY_MIN * MIN_MS
                    }
                }
            } else {
                // Inside a safe zone.
                if (car.sentryOn == true && st.sentryArmedByUs && cfg.sentry.disarmInSafeZone) {
                    actions += AutoAction.SentryOff("parked at ${zone.name}")
                    st = st.copy(sentryArmedByUs = false)
                } else {
                    notes += "safe place: ${zone.name}"
                }
            }
        }
        if (car.sentryOn == false && st.sentryArmedByUs && actions.none { it is AutoAction.SentryOn }) {
            // Someone (or the car) switched it off; stop claiming it.
            st = st.copy(sentryArmedByUs = false)
        }

        // ---------- auto-cool ----------
        if (cfg.cool.enabled) {
            val c = cfg.cool
            val runEnd = st.coolStartedMs?.let { it + c.runMinutes * MIN_MS }
            val running = runEnd != null && now < runEnd
            val soc = car.socPct
            val temp = car.interiorTempC
            if (running) {
                if (soc != null && soc < c.minSocPct && car.acOn == true) {
                    actions += AutoAction.AcOff("battery ${soc.roundToInt()}% fell below ${c.minSocPct}%")
                    st = st.copy(coolStartedMs = null)
                } else {
                    notes += "cooling"
                    wake += runEnd!! + MIN_MS
                }
            } else {
                val skip: String? = when {
                    temp == null -> "cabin temperature not reported"
                    temp < c.triggerTempC -> null.also { notes += "cabin ${fmt(temp)}°C" }
                    car.acOn == true -> "A/C already on"
                    !inHours(car.localHour, c.activeFromHour, c.activeToHour) -> "outside active hours"
                    zone != null && !c.alsoInSafeZones -> "in safe place ${zone.name}"
                    c.maxRunsPerPark > 0 && st.coolRunsThisPark >= c.maxRunsPerPark -> "reached ${c.maxRunsPerPark} runs this stop"
                    soc == null -> "battery level not reported"
                    soc < c.minSocPct -> "battery ${soc.roundToInt()}% below ${c.minSocPct}%"
                    now < st.coolNextAllowedMs -> {
                        wake += st.coolNextAllowedMs
                        "waiting ${((st.coolNextAllowedMs - now + MIN_MS - 1) / MIN_MS)} min between runs"
                    }
                    else -> ""
                }
                when {
                    skip == "" -> {
                        actions += AutoAction.AcOn(
                            c.targetTempC, c.runMinutes,
                            "cabin ${fmt(temp!!)}°C ≥ ${fmt(c.triggerTempC)}°C, battery ${soc!!.roundToInt()}%",
                        )
                        st = st.copy(
                            coolStartedMs = now,
                            coolNextAllowedMs = now + (c.runMinutes + c.gapMinutes) * MIN_MS,
                            coolRunsThisPark = st.coolRunsThisPark + 1,
                        )
                        wake += now + c.runMinutes * MIN_MS + MIN_MS
                    }
                    skip != null -> {
                        notes += "cool: $skip"
                        if (soc != null && soc < c.minSocPct && temp != null && temp >= c.triggerTempC &&
                            !st.coolLowSocNotified
                        ) {
                            actions += AutoAction.Notify(
                                "Auto-cool skipped",
                                "Cabin is ${fmt(temp)}°C but battery is ${soc.roundToInt()}% (floor ${c.minSocPct}%).",
                            )
                            st = st.copy(coolLowSocNotified = true)
                        }
                    }
                }
            }
        }

        // ---------- lock guard: auto-lock ----------
        val lg = cfg.lock
        if (car.locked == false) {
            val since = st.unlockedSinceMs ?: now
            st = st.copy(unlockedSinceMs = since)
            val lockApplies = lg.autoLockEnabled && (zone == null || lg.autoLockInSafeZones)
            if (lockApplies && !isLocked()) {
                val dueAt = since + lg.autoLockAfterMin * MIN_MS
                when {
                    now < dueAt -> wake += dueAt
                    car.openDoors.isNotEmpty() -> if (!st.unlockedOpenNotified) {
                        actions += AutoAction.Notify(
                            "Car unlocked and open",
                            "Can't auto-lock: ${car.openDoors.joinToString()} open ($placeText).",
                        )
                        st = st.copy(unlockedOpenNotified = true)
                    }
                    st.lockAttempts < MAX_LOCK_ATTEMPTS -> {
                        val mins = ((now - since) / MIN_MS).toInt()
                        actions += AutoAction.Lock("left unlocked ${mins.coerceAtLeast(lg.autoLockAfterMin)} min, $placeText")
                        st = st.copy(lockAttempts = st.lockAttempts + 1)
                    }
                }
            }
        } else if (car.locked == true) {
            st = st.copy(unlockedSinceMs = null, lockAttempts = 0, unlockedOpenNotified = false)
        }

        // ---------- lock guard: open alert + window close ----------
        val openItems = buildList {
            addAll(car.openDoors)
            if (car.windowsOpen) add("windows")
            if (car.sunroofOpen) add("sunroof")
        }
        if (openItems.isEmpty()) {
            st = st.copy(openSinceMs = null, openAlertKey = null, closeAttempts = 0)
        } else {
            val since = st.openSinceMs ?: now
            st = st.copy(openSinceMs = since)
            val openMin = ((now - since) / MIN_MS).toInt()
            if (lg.openAlertEnabled) {
                val key = openItems.sorted().joinToString(",")
                val dueAt = since + lg.openAlertAfterMin * MIN_MS
                if (now < dueAt) wake += dueAt
                else if (st.openAlertKey != key) {
                    actions += AutoAction.Notify(
                        "Car left open",
                        "${openItems.joinToString().replaceFirstChar { it.uppercase() }} open for $openMin min ($placeText).",
                    )
                    st = st.copy(openAlertKey = key)
                }
            }
            if (lg.autoCloseWindows && (car.windowsOpen || car.sunroofOpen)) {
                val dueAt = since + lg.autoCloseAfterMin * MIN_MS
                val rainOk = !lg.closeOnlyIfRain || car.rainSoon == true
                when {
                    now < dueAt -> wake += dueAt
                    !rainOk -> notes += if (car.rainSoon == null) "rain forecast unavailable" else "no rain forecast"
                    st.closeAttempts >= MAX_CLOSE_ATTEMPTS -> Unit
                    else -> {
                        val why = if (lg.closeOnlyIfRain) "rain forecast, open $openMin min" else "open $openMin min while parked"
                        if (car.windowsOpen) actions += AutoAction.CloseWindows(why)
                        if (car.sunroofOpen) actions += AutoAction.CloseSunroof(why)
                        st = st.copy(closeAttempts = st.closeAttempts + 1)
                    }
                }
            }
        }

        val next = (wake.minOrNull() ?: (now + parkedCheckMs)).coerceAtLeast(now + MIN_MS)
        val summary = buildString {
            append(placeText.replaceFirstChar { it.uppercase() })
            append(" · $parkedMin min")
            if (notes.isNotEmpty()) append(" · ").append(notes.joinToString(" · "))
        }
        st = st.copy(lastCheckMs = now, lastRunSummary = summary)
        return Decision(actions, st, next, summary)
    }

    /** Reset the per-stop counters, keeping the log and anything that outlives a stop. */
    private fun newStop(prev: AutomationState) = prev.copy(
        sentryAttempts = 0,
        lastSentryAttemptMs = null,
        sentryFailNotified = false,
        coolRunsThisPark = 0,
        coolStartedMs = null,
        coolNextAllowedMs = 0,
        coolLowSocNotified = false,
        unlockedSinceMs = null,
        lockAttempts = 0,
        unlockedOpenNotified = false,
        openSinceMs = null,
        openAlertKey = null,
        closeAttempts = 0,
    )

    fun zoneAt(zones: List<SafeZone>, lat: Double, lng: Double): SafeZone? =
        zones.filter { distanceM(it.lat, it.lng, lat, lng) <= it.radiusM }
            .minByOrNull { distanceM(it.lat, it.lng, lat, lng) }

    /** Great-circle distance in metres (haversine). */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Is [hour] inside [from, to)? from == to or 0..24 = all day; from > to wraps past midnight. */
    fun inHours(hour: Int, from: Int, to: Int): Boolean {
        val f = from.coerceIn(0, 24)
        val t = to.coerceIn(0, 24)
        if (f == t || (f == 0 && t == 24)) return true
        return if (f < t) hour in f until t else hour >= f || hour < t
    }

    private fun fmt(d: Double): String =
        if (d % 1.0 == 0.0) d.toInt().toString() else String.format(java.util.Locale.US, "%.1f", d)
}
