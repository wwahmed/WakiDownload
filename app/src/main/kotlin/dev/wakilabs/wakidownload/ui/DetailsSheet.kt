package dev.wakilabs.wakidownload.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.wakilabs.wakidownload.R
import dev.wakilabs.wakidownload.core.DownloadHistory
import dev.wakilabs.wakidownload.core.HistoryEntry
import dev.wakilabs.wakidownload.core.MediaInfo
import dev.wakilabs.wakidownload.databinding.RowFactBinding
import dev.wakilabs.wakidownload.databinding.SheetDetailsBinding
import dev.wakilabs.wakidownload.ui.Ext.fadeIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/** A bottom sheet with the full file name, a preview, and facts that fill in as they are read. */
object DetailsSheet {
    fun show(host: Fragment, e: HistoryEntry, onChanged: () -> Unit) {
        val ctx = host.requireContext()
        val b = SheetDetailsBinding.inflate(LayoutInflater.from(ctx))
        val dialog = BottomSheetDialog(ctx).apply { setContentView(b.root) }
        b.name.text = e.name
        val uri = Uri.parse(e.uri)
        if (uri.scheme == "content") Glide.with(b.preview).load(uri).transition(DrawableTransitionOptions.withCrossFade()).centerCrop().into(b.preview)
        else { b.preview.setImageResource(if (e.target.startsWith("Google")) R.drawable.ic_cloud_gdrive else R.drawable.ic_cloud_onedrive); b.preview.scaleType = android.widget.ImageView.ScaleType.CENTER }

        fun fact(label: Int, value: String, delay: Long = 0) {
            val r = RowFactBinding.inflate(LayoutInflater.from(ctx), b.facts, false)
            r.label.setText(label); r.value.text = value
            r.root.alpha = 0f; b.facts.addView(r.root); r.root.fadeIn(delayMs = delay)
        }
        fact(R.string.fact_source, Ext.sourceLabel(e.source))
        fact(R.string.fact_saved_to, e.target, 40)
        fact(R.string.fact_size, Ext.size(ctx, e.bytes), 80)
        fact(R.string.fact_type, e.mime, 120)
        fact(R.string.fact_when, DateFormat.getMediumDateFormat(ctx).format(Date(e.time)) + " " + DateFormat.getTimeFormat(ctx).format(Date(e.time)), 160)
        if (e.nsfw) fact(R.string.fact_nsfw, ctx.getString(R.string.yes), 200)
        host.viewLifecycleOwner.lifecycleScope.launch {
            val f = MediaInfo.facts(ctx, e.uri, e.mime) ?: return@launch
            if (f.width > 0) fact(R.string.fact_dimensions, "${f.width} × ${f.height}")
            if (f.durationMs > 0) fact(R.string.fact_duration, MediaInfo.formatDuration(f.durationMs))
        }

        b.open.setOnClickListener {
            val i = if (uri.scheme == "content") Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) else Intent(Intent.ACTION_VIEW, uri)
            runCatching { ctx.startActivity(i) }
            dialog.dismiss()
        }
        b.share.setOnClickListener { share(ctx, e); dialog.dismiss() }
        b.delete.setOnClickListener {
            MaterialAlertDialogBuilder(ctx).setTitle(R.string.delete_confirm_title).setMessage(ctx.getString(R.string.delete_confirm_body, e.name))
                .setPositiveButton(R.string.delete) { _, _ ->
                    host.viewLifecycleOwner.lifecycleScope.launch { delete(ctx, DownloadHistory(ctx), e); onChanged(); dialog.dismiss() }
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        dialog.show()
    }

    fun share(ctx: Context, e: HistoryEntry) {
        val uri = Uri.parse(e.uri)
        val i = if (uri.scheme == "content") Intent(Intent.ACTION_SEND).setType(e.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        else Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, e.uri)
        runCatching { ctx.startActivity(Intent.createChooser(i, e.name)) }
    }

    /** Removes the local or picked-folder file; cloud entries only leave the list. */
    suspend fun delete(ctx: Context, history: DownloadHistory, e: HistoryEntry) {
        val uri = Uri.parse(e.uri)
        withContext(Dispatchers.IO) {
            runCatching {
                if (uri.scheme == "content") {
                    if (DocumentsContract.isDocumentUri(ctx, uri)) DocumentsContract.deleteDocument(ctx.contentResolver, uri) else ctx.contentResolver.delete(uri, null, null)
                }
            }
            history.remove(e.uri)
        }
        Toast.makeText(ctx, R.string.deleted, Toast.LENGTH_SHORT).show()
    }
}
