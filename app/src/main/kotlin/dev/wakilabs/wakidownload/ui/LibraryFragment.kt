package dev.wakilabs.wakidownload.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.wakilabs.wakidownload.R
import dev.wakilabs.wakidownload.core.DownloadHistory
import dev.wakilabs.wakidownload.core.HistoryEntry
import dev.wakilabs.wakidownload.core.MediaInfo
import dev.wakilabs.wakidownload.databinding.FragmentLibraryBinding
import dev.wakilabs.wakidownload.databinding.ItemMediaBinding
import dev.wakilabs.wakidownload.ui.Ext.fadeIn
import dev.wakilabs.wakidownload.ui.Ext.fadeOut
import dev.wakilabs.wakidownload.ui.Ext.pressSpring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything saved, as a two-column masonry of thumbnails with filters, details, share and delete. */
class LibraryFragment : Fragment() {
    private var _b: FragmentLibraryBinding? = null
    private val b get() = _b!!
    private lateinit var history: DownloadHistory
    private var all: List<HistoryEntry> = emptyList()
    private var filter: String = FILTER_ALL
    private lateinit var adapter: MediaAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentLibraryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        history = DownloadHistory(requireContext())
        adapter = MediaAdapter(viewLifecycleOwner.lifecycleScope, onOpen = { open(it) }, onMore = { v, e -> menu(v, e) })
        ViewCompat.setOnApplyWindowInsetsListener(b.appbar) { v, insets -> v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets }
        b.grid.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL).apply { gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS }
        b.grid.adapter = adapter
        b.refresh.setOnRefreshListener { load(animate = false); b.refresh.isRefreshing = false }
        listOf(FILTER_ALL to R.string.filter_all, FILTER_VIDEO to R.string.filter_videos, FILTER_IMAGE to R.string.filter_pictures,
            "twitter" to R.string.default_twitter, "tiktok" to R.string.default_tiktok, "web" to R.string.default_web, "shared" to R.string.default_shared)
            .forEach { (key, label) ->
                b.filters.addView(Chip(requireContext()).apply { text = getString(label); tag = key; isCheckable = true; id = View.generateViewId(); isChecked = key == filter })
            }
        b.filters.setOnCheckedStateChangeListener { group, ids -> filter = (group.findViewById<Chip>(ids.firstOrNull() ?: return@setOnCheckedStateChangeListener))?.tag as? String ?: FILTER_ALL; apply() }
    }

    override fun onResume() { super.onResume(); load(animate = adapter.itemCount == 0) }
    override fun onDestroyView() { super.onDestroyView(); _b = null }

    private fun load(animate: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            all = withContext(Dispatchers.IO) { history.all() }
            apply()
            if (animate) b.grid.scheduleLayoutAnimation()
        }
    }

    private fun apply() {
        val shown = all.filter { e ->
            when (filter) {
                FILTER_ALL -> true
                FILTER_VIDEO -> e.mime.startsWith("video/")
                FILTER_IMAGE -> e.mime.startsWith("image/")
                else -> e.source == filter
            }
        }
        adapter.submitList(shown)
        val bytes = shown.sumOf { it.bytes }
        b.summary.text = if (shown.size == 1) getString(R.string.library_summary_one, Ext.size(requireContext(), bytes)) else getString(R.string.library_summary, shown.size, Ext.size(requireContext(), bytes))
        if (shown.isEmpty()) b.empty.fadeIn() else b.empty.fadeOut()
    }

    private fun open(e: HistoryEntry) {
        val uri = Uri.parse(e.uri)
        val i = if (uri.scheme == "https" || uri.scheme == "http") Intent(Intent.ACTION_VIEW, uri)
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(i) }.onFailure { Toast.makeText(requireContext(), "No app can open this", Toast.LENGTH_SHORT).show() }
    }

    private fun menu(anchor: View, e: HistoryEntry) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, R.string.details); menu.add(0, 2, 1, R.string.open); menu.add(0, 3, 2, R.string.share); menu.add(0, 4, 3, R.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> DetailsSheet.show(this@LibraryFragment, e) { load(false) }
                    2 -> open(e)
                    3 -> DetailsSheet.share(requireContext(), e)
                    4 -> confirmDelete(e)
                }
                true
            }
        }.show()
    }

    private fun confirmDelete(e: HistoryEntry) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.delete_confirm_title).setMessage(getString(R.string.delete_confirm_body, e.name))
            .setPositiveButton(R.string.delete) { _, _ -> viewLifecycleOwner.lifecycleScope.launch { DetailsSheet.delete(requireContext(), history, e); load(false) } }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    class MediaAdapter(val scope: kotlinx.coroutines.CoroutineScope, val onOpen: (HistoryEntry) -> Unit, val onMore: (View, HistoryEntry) -> Unit) : ListAdapter<HistoryEntry, MediaAdapter.VH>(DIFF) {
        class VH(val b: ItemMediaBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(h: VH, position: Int) {
            val e = getItem(position)
            val ctx = h.b.root.context
            h.b.name.text = e.name
            h.b.meta.text = Ext.metaLine(ctx, e)
            h.b.sourceBadge.setImageResource(Ext.sourceIcon(e.source))
            h.b.typeIcon.visibility = if (e.mime.startsWith("video/")) View.VISIBLE else View.GONE
            h.b.badge.visibility = View.GONE
            h.b.facts.visibility = View.GONE
            h.b.shimmer.visibility = View.VISIBLE
            h.b.shimmer.alpha = 1f
            // Cached facts render at once; the rest arrive progressively below.
            MediaInfo.cached(e.uri)?.let { f -> bindFacts(h, e, f) }
            val uri = Uri.parse(e.uri)
            if (uri.scheme == "content") {
                Glide.with(h.b.thumb).load(uri).transition(DrawableTransitionOptions.withCrossFade(220)).centerCrop()
                    .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                        override fun onLoadFailed(e: com.bumptech.glide.load.engine.GlideException?, model: Any?, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean): Boolean { h.b.shimmer.fadeOut(); return false }
                        override fun onResourceReady(resource: android.graphics.drawable.Drawable, model: Any, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?, dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean): Boolean { h.b.shimmer.fadeOut(); return false }
                    }).into(h.b.thumb)
                scope.launch {
                    val f = MediaInfo.facts(ctx, e.uri, e.mime) ?: return@launch
                    if (h.bindingAdapterPosition != RecyclerView.NO_POSITION && getItem(h.bindingAdapterPosition).uri == e.uri) bindFacts(h, e, f)
                }
            } else {
                Glide.with(h.b.thumb).clear(h.b.thumb)
                h.b.thumb.setImageResource(if (e.target.startsWith("Google")) R.drawable.ic_cloud_gdrive else R.drawable.ic_cloud_onedrive)
                h.b.thumb.scaleType = android.widget.ImageView.ScaleType.CENTER
                h.b.shimmer.visibility = View.GONE
                h.b.badge.text = e.target
                h.b.badge.visibility = View.VISIBLE
            }
            h.b.thumb.scaleType = if (uri.scheme == "content") android.widget.ImageView.ScaleType.CENTER_CROP else android.widget.ImageView.ScaleType.CENTER
            h.b.card.setOnClickListener { it.pressSpring(); onOpen(e) }
            h.b.card.setOnLongClickListener { onMore(h.b.more, e); true }
            h.b.more.setOnClickListener { onMore(it, e) }
        }

        private fun bindFacts(h: VH, e: HistoryEntry, f: dev.wakilabs.wakidownload.core.MediaFacts) {
            val parts = mutableListOf<String>()
            if (f.width > 0 && f.height > 0) parts += "${f.width}×${f.height}"
            if (f.durationMs > 0) { parts += MediaInfo.formatDuration(f.durationMs); h.b.badge.text = MediaInfo.formatDuration(f.durationMs); h.b.badge.fadeIn() }
            if (e.nsfw) parts += "NSFW"
            if (e.target != "Downloads / WakiDownload") parts += e.target
            if (parts.isNotEmpty()) { h.b.facts.text = parts.joinToString("  ·  "); h.b.facts.fadeIn() }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<HistoryEntry>() {
                override fun areItemsTheSame(a: HistoryEntry, b: HistoryEntry) = a.uri == b.uri
                override fun areContentsTheSame(a: HistoryEntry, b: HistoryEntry) = a == b
            }
        }
    }

    companion object { const val FILTER_ALL = "all"; const val FILTER_VIDEO = "video"; const val FILTER_IMAGE = "image" }
}
