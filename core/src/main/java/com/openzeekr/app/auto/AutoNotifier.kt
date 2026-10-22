package com.openzeekr.app.auto

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openzeekr.core.R

/** Notifications for the automations, on their own channel so they can be silenced separately. */
object AutoNotifier {
    const val CHANNEL_ID = "automations"
    private const val CHANNEL_NAME = "Automations"

    /** Pass to the launcher activity to open the Automations screen directly. */
    const val EXTRA_OPEN_AUTOMATIONS = "com.openzeekr.app.extra.OPEN_AUTOMATIONS"

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Auto-sentry, auto-cool and lock-guard actions and alerts"
                },
            )
        }
    }

    /** [key] groups updates: posting the same key again replaces the earlier notification. */
    @SuppressLint("MissingPermission") // checked just below
    fun notify(ctx: Context, title: String, text: String, key: String = title) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(ctx)
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?.putExtra(EXTRA_OPEN_AUTOMATIONS, true)
        val pi = launch?.let {
            PendingIntent.getActivity(ctx, 7301, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
        // No-op if POST_NOTIFICATIONS isn't granted.
        runCatching { NotificationManagerCompat.from(ctx).notify(("auto:$key").hashCode(), n) }
    }
}
