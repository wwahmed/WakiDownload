package dev.wakilabs.wakidownload.ui

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.wakilabs.wakidownload.BuildConfig
import dev.wakilabs.wakidownload.R
import dev.wakilabs.wakidownload.cloud.GoogleDrive
import dev.wakilabs.wakidownload.cloud.OneDrive
import dev.wakilabs.wakidownload.core.Settings
import dev.wakilabs.wakidownload.core.Source
import dev.wakilabs.wakidownload.core.Target
import dev.wakilabs.wakidownload.core.TargetKind
import dev.wakilabs.wakidownload.core.TargetWriter
import dev.wakilabs.wakidownload.databinding.FragmentSettingsBinding
import dev.wakilabs.wakidownload.databinding.RowSettingBinding
import dev.wakilabs.wakidownload.updater.InstallReceiver
import dev.wakilabs.wakidownload.updater.UpdateManifest
import dev.wakilabs.wakidownload.updater.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Save locations, per-source defaults, updater and about. */
class SettingsFragment : Fragment() {
    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!
    private lateinit var settings: Settings
    private var checking = false
    private var updateStatus: String? = null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) onFolderPicked(uri) }
    private val googleConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) finishGoogleConnect() else toast("Google Drive not connected")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = Settings(requireContext())
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets -> v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets }
        b.askEachTime.setOnCheckedChangeListener { _, on -> settings.askEachTime = on }
        if (savedInstanceState == null) { reportLastInstall(); checkForUpdates(manual = false) }
    }

    override fun onResume() { super.onResume(); render() }
    override fun onDestroyView() { super.onDestroyView(); _b = null }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()

    private fun render() {
        val ctx = requireContext()
        b.locations.removeAllViews()
        row(b.locations, Target.LOCAL.name, getString(R.string.default_folder_hint), icon = R.drawable.ic_phone) { openFolder() }
        val g = settings.gdriveAccount
        row(b.locations, getString(R.string.gdrive), if (g != null) getString(R.string.connected_as, g, settings.gdriveFolder) else getString(R.string.not_connected), icon = R.drawable.ic_cloud_gdrive,
            action = if (g == null) getString(R.string.connect) else null, onAction = { connectGoogle() }) { if (g != null) cloudMenu(TargetKind.GDRIVE) else connectGoogle() }
        val o = settings.onedriveAccount
        row(b.locations, getString(R.string.onedrive), when { o != null -> getString(R.string.connected_as, o, settings.onedriveFolder); !OneDrive.configured -> getString(R.string.needs_setup); else -> getString(R.string.not_connected) }, icon = R.drawable.ic_cloud_onedrive,
            action = if (o == null && OneDrive.configured) getString(R.string.connect) else null, onAction = { connectMicrosoft() }) { if (o != null) cloudMenu(TargetKind.ONEDRIVE) else if (OneDrive.configured) connectMicrosoft() else toast(getString(R.string.needs_setup)) }
        settings.safTargets().forEach { t -> row(b.locations, t.name, "Folder from Files", icon = R.drawable.ic_folder_open) { safMenu(t) } }
        row(b.locations, getString(R.string.add_folder), getString(R.string.custom_folder_hint), icon = R.drawable.ic_folder) { pickFolder.launch(null) }

        while (b.defaults.childCount > 1) b.defaults.removeViewAt(1)
        b.askEachTime.isChecked = settings.askEachTime
        listOf(Source.TWITTER to R.string.default_twitter, Source.TIKTOK to R.string.default_tiktok, Source.WEB to R.string.default_web, Source.SHARED to R.string.default_shared).forEach { (src, label) ->
            row(b.defaults, getString(label), settings.target(settings.defaultTargetId(src)).name, icon = Ext.sourceIcon(src.slug)) { pickTarget(getString(label), settings.defaultTargetId(src)) { settings.setDefaultTargetId(src, it) } }
        }
        val nsfwTarget = settings.target(settings.nsfwTargetId)
        row(b.defaults, getString(R.string.default_nsfw), nsfwTarget.name + if (nsfwTarget.kind != TargetKind.SAF) " / ${settings.nsfwSubfolder}" else "") { pickTarget(getString(R.string.default_nsfw), settings.nsfwTargetId) { settings.nsfwTargetId = it } }
        row(b.defaults, getString(R.string.nsfw_subfolder), settings.nsfwSubfolder) { promptText(getString(R.string.nsfw_subfolder), settings.nsfwSubfolder) { settings.nsfwSubfolder = it; render() } }

        b.appRows.removeAllViews()
        row(b.appRows, getString(R.string.check_updates), updateStatus ?: getString(R.string.update_status_idle, BuildConfig.VERSION_NAME), icon = R.drawable.ic_update) { checkForUpdates(manual = true) }
        row(b.appRows, getString(R.string.version_row, BuildConfig.VERSION_NAME), BuildConfig.REPO_URL, icon = R.drawable.ic_info) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.REPO_URL))) } }
    }

    private fun row(parent: ViewGroup, title: String, subtitle: String, icon: Int? = null, action: String? = null, onAction: (() -> Unit)? = null, onClick: () -> Unit) {
        val r = RowSettingBinding.inflate(layoutInflater, parent, false)
        r.title.text = title; r.subtitle.text = subtitle
        if (icon != null) { r.icon.setImageResource(icon); r.icon.visibility = View.VISIBLE }
        if (action != null) { r.action.text = action; r.action.visibility = View.VISIBLE; r.action.setOnClickListener { onAction?.invoke() } }
        r.root.setOnClickListener { onClick() }
        parent.addView(r.root)
    }

    private fun setUpdateStatus(text: String) { updateStatus = text; if (_b != null) render() }

    private fun pickTarget(title: String, currentId: String, onPick: (String) -> Unit) {
        val targets = settings.availableTargets()
        val idx = targets.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext()).setTitle(title)
            .setSingleChoiceItems(targets.map { it.name }.toTypedArray(), idx) { d, i -> onPick(targets[i].id); d.dismiss(); render() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun promptText(title: String, current: String, onDone: (String) -> Unit) {
        val input = EditText(requireContext()).apply { setText(current); inputType = InputType.TYPE_CLASS_TEXT; setSelection(current.length) }
        val wrap = FrameLayout(requireContext()).apply { val p = (20 * resources.displayMetrics.density).toInt(); setPadding(p, 0, p, 0); addView(input) }
        MaterialAlertDialogBuilder(requireContext()).setTitle(title).setView(wrap)
            .setPositiveButton(android.R.string.ok) { _, _ -> onDone(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun cloudMenu(kind: TargetKind) {
        val isDrive = kind == TargetKind.GDRIVE
        MaterialAlertDialogBuilder(requireContext()).setTitle(if (isDrive) R.string.gdrive else R.string.onedrive)
            .setItems(arrayOf(getString(R.string.change_folder), getString(R.string.disconnect))) { _, i ->
                when (i) {
                    0 -> promptText(getString(R.string.change_folder), if (isDrive) settings.gdriveFolder else settings.onedriveFolder) { if (isDrive) settings.gdriveFolder = it else settings.onedriveFolder = it; render() }
                    1 -> { if (isDrive) settings.gdriveAccount = null else { OneDrive.signOut(requireContext()); settings.onedriveAccount = null }; render() }
                }
            }.show()
    }

    private fun safMenu(t: Target) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(t.name).setItems(arrayOf(getString(R.string.rename), getString(R.string.remove))) { _, i ->
            when (i) {
                0 -> promptText(getString(R.string.rename), t.name) { settings.renameSafTarget(t.id, it.ifBlank { t.name }); render() }
                1 -> {
                    runCatching { requireContext().contentResolver.releasePersistableUriPermission(Uri.parse(t.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                    settings.removeSafTarget(t.id); render()
                }
            }
        }.show()
    }

    private fun onFolderPicked(uri: Uri) {
        runCatching { requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .onFailure { toast("Could not keep access to that folder"); return }
        val suggested = TargetWriter.describeTree(requireContext(), uri)
        promptText(getString(R.string.rename), suggested) { name -> settings.addSafTarget(name.ifBlank { suggested }, uri.toString()); render() }
    }

    private fun connectGoogle() {
        Identity.getAuthorizationClient(requireActivity()).authorize(GoogleDrive.request())
            .addOnSuccessListener { r -> if (r.hasResolution()) googleConsent.launch(IntentSenderRequest.Builder(r.pendingIntent!!).build()) else finishGoogleConnect() }
            .addOnFailureListener { e -> toast("Google Drive: ${e.message}") }
    }

    private fun finishGoogleConnect() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            val r = runCatching { withContext(Dispatchers.IO) { GoogleDrive.whoAmI(GoogleDrive.accessToken(ctx)) } }
            r.onSuccess { settings.gdriveAccount = it; render(); toast("Google Drive connected: $it") }.onFailure { toast(it.message ?: "Google Drive failed") }
        }
    }

    private fun connectMicrosoft() {
        runCatching { startActivity(OneDrive.beginAuth(requireContext())) }.onFailure { toast(it.message ?: "OneDrive sign-in failed") }
    }

    private fun openFolder() {
        runCatching { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }.onFailure { toast("No Files app found") }
    }

    private fun reportLastInstall() {
        val prefs = requireContext().getSharedPreferences(InstallReceiver.PREFS, android.content.Context.MODE_PRIVATE)
        val status = prefs.getString(InstallReceiver.KEY_LAST_STATUS, null) ?: return
        prefs.edit().remove(InstallReceiver.KEY_LAST_STATUS).remove(InstallReceiver.KEY_LAST_MESSAGE).apply()
        if (status == "success") toast("Updated to ${BuildConfig.VERSION_NAME}")
    }

    private fun checkForUpdates(manual: Boolean) {
        if (checking) return
        checking = true
        setUpdateStatus(getString(R.string.update_status_checking))
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            val result = runCatching { Updater.checkForUpdate(ctx) }
            checking = false
            result.onSuccess { m ->
                if (m == null) setUpdateStatus(getString(R.string.update_status_current, BuildConfig.VERSION_NAME))
                else { setUpdateStatus(getString(R.string.update_status_available, m.version)); offerUpdate(m) }
            }.onFailure { t -> setUpdateStatus(if (manual) getString(R.string.update_status_error, t.message ?: t.javaClass.simpleName) else getString(R.string.update_status_idle, BuildConfig.VERSION_NAME)) }
        }
    }

    private fun offerUpdate(m: UpdateManifest) {
        val size = if (m.byteSize > 0) Formatter.formatShortFileSize(requireContext(), m.byteSize) else "size unknown"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.update_dialog_title, m.version))
            .setMessage(getString(R.string.update_dialog_body, m.version, size) + if (m.releaseNotes.isNotBlank()) "\n\n${m.releaseNotes}" else "")
            .setPositiveButton(R.string.update_install) { _, _ -> installUpdate(m) }
            .setNegativeButton(R.string.update_later, null).show()
    }

    private fun installUpdate(m: UpdateManifest) {
        val ctx = requireContext()
        if (!Updater.canInstall(ctx)) {
            MaterialAlertDialogBuilder(ctx).setTitle(R.string.update_allow_title).setMessage(R.string.update_allow_body)
                .setPositiveButton(R.string.update_allow_open) { _, _ -> Updater.openInstallSettings(ctx) }
                .setNegativeButton(R.string.update_later, null).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val r = runCatching {
                val file = Updater.download(ctx, m) { pct -> activity?.runOnUiThread { setUpdateStatus(getString(R.string.update_downloading, pct.coerceAtLeast(0))) } }
                setUpdateStatus(getString(R.string.update_verifying))
                withContext(Dispatchers.IO) { Updater.verify(ctx, file, m); Updater.install(ctx, file) }
            }
            r.onFailure { setUpdateStatus(getString(R.string.update_failed, it.message ?: it.javaClass.simpleName)) }
            r.onSuccess { setUpdateStatus(getString(R.string.update_status_available, m.version)) }
        }
    }
}
