package dev.wakilabs.wakidownload.ui

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import dev.wakilabs.wakidownload.R
import dev.wakilabs.wakidownload.core.HistoryEntry
import dev.wakilabs.wakidownload.core.Source

/** Small view helpers shared by the tabs and the share card. */
object Ext {
    fun sourceLabel(slug: String): String = Source.values().firstOrNull { it.slug == slug }?.label ?: slug

    fun sourceIcon(slug: String): Int = when (slug) {
        "twitter" -> R.drawable.ic_src_twitter
        "tiktok" -> R.drawable.ic_src_tiktok
        "shared" -> R.drawable.ic_src_file
        else -> R.drawable.ic_src_web
    }

    fun ago(time: Long): CharSequence = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)

    fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun metaLine(context: Context, e: HistoryEntry): String =
        listOf(sourceLabel(e.source), size(context, e.bytes), ago(e.time).toString()).joinToString("  ·  ")

    /** Press feedback: a quick spring scale down and back. */
    fun View.pressSpring() {
        val down = SpringAnimation(this, DynamicAnimation.SCALE_X, 0.96f).apply { spring.stiffness = SpringForce.STIFFNESS_HIGH; spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY }
        val downY = SpringAnimation(this, DynamicAnimation.SCALE_Y, 0.96f).apply { spring.stiffness = SpringForce.STIFFNESS_HIGH; spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY }
        down.addEndListener { _, _, _, _ ->
            SpringAnimation(this, DynamicAnimation.SCALE_X, 1f).apply { spring.stiffness = SpringForce.STIFFNESS_MEDIUM; spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY }.start()
            SpringAnimation(this, DynamicAnimation.SCALE_Y, 1f).apply { spring.stiffness = SpringForce.STIFFNESS_MEDIUM; spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY }.start()
        }
        down.start(); downY.start()
    }

    fun View.popIn(delayMs: Long = 0) {
        scaleX = 0.6f; scaleY = 0.6f; alpha = 0f
        animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(delayMs).setDuration(360).setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    fun View.fadeIn(duration: Long = 220, delayMs: Long = 0) {
        if (visibility == View.VISIBLE && alpha == 1f) return
        alpha = 0f; visibility = View.VISIBLE
        animate().alpha(1f).setStartDelay(delayMs).setDuration(duration).start()
    }

    fun View.fadeOut(duration: Long = 160, gone: Boolean = true) {
        if (visibility != View.VISIBLE) return
        animate().alpha(0f).setDuration(duration).withEndAction { visibility = if (gone) View.GONE else View.INVISIBLE }.start()
    }
}
