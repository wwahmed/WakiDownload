package dev.wakilabs.wakidownload

import android.app.Application
import dev.wakilabs.wakidownload.core.Notifications

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }
}
