package dev.wakilabs.wakidownload.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast

/**
 * Owns the PackageInstaller result (mirrors WakiDrive's WakiInstallReceiver). PENDING_USER_ACTION
 * launches the OS confirmation, the only path Android allows; terminal statuses are persisted so
 * the main screen can report them even if the process was gone when they arrived.
 */
class InstallReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STATUS = "dev.wakilabs.wakidownload.updater.INSTALL_STATUS"
        const val PREFS = "waki_updater"
        const val KEY_LAST_STATUS = "last_status"
        const val KEY_LAST_MESSAGE = "last_message"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) { record(context, "failure", "installer confirmation unavailable"); return }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { record(context, "failure", "could not launch installer confirmation") }
            }
            PackageInstaller.STATUS_SUCCESS -> record(context, "success", message)
            PackageInstaller.STATUS_FAILURE_ABORTED -> record(context, "canceled", message)
            else -> record(context, "failure", "status=$status $message")
        }
    }

    private fun record(context: Context, status: String, message: String) {
        Log.i("WakiDownload", "install status=$status $message")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST_STATUS, status).putString(KEY_LAST_MESSAGE, message).apply()
        if (status == "failure") Toast.makeText(context, "Update failed: $message", Toast.LENGTH_LONG).show()
    }
}
