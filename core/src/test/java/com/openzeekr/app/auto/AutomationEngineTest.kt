package com.openzeekr.app.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationEngineTest {

    private val min = AutomationEngine.MIN_MS
    private val t0 = 1_800_000_000_000L

    // Home, and a spot ~2 km away.
    private val home = SafeZone("h", "Home", 13.9130, 100.4980, 150)
    private val awayLat = 13.9300
    private val awayLng = 100.5050

    private fun car(
        at: Long,
        parked: Boolean? = true,
        lat: Double? = awayLat,
        lng: Double? = awayLng,
        soc: Double? = 80.0,
        temp: Double? = 30.0,
        acOn: Boolean? = false,
        locked: Boolean? = true,
        openDoors: List<String> = emptyList(),
        windowsOpen: Boolean = false,
        sunroofOpen: Boolean = false,
        sentryOn: Boolean? = false,
        rainSoon: Boolean? = null,
        hour: Int = 14,
    ) = CarSnapshot(at, hour, parked, lat, lng, soc, temp, acOn, locked, openDoors, windowsOpen, sunroofOpen, sentryOn, rainSoon)

    private val sentryCfg = AutomationConfig(sentry = AutoSentryConfig(enabled = true, zones = listOf(home), armAfterMin = 3))

    // ---------------- geometry ----------------

    @Test fun distanceIsSane() {
        val d = AutomationEngine.distanceM(13.9130, 100.4980, 13.9300, 100.5050)
        assertTrue("got $d", d in 1900.0..2100.0)
        assertEquals(0.0, AutomationEngine.distanceM(1.0, 2.0, 1.0, 2.0), 0.001)
    }

    @Test fun zoneMatchesWithinRadiusOnly() {
        assertEquals(home, AutomationEngine.zoneAt(listOf(home), 13.9131, 100.4981))
        assertNull(AutomationEngine.zoneAt(listOf(home), awayLat, awayLng))
    }

    @Test fun activeHoursWrapPastMidnight() {
        assertTrue(AutomationEngine.inHours(3, 0, 24))
        assertTrue(AutomationEngine.inHours(3, 5, 5))
        assertTrue(AutomationEngine.inHours(10, 8, 18))
        assertFalse(AutomationEngine.inHours(18, 8, 18))
        assertTrue(AutomationEngine.inHours(23, 22, 6))
        assertTrue(AutomationEngine.inHours(2, 22, 6))
        assertFalse(AutomationEngine.inHours(12, 22, 6))
    }

    // ---------------- auto-sentry ----------------

    @Test fun sentryWaitsForArmDelayThenArmsAwayFromSafeZones() {
        val d1 = AutomationEngine.decide(sentryCfg, car(t0), AutomationState())
        assertTrue(d1.actions.isEmpty())
        assertEquals(t0, d1.state.parkedSinceMs)
        assertEquals("wakes at the arm deadline", t0 + 3 * min, d1.nextCheckMs)

        val d2 = AutomationEngine.decide(sentryCfg, car(t0 + 3 * min), d1.state)
        assertTrue(d2.actions.any { it is AutoAction.SentryOn })
        assertTrue(d2.state.sentryArmedByUs)
        assertEquals(1, d2.state.sentryAttempts)
    }

    @Test fun sentryNeverArmsInSafeZone() {
        var st = AutomationState()
        for (m in 0..30 step 5) {
            val d = AutomationEngine.decide(sentryCfg, car(t0 + m * min, lat = home.lat, lng = home.lng), st)
            assertTrue(d.actions.none { it is AutoAction.SentryOn })
            st = d.state
        }
        assertTrue(st.lastRunSummary!!.contains("Home"))
    }

    @Test fun sentryLocksFirstIfUnlockedAndClosed() {
        val st = AutomationState(parkedSinceMs = t0, parkLat = awayLat, parkLng = awayLng)
        val d = AutomationEngine.decide(sentryCfg, car(t0 + 5 * min, locked = false), st)
        val kinds = d.actions.map { it::class.simpleName }
        assertEquals(listOf("Lock", "SentryOn"), kinds)
    }

    @Test fun sentryDoesNotLockWhenADoorIsOpen() {
        val st = AutomationState(parkedSinceMs = t0, parkLat = awayLat, parkLng = awayLng)
        val d = AutomationEngine.decide(sentryCfg, car(t0 + 5 * min, locked = false, openDoors = listOf("trunk")), st)
        assertTrue(d.actions.none { it is AutoAction.Lock })
        assertTrue(d.actions.any { it is AutoAction.SentryOn })
    }

    @Test fun sentryRetriesThenGivesUpWithOneNotification() {
        var st = AutomationState(parkedSinceMs = t0, parkLat = awayLat, parkLng = awayLng)
        var now = t0 + 5 * min
        var sends = 0
        var notifies = 0
        repeat(8) {
            val d = AutomationEngine.decide(sentryCfg, car(now, sentryOn = false), st)
            sends += d.actions.count { it is AutoAction.SentryOn }
            notifies += d.actions.count { it is AutoAction.Notify }
            st = d.state
            now += 5 * min
        }
        assertEquals(AutomationEngine.MAX_SENTRY_ATTEMPTS, sends)
        assertEquals(1, notifies)
    }

    @Test fun sentryNotResentWhileAlreadyOn() {
        val st = AutomationState(parkedSinceMs = t0, parkLat = awayLat, parkLng = awayLng)
        val d = AutomationEngine.decide(sentryCfg, car(t0 + 10 * min, sentryOn = true), st)
        assertTrue(d.actions.isEmpty())
    }

    @Test fun sentryWeArmedIsTurnedOffInSafeZone() {
        val st = AutomationState(parkedSinceMs = t0, parkLat = home.lat, parkLng = home.lng, sentryArmedByUs = true)
        val d = AutomationEngine.decide(sentryCfg, car(t0 + min, lat = home.lat, lng = home.lng, sentryOn = true), st)
        assertTrue(d.actions.any { it is AutoAction.SentryOff })
        assertFalse(d.state.sentryArmedByUs)
    }

    @Test fun sentryTurnedOnManuallyAtHomeIsLeftAlone() {
        val st = AutomationState(parkedSinceMs = t0, parkLat = home.lat, parkLng = home.lng)
        val d = AutomationEngine.decide(sentryCfg, car(t0 + min, lat = home.lat, lng = home.lng, sentryOn = true), st)
        assertTrue(d.actions.none { it is AutoAction.SentryOff })
    }

    @Test fun sentryWaitsWhenLocationUnknown() {
        val st = AutomationState(parkedSinceMs = t0)
        val d = AutomationEngine.decide(sentryCfg, car(t0 + 30 * min, lat = null, lng = null), st)
        assertTrue(d.actions.isEmpty())
    }

    @Test fun drivingResetsStopAndChecksSoon() {
        val st = AutomationState(parkedSinceMs = t0, parkLat = awayLat, parkLng = awayLng, sentryAttempts = 3, coolRunsThisPark = 4)
        val d = AutomationEngine.decide(sentryCfg, car(t0 + 60 * min, parked = false), st)
        assertNull(d.state.parkedSinceMs)
        assertEquals(0, d.state.sentryAttempts)
        assertEquals(0, d.state.coolRunsThisPark)
        assertEquals(t0 + 60 * min + AutomationEngine.DRIVING_CHECK_MIN * min, d.nextCheckMs)
    }

    @Test fun carMovedBetweenChecksStartsANewStop() {
        val st = AutomationState(parkedSinceMs = t0, parkLat = home.lat, parkLng = home.lng, sentryAttempts = 3)
        val now = t0 + 120 * min
        val d = AutomationEngine.decide(sentryCfg, car(now), st)
        assertEquals(now, d.state.parkedSinceMs)
        assertEquals(0, d.state.sentryAttempts)
        assertTrue("arm delay restarts", d.actions.isEmpty())
    }

    // ---------------- auto-cool ----------------

    private val coolCfg = AutomationConfig(
        cool = AutoCoolConfig(enabled = true, triggerTempC = 38.0, targetTempC = 22.0, runMinutes = 10, gapMinutes = 20, minSocPct = 40),
    )

    @Test fun coolsWhenHotAndBatteryOk() {
        val d = AutomationEngine.decide(coolCfg, car(t0, temp = 45.0, soc = 70.0), AutomationState())
        val ac = d.actions.single() as AutoAction.AcOn
        assertEquals(22.0, ac.tempC, 0.0)
        assertEquals(10, ac.minutes)
        assertEquals(1, d.state.coolRunsThisPark)
        assertEquals(t0 + 30 * min, d.state.coolNextAllowedMs)
        assertEquals("checks just after the run ends", t0 + 11 * min, d.nextCheckMs)
    }

    @Test fun doesNotCoolWhenCabinBelowTrigger() {
        val d = AutomationEngine.decide(coolCfg, car(t0, temp = 33.0), AutomationState())
        assertTrue(d.actions.isEmpty())
    }

    @Test fun repeatsAfterGapWhileStillHot() {
        var st = AutomationEngine.decide(coolCfg, car(t0, temp = 45.0), AutomationState()).state
        // During the run: nothing new.
        var d = AutomationEngine.decide(coolCfg, car(t0 + 5 * min, temp = 36.0, acOn = true), st)
        assertTrue(d.actions.isEmpty()); st = d.state
        // Run over, inside the gap, cabin hot again: wait.
        d = AutomationEngine.decide(coolCfg, car(t0 + 20 * min, temp = 41.0), st)
        assertTrue(d.actions.isEmpty())
        assertEquals(t0 + 30 * min, d.nextCheckMs); st = d.state
        // Gap over: second run.
        d = AutomationEngine.decide(coolCfg, car(t0 + 30 * min, temp = 42.0), st)
        assertTrue(d.actions.single() is AutoAction.AcOn)
        assertEquals(2, d.state.coolRunsThisPark)
    }

    @Test fun lowBatteryBlocksCoolingAndNotifiesOnce() {
        var st = AutomationState()
        var notifies = 0
        for (m in 0..60 step 15) {
            val d = AutomationEngine.decide(coolCfg, car(t0 + m * min, temp = 45.0, soc = 35.0), st)
            assertTrue(d.actions.none { it is AutoAction.AcOn })
            notifies += d.actions.count { it is AutoAction.Notify }
            st = d.state
        }
        assertEquals(1, notifies)
    }

    @Test fun stopsARunIfBatteryDropsBelowFloor() {
        val st = AutomationState(parkedSinceMs = t0, parkLat = awayLat, parkLng = awayLng, coolStartedMs = t0)
        val d = AutomationEngine.decide(coolCfg, car(t0 + 4 * min, temp = 40.0, soc = 39.0, acOn = true), st)
        assertTrue(d.actions.single() is AutoAction.AcOff)
    }

    @Test fun respectsMaxRunsActiveHoursAndSafeZoneOption() {
        val capped = coolCfg.copy(cool = coolCfg.cool.copy(maxRunsPerPark = 2))
        val st = AutomationState(parkedSinceMs = t0, parkLat = awayLat, parkLng = awayLng, coolRunsThisPark = 2)
        assertTrue(AutomationEngine.decide(capped, car(t0 + 90 * min, temp = 45.0), st).actions.isEmpty())

        val daytime = coolCfg.copy(cool = coolCfg.cool.copy(activeFromHour = 9, activeToHour = 18))
        assertTrue(AutomationEngine.decide(daytime, car(t0, temp = 45.0, hour = 20), AutomationState()).actions.isEmpty())
        assertTrue(AutomationEngine.decide(daytime, car(t0, temp = 45.0, hour = 12), AutomationState()).actions.isNotEmpty())

        val notHome = coolCfg.copy(
            sentry = AutoSentryConfig(zones = listOf(home)),
            cool = coolCfg.cool.copy(alsoInSafeZones = false),
        )
        assertTrue(AutomationEngine.decide(notHome, car(t0, lat = home.lat, lng = home.lng, temp = 45.0), AutomationState()).actions.isEmpty())
    }

    @Test fun doesNotCoolWhenAcAlreadyOnOrTempMissing() {
        assertTrue(AutomationEngine.decide(coolCfg, car(t0, temp = 45.0, acOn = true), AutomationState()).actions.isEmpty())
        assertTrue(AutomationEngine.decide(coolCfg, car(t0, temp = null), AutomationState()).actions.isEmpty())
    }

    // ---------------- lock guard ----------------

    private val lockCfg = AutomationConfig(lock = LockGuardConfig(autoLockEnabled = true, autoLockAfterMin = 5))

    @Test fun autoLocksAfterDelay() {
        var d = AutomationEngine.decide(lockCfg, car(t0, locked = false), AutomationState())
        assertTrue(d.actions.isEmpty())
        assertEquals(t0 + 5 * min, d.nextCheckMs)
        d = AutomationEngine.decide(lockCfg, car(t0 + 5 * min, locked = false), d.state)
        assertTrue(d.actions.single() is AutoAction.Lock)
    }

    @Test fun autoLockNotifiesInsteadWhenDoorOpen() {
        val st = AutomationState(parkedSinceMs = t0, parkLat = awayLat, parkLng = awayLng, unlockedSinceMs = t0)
        var d = AutomationEngine.decide(lockCfg, car(t0 + 6 * min, locked = false, openDoors = listOf("driver door")), st)
        assertTrue(d.actions.single() is AutoAction.Notify)
        d = AutomationEngine.decide(lockCfg, car(t0 + 20 * min, locked = false, openDoors = listOf("driver door")), d.state)
        assertTrue("only once", d.actions.isEmpty())
    }

    @Test fun autoLockStopsAfterTwoAttempts() {
        var st = AutomationState(parkedSinceMs = t0, parkLat = awayLat, parkLng = awayLng, unlockedSinceMs = t0)
        var locks = 0
        for (m in 6..60 step 6) {
            val d = AutomationEngine.decide(lockCfg, car(t0 + m * min, locked = false), st)
            locks += d.actions.count { it is AutoAction.Lock }
            st = d.state
        }
        assertEquals(AutomationEngine.MAX_LOCK_ATTEMPTS, locks)
    }

    @Test fun autoLockCanSkipSafeZones() {
        val cfg = lockCfg.copy(sentry = AutoSentryConfig(zones = listOf(home)), lock = lockCfg.lock.copy(autoLockInSafeZones = false))
        val st = AutomationState(parkedSinceMs = t0, parkLat = home.lat, parkLng = home.lng, unlockedSinceMs = t0)
        assertTrue(AutomationEngine.decide(cfg, car(t0 + 30 * min, lat = home.lat, lng = home.lng, locked = false), st).actions.isEmpty())
    }

    @Test fun openAlertOncePerDistinctState() {
        val cfg = AutomationConfig(lock = LockGuardConfig(openAlertEnabled = true, openAlertAfterMin = 10))
        var d = AutomationEngine.decide(cfg, car(t0, windowsOpen = true), AutomationState())
        assertTrue(d.actions.isEmpty())
        d = AutomationEngine.decide(cfg, car(t0 + 10 * min, windowsOpen = true), d.state)
        assertTrue(d.actions.single() is AutoAction.Notify)
        d = AutomationEngine.decide(cfg, car(t0 + 25 * min, windowsOpen = true), d.state)
        assertTrue(d.actions.isEmpty())
        d = AutomationEngine.decide(cfg, car(t0 + 40 * min, windowsOpen = true, openDoors = listOf("trunk")), d.state)
        assertTrue("new thing open -> new alert", d.actions.single() is AutoAction.Notify)
        d = AutomationEngine.decide(cfg, car(t0 + 50 * min), d.state)
        assertNull(d.state.openSinceMs)
    }

    @Test fun closesWindowsOnlyWhenRainForecast() {
        val cfg = AutomationConfig(lock = LockGuardConfig(autoCloseWindows = true, autoCloseAfterMin = 10, closeOnlyIfRain = true))
        val st = AutomationState(parkedSinceMs = t0, parkLat = awayLat, parkLng = awayLng, openSinceMs = t0)
        assertTrue(AutomationEngine.decide(cfg, car(t0 + 15 * min, windowsOpen = true, rainSoon = false), st).actions.isEmpty())
        assertTrue(AutomationEngine.decide(cfg, car(t0 + 15 * min, windowsOpen = true, rainSoon = null), st).actions.isEmpty())
        val d = AutomationEngine.decide(cfg, car(t0 + 15 * min, windowsOpen = true, sunroofOpen = true, rainSoon = true), st)
        assertEquals(listOf("CloseWindows", "CloseSunroof"), d.actions.map { it::class.simpleName })
    }

    @Test fun closesWindowsAlwaysWhenRainNotRequired() {
        val cfg = AutomationConfig(lock = LockGuardConfig(autoCloseWindows = true, autoCloseAfterMin = 10, closeOnlyIfRain = false))
        val st = AutomationState(parkedSinceMs = t0, parkLat = awayLat, parkLng = awayLng, openSinceMs = t0)
        val d = AutomationEngine.decide(cfg, car(t0 + 10 * min, windowsOpen = true), st)
        assertTrue(d.actions.single() is AutoAction.CloseWindows)
    }

    // ---------------- general ----------------

    @Test fun allDisabledDoesNothing() {
        val d = AutomationEngine.decide(
            AutomationConfig(),
            car(t0, temp = 50.0, locked = false, windowsOpen = true, openDoors = listOf("trunk")),
            AutomationState(),
        )
        assertTrue(d.actions.isEmpty())
        assertEquals(t0 + 15 * min, d.nextCheckMs)
    }

    @Test fun unknownStatusWaitsAFullInterval() {
        val d = AutomationEngine.decide(sentryCfg, car(t0, parked = null), AutomationState())
        assertTrue(d.actions.isEmpty())
        assertEquals(t0 + 15 * min, d.nextCheckMs)
    }

    @Test fun logIsCapped() {
        var st = AutomationState()
        repeat(100) { st = st.withLog(t0 + it, "e$it") }
        assertEquals(AutomationState.MAX_LOG, st.log.size)
        assertEquals("e99", st.log.first().text)
    }
}
