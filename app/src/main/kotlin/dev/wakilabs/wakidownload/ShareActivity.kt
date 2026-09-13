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
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import dev.wakilabs.wakidownload.core.DownloadWorker
import dev.wakilabs.wakidownload.core.FileNames
import dev.wakilabs.wakidownload.core.LinkClassifier
import dev.wakilabs.wakidownload.core.Notifications
import dev.wakilabs.wakidownload.core.Settings
import dev.wakilabs.wakidownload.core.Source
import dev.wakilabs.wakidownload.core.Target
import dev.wakilabs.wakidownload.databinding.ActivityShareBinding
import dev.wakilabs.wakidownload.ui.Ext
import dev.wakilabs.wakidownload.ui.Ext.fadeIn
import dev.wakilabs.wakidownload.ui.Ext.fadeOut
import dev.wakilabs.wakidownload.ui.Ext.popIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The share target for links and files from any app. A bottom card: source icon, what was shared,
 * location chips (prefilled from the per-source default), the NSFW switch, then Save. After Save
 * the chooser collapses into a three-step stepper (Resolve, Download, Save) with live progress,
 * a "Found 1 video" line as soon as the link is resolved, and a thumbnail plus a check mark on
 * completion before the card dismisses itself. Shared files are staged here because their URI
 * grant dies with this activity.
 */
class ShareActivity : AppCompatActivity() {
    private lateinit var b: ActivityShareBinding
    private lateinit var settings: Settings
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { prepare() }
    private var prepared = false
    private var source: Source = Source.WEB
    private var sharedText: String? = null
    private var sharedUrl: String? = null
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
        b.nsfw.setOnCheckedChangeListener { _, checked -> select(if (checked) settings.nsfwTargetId else settings.defaultTargetId(source)) }
        if (Build.VERSION.SDK_INT >= 33 && !Notifications.canPost(this)) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else prepare()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        finish(); startActivity(intent)
    }

    private fun prepare() {
        if (prepared) return
        prepared = true
        val streams = streamUris()
        if (streams.isNotEmpty()) {
            source = Source.SHARED
            b.sourceIcon.setImageResource(Ext.sourceIcon("shared"))
            b.title.text = if (streams.size == 1) "1 file" else "${streams.size} files"
            b.status.text = intent.type ?: ""
            Glide.with(this).load(streams.first()).centerCrop().into(b.preview)
            b.sourceIcon.visibility = View.GONE
            fillChooser()
            b.save.isEnabled = false
            lifecycleScope.launch {
                val r = runCatching { withContext(Dispatchers.IO) { stage(streams) } }
                r.onSuccess { b.save.isEnabled = true; b.status.text = stagedFiles.joinToString(", ") { Ext.size(this@ShareActivity, it.length()) }; if (!settings.askEachTime) start() }
                    .onFailure { fail(it.message ?: "Cannot read the shared file") }
            }
            return
        }
        val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT)).joinToString(" ")
        val url = LinkClassifier.extractUrl(text)
        if (url == null) { bail(getString(R.string.share_no_link)); return }
        sharedText = text; sharedUrl = url
        source = LinkClassifier.classify(url).let { if (it == Source.UNKNOWN) Source.WEB else it }
        b.sourceIcon.setImageResource(Ext.sourceIcon(source.slug))
        b.title.text = source.label
        b.status.text = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        fillChooser()
        if (!settings.askEachTime) start()
    }

    private fun fillChooser() {
        b.targets.removeAllViews()
        settings.availableTargets().forEach { t ->
            val chip = Chip(this).apply { text = t.name; tag = t.id; isCheckable = true; id = View.generateViewId(); setEnsureMinTouchTargetSize(false) }
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

    private fun selectedTargetId(): String = (b.targets.findViewById<Chip>(b.targets.checkedChipId))?.tag as? String ?: Target.LOCAL_ID

    private fun steps(active: Int, done: Int) {
        listOf(b.step1, b.step2, b.step3).forEachIndexed { i, tv ->
            tv.isActivated = i == active; tv.isSelected = i < done
            val base = getString(listOf(R.string.step_resolve, R.string.step_download, R.string.step_save)[i])
            tv.text = if (i < done) "\u2713 $base" else base
        }
    }

    private fun start() {
        val targetId = selectedTargetId()
        val nsfw = b.nsfw.isChecked
        b.chooser.fadeOut()
        b.save.visibility = View.GONE
        b.cancel.setText(R.string.share_hide)
        b.steps.fadeIn(delayMs = 120)
        b.progress.fadeIn(delayMs = 120)
        steps(0, 0)
        b.status.text = getString(R.string.share_resolving)
        val id = if (stagedFiles.isNotEmpty()) DownloadWorker.enqueueFiles(this, stagedFiles, stagedMimes, targetId, nsfw)
        else DownloadWorker.enqueueLink(this, sharedText ?: return, targetId, nsfw, label = b.status.text.toString(), source = source.slug)
        stagedFiles = emptyList()
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(id).observe(this) { info -> render(info) }
    }

    private fun render(info: WorkInfo?) {
        info ?: return
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> { b.status.text = getString(R.string.share_resolving); steps(0, 0) }
            WorkInfo.State.RUNNING -> {
                val p = info.progress
                val stage = p.getString(DownloadWorker.KEY_STAGE)
                val pct = p.getInt(DownloadWorker.KEY_PERCENT, -1)
                val name = p.getString(DownloadWorker.KEY_NAME) ?: ""
                val count = p.getInt(DownloadWorker.KEY_COUNT, 1)
                val idx = p.getInt(DownloadWorker.KEY_INDEX, 0)
                val found = p.getString(DownloadWorker.KEY_FOUND)
                if (!found.isNullOrBlank() && b.title.text != getString(R.string.share_found, found)) { b.title.text = getString(R.string.share_found, found); b.title.popIn() }
                when (stage) {
                    DownloadWorker.STAGE_DOWNLOADING -> { steps(1, 1); b.status.text = if (count > 1) "$name  ·  ${idx + 1} of $count" else name }
                    DownloadWorker.STAGE_SAVING -> { steps(2, 2); b.status.text = "${getString(R.string.share_saving)} ${p.getString(DownloadWorker.KEY_TARGET) ?: ""}" }
                    else -> { steps(0, 0); b.status.text = getString(R.string.share_resolving) }
                }
                if (pct >= 0 && stage != DownloadWorker.STAGE_RESOLVING) { b.progress.isIndeterminate = false; b.progress.setProgressCompat(pct, true) } else b.progress.isIndeterminate = true
            }
            WorkInfo.State.SUCCEEDED -> {
                steps(-1, 3)
                b.progress.isIndeterminate = false; b.progress.setProgressCompat(100, true)
                val where = info.outputData.getString(DownloadWorker.KEY_TARGET) ?: Target.LOCAL.name
                val n = info.outputData.getInt(DownloadWorker.KEY_COUNT, 1)
                b.title.text = getString(R.string.share_done) + if (n > 1) " ($n)" else ""
                b.status.text = getString(R.string.share_saved_to, where)
                info.outputData.getString(DownloadWorker.KEY_FIRST_URI)?.let { u ->
                    if (u.startsWith("content")) { b.sourceIcon.fadeOut(); Glide.with(this).load(Uri.parse(u)).centerCrop().into(b.preview) }
                }
                b.doneIcon.visibility = View.VISIBLE; b.doneIcon.popIn()
                b.cancel.setText(R.string.share_close)
                b.root.postDelayed({ if (!isFinishing) finish() }, 1400)
            }
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> fail(info.outputData.getString(DownloadWorker.KEY_ERROR) ?: "Download failed")
        }
    }

    private fun fail(message: String) {
        b.progress.fadeOut(); b.chooser.fadeOut(); b.save.visibility = View.GONE
        b.title.text = getString(R.string.job_failed)
        b.status.text = message
        b.doneIcon.setImageResource(R.drawable.ic_error); b.doneIcon.visibility = View.VISIBLE; b.doneIcon.popIn()
        b.cancel.setText(R.string.share_close)
    }

    private fun bail(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); finish() }

    private fun streamUris(): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(uriExtra())
        Intent.ACTION_SEND_MULTIPLE -> uriListExtra()
        else -> emptyList()
    }

    @Suppress("DEPRECATION")
    private fun uriExtra(): Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun uriListExtra(): List<Uri> = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()

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
            contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: throw IllegalStateException("Cannot read shared file")
            files += f; mimes += mime
        }
        stagedFiles = files; stagedMimes = mimes
    }
}
