package dev.wakilabs.wakidownload

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.chip.Chip
import dev.wakilabs.wakidownload.core.DownloadWorker
import dev.wakilabs.wakidownload.core.FileNames
import dev.wakilabs.wakidownload.core.LinkClassifier
import dev.wakilabs.wakidownload.core.Notifications
import dev.wakilabs.wakidownload.core.Settings
import dev.wakilabs.wakidownload.core.Source
import dev.wakilabs.wakidownload.core.Target
import dev.wakilabs.wakidownload.databinding.ActivityShareBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The share target for links (any app) and files (any app). Shows a chooser card: save location
 * chips prefilled from the per-source default, an NSFW checkbox that flips to the NSFW target,
 * then hands the job to [DownloadWorker] and follows its progress until done. Shared files are
 * staged into the cache here because their URI grant dies with this activity.
 */
class ShareActivity : AppCompatActivity() {
    private lateinit var b: ActivityShareBinding
    private lateinit var settings: Settings
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { prepare() }
    private var prepared = false
    private var source: Source = Source.WEB
    private var sharedText: String? = null
    private var stagedFiles: List<File> = emptyList()
    private var stagedMimes: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityShareBinding.inflate(layoutInflater)
        setContentView(b.root)
        settings = Settings(this)
        b.scrim.setOnClickListener { finish() }
        b.card.setOnClickListener { }
        b.cancel.setOnClickListener { finish() }
        b.save.setOnClickListener { start() }
        b.nsfw.setOnCheckedChangeListener { _, checked -> if (checked) select(settings.nsfwTargetId) else select(settings.defaultTargetId(source)) }

        if (Build.VERSION.SDK_INT >= 33 && !Notifications.canPost(this)) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else prepare()
    }

    /** A second share while a card is still up (for example after a failure) starts over with the new intent. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        finish()
        startActivity(intent)
    }

    /** Works out what was shared, fills the chooser, and starts right away when the user turned asking off. */
    private fun prepare() {
        if (prepared) return
        prepared = true
        val streams = streamUris()
        if (streams.isNotEmpty()) {
            source = Source.SHARED
            b.status.text = if (streams.size == 1) "1 file" else "${streams.size} files"
            fillChooser()
            b.save.isEnabled = false
            lifecycleScope.launch {
                val r = runCatching { withContext(Dispatchers.IO) { stage(streams) } }
                r.onSuccess { b.save.isEnabled = true; if (!settings.askEachTime) start() }
                    .onFailure { fail(it.message ?: "Cannot read the shared file") }
            }
            return
        }
        val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT)).joinToString(" ")
        val url = LinkClassifier.extractUrl(text)
        if (url == null) { bail(getString(R.string.share_no_link)); return }
        sharedText = text
        source = LinkClassifier.classify(url).let { if (it == Source.UNKNOWN) Source.WEB else it }
        b.status.text = "${source.label}: $url"
        fillChooser()
        if (!settings.askEachTime) start()
    }

    private fun fillChooser() {
        b.targets.removeAllViews()
        settings.availableTargets().forEach { t ->
            val chip = Chip(this).apply { text = t.name; tag = t.id; isCheckable = true; id = View.generateViewId() }
            b.targets.addView(chip)
        }
        select(settings.defaultTargetId(source))
    }

    private fun select(targetId: String) {
        for (i in 0 until b.targets.childCount) {
            val c = b.targets.getChildAt(i) as Chip
            if (c.tag == targetId) { b.targets.check(c.id); return }
        }
        (b.targets.getChildAt(0) as? Chip)?.let { b.targets.check(it.id) }
    }

    private fun selectedTargetId(): String =
        (b.targets.findViewById<Chip>(b.targets.checkedChipId))?.tag as? String ?: Target.LOCAL_ID

    private fun start() {
        val targetId = selectedTargetId()
        val nsfw = b.nsfw.isChecked
        b.chooser.visibility = View.GONE
        b.save.visibility = View.GONE
        b.cancel.setText(R.string.share_hide)
        b.progress.visibility = View.VISIBLE
        b.status.text = getString(R.string.share_resolving)
        val id = if (stagedFiles.isNotEmpty()) DownloadWorker.enqueueFiles(this, stagedFiles, stagedMimes, targetId, nsfw)
        else DownloadWorker.enqueueLink(this, sharedText ?: return, targetId, nsfw)
        stagedFiles = emptyList()
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(id).observe(this) { info -> render(info) }
    }

    private fun render(info: WorkInfo?) {
        info ?: return
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> b.status.text = getString(R.string.share_resolving)
            WorkInfo.State.RUNNING -> {
                val p = info.progress
                val stage = p.getString(DownloadWorker.KEY_STAGE)
                if (stage == DownloadWorker.STAGE_DOWNLOADING || stage == DownloadWorker.STAGE_SAVING) {
                    val pct = p.getInt(DownloadWorker.KEY_PERCENT, -1)
                    val name = p.getString(DownloadWorker.KEY_NAME) ?: ""
                    val count = p.getInt(DownloadWorker.KEY_COUNT, 1)
                    val idx = p.getInt(DownloadWorker.KEY_INDEX, 0)
                    val verb = if (stage == DownloadWorker.STAGE_SAVING) getString(R.string.share_saving) + " " + (p.getString(DownloadWorker.KEY_TARGET) ?: "") else getString(R.string.share_downloading)
                    b.status.text = if (count > 1) "$verb $name (${idx + 1}/$count)" else "$verb $name"
                    if (pct >= 0) { b.progress.isIndeterminate = false; b.progress.setProgressCompat(pct, true) } else b.progress.isIndeterminate = true
                } else b.status.text = getString(R.string.share_resolving)
            }
            WorkInfo.State.SUCCEEDED -> {
                val where = info.outputData.getString(DownloadWorker.KEY_TARGET) ?: Target.LOCAL.name
                Toast.makeText(this, getString(R.string.share_saved_to, where), Toast.LENGTH_LONG).show()
                finish()
            }
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> fail(info.outputData.getString(DownloadWorker.KEY_ERROR) ?: "Download failed")
        }
    }

    private fun fail(message: String) {
        b.progress.visibility = View.GONE
        b.chooser.visibility = View.GONE
        b.save.visibility = View.GONE
        b.status.text = message
        b.cancel.setText(R.string.share_close)
    }

    private fun bail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun streamUris(): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(uriExtra())
        Intent.ACTION_SEND_MULTIPLE -> uriListExtra()
        else -> emptyList()
    }

    @Suppress("DEPRECATION")
    private fun uriExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun uriListExtra(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()

    /** Copies each shared stream into the cache under its final display name; the worker moves it on. */
    private fun stage(uris: List<Uri>) {
        val dir = File(cacheDir, "staging/share-${System.nanoTime()}").apply { mkdirs() }
        val files = mutableListOf<File>()
        val mimes = mutableListOf<String>()
        val id = System.nanoTime().toString()
        uris.forEachIndexed { i, uri ->
            val mime = contentResolver.getType(uri) ?: intent.type?.takeIf { !it.contains('*') } ?: "application/octet-stream"
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: FileNames.extFromUrl(uri.toString(), "bin")
            val name = FileNames.build(Source.SHARED, id, ext, i, uris.size)
            val f = File(dir, name)
            contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("Cannot read shared file")
            files += f; mimes += mime
        }
        stagedFiles = files; stagedMimes = mimes
    }
}
