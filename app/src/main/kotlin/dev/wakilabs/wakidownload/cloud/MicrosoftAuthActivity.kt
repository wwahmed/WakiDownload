package dev.wakilabs.wakidownload.cloud

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.wakilabs.wakidownload.MainActivity
import dev.wakilabs.wakidownload.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Receives `wakidownload://oauth/microsoft?code=...` from the browser and finishes the sign-in. */
class MicrosoftAuthActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) { finish(); return }
        lifecycleScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { OneDrive.completeAuth(this@MicrosoftAuthActivity, uri) } }
            r.onSuccess { account ->
                Settings(this@MicrosoftAuthActivity).onedriveAccount = account
                Toast.makeText(this@MicrosoftAuthActivity, "OneDrive connected: $account", Toast.LENGTH_LONG).show()
            }.onFailure { Toast.makeText(this@MicrosoftAuthActivity, it.message ?: "OneDrive sign-in failed", Toast.LENGTH_LONG).show() }
            startActivity(Intent(this@MicrosoftAuthActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
    }
}
