package com.mybrowser.backup

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mybrowser.MainActivity
import com.mybrowser.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A separate private process prevents cached repositories from overwriting restored data. */
class RestoreActivity : ComponentActivity() {
    private var running = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restore()
    }

    private fun restore() {
        if (running) return
        running = true
        content(busy = true)
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    BackupStorage.locked(this@RestoreActivity) {
                        // Only this app's browser process may be stopped. No PID is accepted
                        // from an intent; a recycled or foreign PID can never be targeted.
                        getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
                            .filter { it.uid == Process.myUid() && it.processName == packageName }
                            .forEach { Process.killProcess(it.pid) }
                        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
                        while (getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
                                .any { it.uid == Process.myUid() && it.processName == packageName }) {
                            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Browser process did not stop" }
                            android.os.SystemClock.sleep(20)
                        }
                        val storage = BackupStorage(this@RestoreActivity)
                        val recovered = storage.recoverIfNeeded()
                        if (!recovered) {
                            check(storage.hasPending()) { "No pending restore" }
                            storage.consumePending()
                        }
                        else if (recovered) storage.discardPending()
                        storage.writeResult(if (recovered) "recovered" else "restored")
                    }
                }.isSuccess
            }
            running = false
            if (success) {
                startActivity(Intent(this@RestoreActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finishAndRemoveTask()
                Process.killProcess(Process.myPid())
            } else content(busy = false)
        }
    }

    private fun content(busy: Boolean) {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(padding, padding * 2, padding, padding * 2)
        }
        root.addView(TextView(this).apply {
            text = getString(if (busy) R.string.backup_restoring else R.string.backup_recovery_failed)
            textSize = 18f; gravity = Gravity.CENTER
            setPadding(0, padding, 0, padding)
        })
        if (busy) root.addView(ProgressBar(this))
        else root.addView(Button(this).apply { setText(R.string.recovery_retry); setOnClickListener { restore() } })
        setContentView(root)
    }
}
