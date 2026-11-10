package io.github.daddymean.callscreen

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ScreeningService : CallScreeningService() {
    private val workers = Executors.newFixedThreadPool(2)
    private val effects = Executors.newFixedThreadPool(2)
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val pending = ConcurrentHashMap<ResponseGate, CancellationSignal>()

    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return
        val start = SystemClock.elapsedRealtime()
        val cancellation = CancellationSignal()
        val timer = AtomicReference<ScheduledFuture<*>?>()
        lateinit var gate: ResponseGate
        gate = ResponseGate(DEADLINE_MS, { SystemClock.elapsedRealtime() - start }) { action ->
            timer.get()?.cancel(false)
            pending.remove(gate)
            try {
                val response = CallResponse.Builder()
                    .setDisallowCall(action == Action.REJECT)
                    .setRejectCall(action == Action.REJECT)
                    .setSilenceCall(action == Action.SILENCE)
                    .setSkipNotification(false)
                    .build()
                respondToCall(details, response)
                // Provider cancellation and notification binder calls must not block watchdog.
                effects.execute {
                    try { cancellation.cancel() } catch (_: RuntimeException) { /* no logs */ }
                    notifyGeneric()
                }
            } catch (_: RuntimeException) {
                // No identifiers or exception messages in logs; Telecom timeout allows.
            }
        }
        pending[gate] = cancellation
        try {
            timer.set(watchdog.schedule({ gate.failOpen() }, DEADLINE_MS, TimeUnit.MILLISECONDS))
            workers.execute {
                gate.evaluate {
                    if (cancellation.isCanceled) return@evaluate Action.ALLOW
                    val rules = RuleStore(this)
                    val role = getSystemService(RoleManager::class.java) ?: throw IllegalStateException()
                    val roleHeld = role.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
                        role.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
                    val contactsGranted = checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
                        PackageManager.PERMISSION_GRANTED
                    val number = if (details.handle?.scheme == "tel") normalize(details.handle?.schemeSpecificPart) else null
                    val known = if (rules.active && roleHeld && contactsGranted && number != null) {
                        isContact(number, cancellation)
                    } else false
                    val chosen = decide(Input(rules.active, roleHeld, contactsGranted, number,
                        known, rules.allowlist, rules.unknownAction))
                    // Recheck immediately before response if the user revoked access during lookup.
                    if (!role.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) ||
                        checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                        Action.ALLOW
                    } else chosen
                }
            }
        } catch (_: RuntimeException) {
            gate.failOpen()
        }
    }

    private fun isContact(number: String, cancellation: CancellationSignal): Boolean {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val cursor = contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID),
            null, null, null, cancellation) ?: throw IllegalStateException()
        return cursor.use { it.moveToFirst() }
    }

    private fun notifyGeneric() {
        // Entire notification is generic, even when unlocked. Replaces the previous one.
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (!manager.areNotificationsEnabled()) return
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_body))
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setTimeoutAfter(30_000L)
                .build()
            manager.notify(1, notification)
        } catch (_: RuntimeException) {
            // Notification permission is optional and must never affect call decisions.
        }
    }

    override fun onDestroy() {
        pending.keys.forEach { it.failOpen() }
        workers.shutdownNow()
        watchdog.shutdownNow()
        effects.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val DEADLINE_MS = 2500L
        private const val CHANNEL = "screening_status"
    }
}
