package dev.wakilabs.wakidownload

import android.app.Application
import com.google.android.material.color.DynamicColors
import dev.wakilabs.wakidownload.core.Notifications

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: the palette follows the wallpaper on Android 12+, the teal seed elsewhere.
        DynamicColors.applyToActivitiesIfAvailable(this)
        Notifications.ensureChannels(this)
    }
}
