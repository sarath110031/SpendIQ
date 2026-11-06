package com.spendiq.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.accessibility.AccessibilityEvent
import com.spendiq.app.data.db.AppDatabase
import com.spendiq.app.data.repository.SpendIQRepository
import com.spendiq.app.ui.SpendingAlertActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SpendingMonitorAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: SpendIQRepository

    private val monitoredPackages = setOf(
        "com.google.android.apps.nbu.paisa.user", // Google Pay
        "com.phonepe.app",                       // PhonePe
        "net.one97.paytm",                       // Paytm
        "in.amazon.mShop.android.shopping",      // Amazon Pay
        "com.cred.app"                           // CRED
    )

    private var lastAlertTime = 0L
    private val alertCooldownMillis = 30_000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        val db = AppDatabase.getInstance(applicationContext)
        repository = SpendIQRepository(db)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: return

        if (monitoredPackages.contains(packageName)) {
            val now = System.currentTimeMillis()
            if (now - lastAlertTime < alertCooldownMillis) return

            serviceScope.launch {
                try {
                    val (isExceeded, overspendAmount) = repository.checkDiscretionaryBudgetExceeded()

                    if (isExceeded) {
                        lastAlertTime = now
                        triggerHapticFeedback()

                        val targetAppName = when (packageName) {
                            "com.google.android.apps.nbu.paisa.user" -> "Google Pay"
                            "com.phonepe.app" -> "PhonePe"
                            "net.one97.paytm" -> "Paytm"
                            "in.amazon.mShop.android.shopping" -> "Amazon Pay"
                            else -> "Payment App"
                        }

                        val intent = Intent(applicationContext, SpendingAlertActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            putExtra(SpendingAlertActivity.EXTRA_PACKAGE_NAME, packageName)
                            putExtra(SpendingAlertActivity.EXTRA_APP_NAME, targetAppName)
                            putExtra(SpendingAlertActivity.EXTRA_OVERSPENT_AMOUNT, overspendAmount)
                        }
                        startActivity(intent)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onInterrupt() {}

    private fun triggerHapticFeedback() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(120)
            }
        } catch (_: Exception) {}
    }
}