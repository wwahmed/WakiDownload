package dev.wakilabs.wakidownload

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.transition.MaterialFadeThrough
import dev.wakilabs.wakidownload.core.Notifications
import dev.wakilabs.wakidownload.databinding.ActivityMainBinding
import dev.wakilabs.wakidownload.ui.ActivityFragment
import dev.wakilabs.wakidownload.ui.LibraryFragment
import dev.wakilabs.wakidownload.ui.SettingsFragment

/** Three tabs: Library (what is saved), Activity (what is happening), Settings (where things go). */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.nav) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }

        b.nav.setOnItemSelectedListener { item ->
            val tag = when (item.itemId) { R.id.tab_activity -> TAG_ACTIVITY; R.id.tab_settings -> TAG_SETTINGS; else -> TAG_LIBRARY }
            show(tag); true
        }
        b.nav.setOnItemReselectedListener { }

        if (savedInstanceState == null) {
            show(if (intent.getStringExtra(EXTRA_TAB) == TAG_ACTIVITY) TAG_ACTIVITY.also { b.nav.selectedItemId = R.id.tab_activity } else TAG_LIBRARY)
            if (Build.VERSION.SDK_INT >= 33 && !Notifications.canPost(this)) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun show(tag: String) {
        val fm = supportFragmentManager
        val existing = fm.findFragmentByTag(tag)
        val tx = fm.beginTransaction()
        fm.fragments.forEach { f -> if (f.tag != tag) { f.exitTransition = MaterialFadeThrough(); tx.hide(f) } }
        val target: Fragment = existing ?: when (tag) {
            TAG_ACTIVITY -> ActivityFragment()
            TAG_SETTINGS -> SettingsFragment()
            else -> LibraryFragment()
        }.also { tx.add(R.id.host, it, tag) }
        target.enterTransition = MaterialFadeThrough()
        tx.show(target).setReorderingAllowed(true).commit()
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAG_LIBRARY = "library"
        const val TAG_ACTIVITY = "activity"
        const val TAG_SETTINGS = "settings"
    }
}
