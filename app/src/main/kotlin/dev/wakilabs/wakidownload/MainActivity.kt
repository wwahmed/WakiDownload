package dev.wakilabs.wakidownload

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.wakilabs.wakidownload.cloud.GoogleDrive
import dev.wakilabs.wakidownload.cloud.OneDrive
import dev.wakilabs.wakidownload.core.DownloadHistory
import dev.wakilabs.wakidownload.core.HistoryEntry
import dev.wakilabs.wakidownload.core.Notifications
import dev.wakilabs.wakidownload.core.Settings
import dev.wakilabs.wakidownload.core.Source
import dev.wakilabs.wakidownload.core.Target
import dev.wakilabs.wakidownload.core.TargetKind
import dev.wakilabs.wakidownload.core.TargetWriter
import dev.wakilabs.wakidownload.databinding.ActivityMainBinding
import dev.wakilabs.wakidownload.databinding.ItemDownloadBinding
import dev.wakilabs.wakidownload.databinding.RowSettingBinding
import dev.wakilabs.wakidownload.updater.InstallReceiver
import dev.wakilabs.wakidownload.updater.UpdateManifest
import dev.wakilabs.wakidownload.updater.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var settings: Settings
    private val adapter = HistoryAdapter { open(it) }
    private var checking = false

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) onFolderPicked(uri) }

    private val googleConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == RESULT_OK) finishGoogleConnect() else Toast.makeText(this, "Google Drive not connected", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        settings = Settings(this)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.checkUpdates.setOnClickListener { checkForUpdates(manual = true) }
        b.updateStatus.text = getString(R.string.update_status_idle, BuildConfig.VERSION_NAME)
        b.askEachTime.setOnCheckedChangeListener { _, on -> settings.askEachTime = on }

        if (Build.VERSION.SDK_INT >= 33 && !Notifications.canPost(this)) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) {
            reportLastInstall()
            checkForUpdates(manual = false)
        }
    }

    override fun onResume() {
        super.onResume()
        renderSettings()
        val entries = DownloadHistory(this).all()
        adapter.submit(entries)
        b.empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    // Settings

    private fun renderSettings() {
        b.locations.removeAllViews()
        row(b.locations, Target.LOCAL.name, getString(R.string.default_folder_hint)) { openFolder() }

        val g = settings.gdriveAccount
        row(b.locations, getString(R.string.gdrive), if (g != null) getString(R.string.connected_as, g, settings.gdriveFolder) else getString(R.string.not_connected),
            action = if (g == null) getString(R.string.connect) else null, onAction = { connectGoogle() }) {
            if (g != null) cloudMenu(TargetKind.GDRIVE) else connectGoogle()
        }
        val o = settings.onedriveAccount
        row(b.locations, getString(R.string.onedrive), when { o != null -> getString(R.string.connected_as, o, settings.onedriveFolder); !OneDrive.configured -> getString(R.string.needs_setup); else -> getString(R.string.not_connected) },
            action = if (o == null && OneDrive.configured) getString(R.string.connect) else null, onAction = { connectMicrosoft() }) {
            if (o != null) cloudMenu(TargetKind.ONEDRIVE) else if (OneDrive.configured) connectMicrosoft() else Toast.makeText(this, R.string.needs_setup, Toast.LENGTH_LONG).show()
        }
        settings.safTargets().forEach { t -> row(b.locations, t.name, "Folder from Files") { safMenu(t) } }
        row(b.locations, getString(R.string.add_folder), getString(R.string.custom_folder_hint)) { pickFolder.launch(null) }

        // Defaults (keep the switch, which is the first child)
        while (b.defaults.childCount > 1) b.defaults.removeViewAt(1)
        b.askEachTime.isChecked = settings.askEachTime
        listOf(Source.TWITTER to R.string.default_twitter, Source.TIKTOK to R.string.default_tiktok, Source.WEB to R.string.default_web, Source.SHARED to R.string.default_shared).forEach { (src, label) ->
            row(b.defaults, getString(label), settings.target(settings.defaultTargetId(src)).name) { pickTarget(getString(label), settings.defaultTargetId(src)) { settings.setDefaultTargetId(src, it) } }
        }
        val nsfwTarget = settings.target(settings.nsfwTargetId)
        row(b.defaults, getString(R.string.default_nsfw), nsfwTarget.name + if (nsfwTarget.kind != TargetKind.SAF) " / ${settings.nsfwSubfolder}" else "") {
            pickTarget(getString(R.string.default_nsfw), settings.nsfwTargetId) { settings.nsfwTargetId = it }
        }
        row(b.defaults, getString(R.string.nsfw_subfolder), settings.nsfwSubfolder) { promptText(getString(R.string.nsfw_subfolder), settings.nsfwSubfolder) { settings.nsfwSubfolder = it; renderSettings() } }
    }

    private fun row(parent: ViewGroup, title: String, subtitle: String, action: String? = null, onAction: (() -> Unit)? = null, onClick: () -> Unit) {
        val r = RowSettingBinding.inflate(layoutInflater, parent, false)
        r.title.text = title
        r.subtitle.text = subtitle
        if (action != null) { r.action.text = action; r.action.visibility = View.VISIBLE; r.action.setOnClickListener { onAction?.invoke() } }
        r.root.setOnClickListener { onClick() }
        parent.addView(r.root)
    }

    private fun pickTarget(title: String, currentId: String, onPick: (String) -> Unit) {
        val targets = settings.availableTargets()
        val idx = targets.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this).setTitle(title)
            .setSingleChoiceItems(targets.map { it.name }.toTypedArray(), idx) { d, i -> onPick(targets[i].id); d.dismiss(); renderSettings() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun promptText(title: String, current: String, onDone: (String) -> Unit) {
        val input = EditText(this).apply { setText(current); inputType = InputType.TYPE_CLASS_TEXT; setSelection(current.length) }
        val wrap = FrameLayout(this).apply { val p = (20 * resources.displayMetrics.density).toInt(); setPadding(p, 0, p, 0); addView(input) }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(wrap)
            .setPositiveButton(android.R.string.ok) { _, _ -> onDone(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun cloudMenu(kind: TargetKind) {
        val isDrive = kind == TargetKind.GDRIVE
        val items = arrayOf(getString(R.string.change_folder), getString(R.string.disconnect))
        MaterialAlertDialogBuilder(this).setTitle(if (isDrive) R.string.gdrive else R.string.onedrive).setItems(items) { _, i ->
            when (i) {
                0 -> promptText(getString(R.string.change_folder), if (isDrive) settings.gdriveFolder else settings.onedriveFolder) {
                    if (isDrive) settings.gdriveFolder = it else settings.onedriveFolder = it
                    renderSettings()
                }
                1 -> { if (isDrive) settings.gdriveAccount = null else { OneDrive.signOut(this); settings.onedriveAccount = null }; renderSettings() }
            }
        }.show()
    }

    private fun safMenu(t: Target) {
        MaterialAlertDialogBuilder(this).setTitle(t.name).setItems(arrayOf(getString(R.string.rename), getString(R.string.remove))) { _, i ->
            when (i) {
                0 -> promptText(getString(R.string.rename), t.name) { settings.renameSafTarget(t.id, it.ifBlank { t.name }); renderSettings() }
                1 -> {
                    runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(t.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                    settings.removeSafTarget(t.id); renderSettings()
                }
            }
        }.show()
    }

    private fun onFolderPicked(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .onFailure { Toast.makeText(this, "Could not keep access to that folder", Toast.LENGTH_LONG).show(); return }
        val suggested = TargetWriter.describeTree(this, uri)
        promptText(getString(R.string.rename), suggested) { name -> settings.addSafTarget(name.ifBlank { suggested }, uri.toString()); renderSettings() }
    }

    // Google Drive: Play Services authorization with the device's accounts

    private fun connectGoogle() {
        Identity.getAuthorizationClient(this).authorize(GoogleDrive.request())
            .addOnSuccessListener { r ->
                if (r.hasResolution()) googleConsent.launch(IntentSenderRequest.Builder(r.pendingIntent!!).build()) else finishGoogleConnect()
            }
            .addOnFailureListener { e -> Toast.makeText(this, "Google Drive: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun finishGoogleConnect() {
        lifecycleScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { GoogleDrive.whoAmI(GoogleDrive.accessToken(this@MainActivity)) } }
            r.onSuccess { settings.gdriveAccount = it; renderSettings(); Toast.makeText(this@MainActivity, "Google Drive connected: $it", Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(this@MainActivity, it.message ?: "Google Drive failed", Toast.LENGTH_LONG).show() }
        }
    }

    private fun connectMicrosoft() {
        runCatching { startActivity(OneDrive.beginAuth(this)) }
            .onFailure { Toast.makeText(this, it.message ?: "OneDrive sign-in failed", Toast.LENGTH_LONG).show() }
    }

    // Recent downloads

    private fun openFolder() {
        runCatching { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
            .onFailure { Toast.makeText(this, "No Files app found", Toast.LENGTH_SHORT).show() }
    }

    private fun open(e: HistoryEntry) {
        val uri = Uri.parse(e.uri)
        val i = if (uri.scheme == "https" || uri.scheme == "http") Intent(Intent.ACTION_VIEW, uri)
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(i) }.onFailure { openFolder() }
    }

    // Updater

    private fun reportLastInstall() {
        val prefs = getSharedPreferences(InstallReceiver.PREFS, MODE_PRIVATE)
        val status = prefs.getString(InstallReceiver.KEY_LAST_STATUS, null) ?: return
        prefs.edit().remove(InstallReceiver.KEY_LAST_STATUS).remove(InstallReceiver.KEY_LAST_MESSAGE).apply()
        if (status == "success") Toast.makeText(this, "Updated to ${BuildConfig.VERSION_NAME}", Toast.LENGTH_LONG).show()
    }

    private fun checkForUpdates(manual: Boolean) {
        if (checking) return
        checking = true
        b.updateStatus.text = getString(R.string.update_status_checking)
        lifecycleScope.launch {
            val result = runCatching { Updater.checkForUpdate(this@MainActivity) }
            checking = false
            result.onSuccess { m ->
                if (m == null) b.updateStatus.text = getString(R.string.update_status_current, BuildConfig.VERSION_NAME)
                else { b.updateStatus.text = getString(R.string.update_status_available, m.version); offerUpdate(m) }
            }.onFailure { t ->
                b.updateStatus.text = if (manual) getString(R.string.update_status_error, t.message ?: t.javaClass.simpleName)
                else getString(R.string.update_status_idle, BuildConfig.VERSION_NAME)
            }
        }
    }

    private fun offerUpdate(m: UpdateManifest) {
        val size = if (m.byteSize > 0) Formatter.formatShortFileSize(this, m.byteSize) else "size unknown"
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_dialog_title, m.version))
            .setMessage(getString(R.string.update_dialog_body, m.version, size) + if (m.releaseNotes.isNotBlank()) "\n\n${m.releaseNotes}" else "")
            .setPositiveButton(R.string.update_install) { _, _ -> installUpdate(m) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun installUpdate(m: UpdateManifest) {
        if (!Updater.canInstall(this)) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.update_allow_title).setMessage(R.string.update_allow_body)
                .setPositiveButton(R.string.update_allow_open) { _, _ -> Updater.openInstallSettings(this) }
                .setNegativeButton(R.string.update_later, null).show()
            return
        }
        b.checkUpdates.isEnabled = false
        lifecycleScope.launch {
            val r = runCatching {
                val file = Updater.download(this@MainActivity, m) { pct -> runOnUiThread { b.updateStatus.text = getString(R.string.update_downloading, pct.coerceAtLeast(0)) } }
                b.updateStatus.text = getString(R.string.update_verifying)
                withContext(Dispatchers.IO) { Updater.verify(this@MainActivity, file, m); Updater.install(this@MainActivity, file) }
            }
            b.checkUpdates.isEnabled = true
            r.onFailure { b.updateStatus.text = getString(R.string.update_failed, it.message ?: it.javaClass.simpleName) }
            r.onSuccess { b.updateStatus.text = getString(R.string.update_status_available, m.version) }
        }
    }

    private class HistoryAdapter(val onClick: (HistoryEntry) -> Unit) : RecyclerView.Adapter<HistoryAdapter.VH>() {
        private var items: List<HistoryEntry> = emptyList()
        fun submit(list: List<HistoryEntry>) { items = list; notifyDataSetChanged() }
        class VH(val b: ItemDownloadBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            val ctx = h.b.root.context
            h.b.name.text = e.name
            val source = Source.values().firstOrNull { it.slug == e.source }?.label ?: e.source
            val ago = DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            h.b.meta.text = listOf(source, Formatter.formatShortFileSize(ctx, e.bytes), e.target + if (e.nsfw) " (NSFW)" else "", ago).joinToString(" · ")
            h.b.icon.setImageResource(if (e.mime.startsWith("image/")) R.drawable.ic_image else R.drawable.ic_video)
            h.b.root.setOnClickListener { onClick(e) }
        }
    }
}
