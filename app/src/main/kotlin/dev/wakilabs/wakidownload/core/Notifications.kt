package dev.wakilabs.wakidownload.core

import android.Manifest
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.wakilabs.wakidownload.core.Target
import dev.wakilabs.wakidownload.core.TargetKind
import dev.wakilabs.wakidownload.R

object Notifications {
    const val CHANNEL_PROGRESS = "downloads"
    const val CHANNEL_DONE = "downloads_done"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.notif_channel_progress), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, context.getString(R.string.notif_channel_done), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun progress(context: Context, title: String, text: String?, max: Int, progress: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(max, progress, indeterminate)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    /** Tap opens the first file; the action opens the system Downloads view (Files). */
    fun completed(context: Context, id: Int, names: List<String>, firstUri: Uri?, firstMime: String?, target: Target = Target.LOCAL) {
        if (!canPost(context)) return
        val filesIntent = when (target.kind) {
            TargetKind.LOCAL -> Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            TargetKind.SAF -> Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(target.treeUri), android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
            else -> Intent(Intent.ACTION_VIEW, Uri.parse(if (target.kind == TargetKind.GDRIVE) "https://drive.google.com/drive/my-drive" else "https://onedrive.live.com/"))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val filesPending = PendingIntent.getActivity(context, id + 1, filesIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = if (firstUri != null) {
            Intent(Intent.ACTION_VIEW).setDataAndType(firstUri, firstMime ?: "*/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else filesIntent
        val openPending = PendingIntent.getActivity(context, id + 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(context.getString(R.string.notif_done_title, names.size))
            .setContentText(names.joinToString(", ") + " · " + target.name)
            .setStyle(NotificationCompat.BigTextStyle().bigText(names.joinToString("\n") + "\nSaved to " + target.name))
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_folder, context.getString(if (target.isCloud) R.string.notif_open_cloud else R.string.notif_show_in_files), filesPending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    fun failed(context: Context, id: Int, message: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(context.getString(R.string.notif_failed_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
