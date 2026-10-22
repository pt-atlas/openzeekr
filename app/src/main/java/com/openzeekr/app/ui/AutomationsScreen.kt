package com.openzeekr.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openzeekr.app.Deps
import com.openzeekr.app.auto.AutomationConfig
import com.openzeekr.app.auto.AutomationScheduler
import com.openzeekr.app.auto.AutomationStore
import com.openzeekr.app.auto.SafeZone
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Automations: auto-sentry by location (with safe places), auto-cool by cabin temperature, and the
 * lock guard. Settings save as you change them; the background checks start/stop to match.
 */
@Composable
fun AutomationsScreen(deps: Deps, onBack: () -> Unit, snackbar: (String) -> Unit, modifier: Modifier = Modifier) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val store = remember { AutomationStore.get(ctx) }
    val cfg by store.config.collectAsState()
    val st by store.state.collectAsState()
    var addZone by remember { mutableStateOf(false) }
    var batteryExempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(ctx)) }
    // Re-read the battery exemption when returning from Android's settings dialog.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) batteryExempt = isIgnoringBatteryOptimizations(ctx)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun update(runCheck: Boolean = false, t: (AutomationConfig) -> AutomationConfig) {
        store.updateConfig(t)
        AutomationScheduler.sync(ctx)
        if (runCheck && store.config.value.anyEnabled) AutomationScheduler.runNow(ctx)
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Automations", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        // ---------- status ----------
        CockpitCard {
            SectionHeader("Status")
            val running = cfg.anyEnabled
            Text(
                if (running) "Background checks are on" else "All automations are off",
                color = if (running) Brand.good else Brand.muted, fontWeight = FontWeight.SemiBold,
            )
            st.lastCheckMs?.let {
                Text("Last check ${fmtTime(it)} · ${st.lastRunSummary.orEmpty()}", color = Brand.muted, fontSize = 12.5.sp)
            }
            if (running) {
                GhostButton("Run a check now", Modifier.fillMaxWidth()) {
                    AutomationScheduler.runNow(ctx); snackbar("Checking the car…")
                }
            }
            if (running && !batteryExempt) {
                Text(
                    "Android may delay background checks to save battery. Allow OpenZeekr to run unrestricted for reliable timing.",
                    color = Brand.energy, fontSize = 12.sp,
                )
                PrimaryButton("Allow background activity", Modifier.fillMaxWidth(), tint = Brand.energy) {
                    requestBatteryExemption(ctx); batteryExempt = isIgnoringBatteryOptimizations(ctx)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------- auto-sentry ----------
        CockpitCard {
            SectionHeader("Auto sentry")
            ToggleRow("Turn on sentry when parked", "Arms automatically when the car parks away from your safe places",
                cfg.sentry.enabled) { on -> update(runCheck = on) { it.copy(sentry = it.sentry.copy(enabled = on)) } }
            if (cfg.sentry.enabled) {
                ChipRow("Arm after parked for", listOf(1, 3, 5, 10), cfg.sentry.armAfterMin, { "$it min" }) { v ->
                    update { it.copy(sentry = it.sentry.copy(armAfterMin = v)) }
                }
                ToggleRow("Lock doors when arming", "Only if the doors are closed", cfg.sentry.lockWhenArming) { on ->
                    update { it.copy(sentry = it.sentry.copy(lockWhenArming = on)) }
                }
                ToggleRow("Turn off in safe places", "If this app armed it and the car is at a safe place", cfg.sentry.disarmInSafeZone) { on ->
                    update { it.copy(sentry = it.sentry.copy(disarmInSafeZone = on)) }
                }
            }
            Text("Safe places", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("Sentry stays off here. Also used by the \"in safe places\" options below.", color = Brand.muted, fontSize = 12.sp)
            if (cfg.sentry.zones.isEmpty()) {
                Text("No safe places yet. Park at home, then add the car's current spot.", color = Brand.faint, fontSize = 12.5.sp)
            }
            cfg.sentry.zones.forEach { z ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(z.name, fontWeight = FontWeight.SemiBold)
                        Text("within ${z.radiusM} m · ${"%.5f".format(Locale.US, z.lat)}, ${"%.5f".format(Locale.US, z.lng)}",
                            color = Brand.muted, fontSize = 11.5.sp)
                    }
                    IconButton(onClick = {
                        update { c -> c.copy(sentry = c.sentry.copy(zones = c.sentry.zones.filterNot { it.id == z.id })) }
                    }) { Icon(Icons.Filled.Delete, "Remove ${z.name}", tint = Brand.crit) }
                }
            }
            PrimaryButton("Add safe place", Modifier.fillMaxWidth()) { addZone = true }
        }
        Spacer(Modifier.height(12.dp))

        // ---------- auto-cool ----------
        CockpitCard {
            SectionHeader("Auto cool")
            val c = cfg.cool
            ToggleRow("Cool the cabin when parked", "Runs the A/C when the cabin gets hot, and repeats while parked",
                c.enabled) { on -> update(runCheck = on) { it.copy(cool = it.cool.copy(enabled = on)) } }
            if (c.enabled) {
                ChipRow("Start when cabin reaches", listOf(35.0, 38.0, 40.0, 45.0), c.triggerTempC, { "${it.toInt()}°C" }) { v ->
                    update { it.copy(cool = it.cool.copy(triggerTempC = v)) }
                }
                ChipRow("A/C set to", listOf(20.0, 22.0, 24.0, 26.0), c.targetTempC, { "${it.toInt()}°C" }) { v ->
                    update { it.copy(cool = it.cool.copy(targetTempC = v)) }
                }
                ChipRow("Each run lasts", listOf(5, 10, 15, 20, 30), c.runMinutes, { "$it min" }) { v ->
                    update { it.copy(cool = it.cool.copy(runMinutes = v)) }
                }
                ChipRow("Wait between runs", listOf(10, 20, 30, 60), c.gapMinutes, { "$it min" }) { v ->
                    update { it.copy(cool = it.cool.copy(gapMinutes = v)) }
                }
                ChipRow("Don't run below battery", listOf(20, 30, 40, 50, 60), c.minSocPct, { "$it%" }) { v ->
                    update { it.copy(cool = it.cool.copy(minSocPct = v)) }
                }
                ChipRow("Max runs per stop", listOf(0, 2, 4, 6), c.maxRunsPerPark, { if (it == 0) "No limit" else "$it" }) { v ->
                    update { it.copy(cool = it.cool.copy(maxRunsPerPark = v)) }
                }
                val hours = listOf(0 to 24, 7 to 19, 9 to 17, 10 to 18)
                ChipRow("Active hours", hours, c.activeFromHour to c.activeToHour,
                    { (f, t) -> if (f == 0 && t == 24) "All day" else "%02d–%02d".format(f, t) }) { (f, t) ->
                    update { it.copy(cool = it.cool.copy(activeFromHour = f, activeToHour = t)) }
                }
                ToggleRow("Also in safe places", "E.g. a hot carport at home", c.alsoInSafeZones) { on ->
                    update { it.copy(cool = it.cool.copy(alsoInSafeZones = on)) }
                }
                Text(
                    "Each run uses battery. The car may refuse remote A/C at low charge, and the cabin reading can lag while the car sleeps.",
                    color = Brand.faint, fontSize = 11.5.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------- lock guard ----------
        CockpitCard {
            SectionHeader("Lock guard")
            val l = cfg.lock
            ToggleRow("Auto-lock if left unlocked", "Locks over the internet; works without the Bluetooth key",
                l.autoLockEnabled) { on -> update(runCheck = on) { it.copy(lock = it.lock.copy(autoLockEnabled = on)) } }
            if (l.autoLockEnabled) {
                ChipRow("Lock after", listOf(2, 5, 10, 15), l.autoLockAfterMin, { "$it min" }) { v ->
                    update { it.copy(lock = it.lock.copy(autoLockAfterMin = v)) }
                }
                ToggleRow("Also in safe places", "Turn off if you leave it unlocked at home on purpose", l.autoLockInSafeZones) { on ->
                    update { it.copy(lock = it.lock.copy(autoLockInSafeZones = on)) }
                }
            }
            ToggleRow("Alert if left open", "Doors, trunk, hood, windows or sunroof open while parked",
                l.openAlertEnabled) { on -> update(runCheck = on) { it.copy(lock = it.lock.copy(openAlertEnabled = on)) } }
            if (l.openAlertEnabled) {
                ChipRow("Alert after", listOf(5, 10, 20, 30), l.openAlertAfterMin, { "$it min" }) { v ->
                    update { it.copy(lock = it.lock.copy(openAlertAfterMin = v)) }
                }
            }
            ToggleRow("Close windows & sunroof", "When parked and left open",
                l.autoCloseWindows) { on -> update(runCheck = on) { it.copy(lock = it.lock.copy(autoCloseWindows = on)) } }
            if (l.autoCloseWindows) {
                ChipRow("Close after", listOf(5, 10, 20, 30), l.autoCloseAfterMin, { "$it min" }) { v ->
                    update { it.copy(lock = it.lock.copy(autoCloseAfterMin = v)) }
                }
                ToggleRow("Only when rain is forecast", "Checks the next hour's forecast at the car (Open-Meteo)", l.closeOnlyIfRain) { on ->
                    update { it.copy(lock = it.lock.copy(closeOnlyIfRain = on)) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------- general ----------
        CockpitCard {
            SectionHeader("General")
            ChipRow("Check while parked every", listOf(10, 15, 30), cfg.parkedCheckMin, { "$it min" }) { v ->
                update { it.copy(parkedCheckMin = v) }
            }
            ToggleRow("Notify when an automation acts", "Failures are always notified", cfg.notifyOnActions) { on ->
                update { it.copy(notifyOnActions = on) }
            }
            ToggleRow("Sign in again automatically", "If the session expires (uses your saved login)", cfg.autoRelogin) { on ->
                update { it.copy(autoRelogin = on) }
            }
            Text("Tip: use a second Zeekr account shared with your car, so this app and the official app don't sign each other out.",
                color = Brand.faint, fontSize = 11.5.sp)
        }
        Spacer(Modifier.height(12.dp))

        // ---------- activity ----------
        CockpitCard {
            SectionHeader("Activity")
            if (st.log.isEmpty()) Text("Nothing yet.", color = Brand.faint, fontSize = 12.5.sp)
            st.log.take(30).forEach { e ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(fmtTime(e.timeMs), color = Brand.faint, fontSize = 11.5.sp)
                    Text(e.text, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    if (addZone) {
        AddZoneDialog(
            deps = deps,
            onDismiss = { addZone = false },
            onAdd = { zone ->
                update(runCheck = true) { it.copy(sentry = it.sentry.copy(zones = it.sentry.zones + zone)) }
                addZone = false
                snackbar("Added ${zone.name}")
            },
        )
    }
}

@Composable
private fun AddZoneDialog(deps: Deps, onDismiss: () -> Unit, onAdd: (SafeZone) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("Home") }
    var coords by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(SafeZone.DEFAULT_RADIUS_M) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun useCarLocation() {
        busy = true; error = null
        scope.launch {
            when (val r = deps.control.status()) {
                is CallResult.Ok -> {
                    val p = r.value.basicVehicleStatus?.position
                    val la = p?.latitude; val lo = p?.longitude
                    if (la != null && lo != null && !(la == 0.0 && lo == 0.0)) {
                        coords = "%.6f, %.6f".format(Locale.US, la, lo)
                    } else error = "The car didn't report a location. Try again in a minute."
                }
                is CallResult.Err -> error = r.message
            }
            busy = false
        }
    }
    LaunchedEffect(Unit) { useCarLocation() }

    val parsed = parseCoords(coords)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add safe place") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(30) }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    coords, { coords = it }, label = { Text("Latitude, longitude") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    supportingText = { Text("Filled from the car's position. You can paste coordinates from Google Maps instead.") },
                )
                Text(
                    if (busy) "Getting the car's location…" else "Use the car's current location",
                    color = if (busy) Brand.muted else Brand.accent, fontSize = 13.sp,
                    modifier = Modifier.clickable(enabled = !busy) { useCarLocation() },
                )
                error?.let { Text(it, color = Brand.crit, fontSize = 12.sp) }
                ChipRow("Radius", listOf(100, 150, 300, 500), radius, { "$it m" }) { radius = it }
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null && name.isNotBlank(), onClick = {
                val (la, lo) = parsed!!
                onAdd(SafeZone(UUID.randomUUID().toString(), name.trim(), la, lo, radius))
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Brand.muted, fontSize = 11.5.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = brandSwitchColors(Brand.good))
    }
}

@Composable
private fun <T> ChipRow(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Brand.muted, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> SelectChip(text(o), o == selected) { onSelect(o) } }
        }
    }
}

/** Accepts "13.91, 100.49", "13.91 100.49" or a Google Maps link containing "@13.91,100.49". */
internal fun parseCoords(text: String): Pair<Double, Double>? {
    val m = Regex("""(-?\d{1,2}(?:\.\d+)?)\s*[, ]\s*(-?\d{1,3}(?:\.\d+)?)""").find(text) ?: return null
    val la = m.groupValues[1].toDoubleOrNull() ?: return null
    val lo = m.groupValues[2].toDoubleOrNull() ?: return null
    if (la !in -90.0..90.0 || lo !in -180.0..180.0 || (la == 0.0 && lo == 0.0)) return null
    return la to lo
}

private fun fmtTime(ms: Long): String {
    val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ms))
    val pattern = if (day == today) "HH:mm" else "d MMM HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}

private fun isIgnoringBatteryOptimizations(ctx: Context): Boolean =
    runCatching {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(true)

private fun requestBatteryExemption(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
