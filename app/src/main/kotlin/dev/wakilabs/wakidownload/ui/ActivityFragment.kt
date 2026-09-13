package dev.wakilabs.wakidownload.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.wakilabs.wakidownload.R
import dev.wakilabs.wakidownload.core.DownloadWorker
import dev.wakilabs.wakidownload.databinding.FragmentActivityBinding
import dev.wakilabs.wakidownload.databinding.ItemJobBinding
import dev.wakilabs.wakidownload.ui.Ext.fadeIn
import dev.wakilabs.wakidownload.ui.Ext.fadeOut

/** Live view of every download job WorkManager knows about: running ones with a stepper, then recent results. */
class ActivityFragment : Fragment() {
    private var _b: FragmentActivityBinding? = null
    private val b get() = _b!!
    private val adapter = JobAdapter { info -> retry(info) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentActivityBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets -> v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets }
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter
        WorkManager.getInstance(requireContext()).getWorkInfosByTagLiveData("download").observe(viewLifecycleOwner) { infos ->
            val sorted = infos.sortedWith(compareBy<WorkInfo> { it.state.isFinished }.thenByDescending { it.progress.getLong(DownloadWorker.KEY_STARTED, 0) }.thenByDescending { it.outputData.getLong(DownloadWorker.KEY_STARTED, 0) })
            adapter.submitList(sorted)
            val running = infos.count { !it.state.isFinished }
            b.summary.text = when {
                running == 1 -> "1 download in progress"
                running > 1 -> "$running downloads in progress"
                infos.isEmpty() -> ""
                else -> "${infos.size} recent"
            }
            if (infos.isEmpty()) b.empty.fadeIn() else b.empty.fadeOut()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    private fun retry(info: WorkInfo) {
        val text = info.outputData.getString(DownloadWorker.KEY_TEXT) ?: return
        DownloadWorker.enqueueLink(requireContext(), text, info.outputData.getString(DownloadWorker.KEY_TARGET_ID) ?: "local", info.outputData.getBoolean(DownloadWorker.KEY_NSFW, false))
    }

    class JobAdapter(val onRetry: (WorkInfo) -> Unit) : ListAdapter<WorkInfo, JobAdapter.VH>(DIFF) {
        class VH(val b: ItemJobBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemJobBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(h: VH, position: Int) {
            val info = getItem(position)
            val ctx = h.b.root.context
            val data = if (info.state.isFinished) info.outputData else info.progress
            val name = data.getString(DownloadWorker.KEY_NAME) ?: data.getString(DownloadWorker.KEY_LABEL) ?: ctx.getString(R.string.app_name)
            val source = data.getString(DownloadWorker.KEY_SOURCE) ?: "web"
            h.b.icon.setImageResource(Ext.sourceIcon(source))
            h.b.title.text = name
            h.b.retry.visibility = View.GONE
            h.b.status.visibility = View.GONE
            val stage = data.getString(DownloadWorker.KEY_STAGE)
            fun steps(active: Int, done: Int) {
                listOf(h.b.step1, h.b.step2, h.b.step3).forEachIndexed { i, tv ->
                    tv.isActivated = i == active
                    tv.isSelected = i < done
                    val base = tv.context.getString(listOf(R.string.step_resolve, R.string.step_download, R.string.step_save)[i])
                    tv.text = if (i < done) "\u2713 $base" else base
                }
            }
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                    h.b.subtitle.text = ctx.getString(R.string.job_queued); steps(-1, 0)
                    h.b.progress.visibility = View.VISIBLE; h.b.progress.isIndeterminate = true
                }
                WorkInfo.State.RUNNING -> {
                    val pct = data.getInt(DownloadWorker.KEY_PERCENT, -1)
                    val count = data.getInt(DownloadWorker.KEY_COUNT, 1)
                    val idx = data.getInt(DownloadWorker.KEY_INDEX, 0)
                    val found = data.getString(DownloadWorker.KEY_FOUND)
                    when (stage) {
                        DownloadWorker.STAGE_DOWNLOADING -> { steps(1, 1); h.b.subtitle.text = listOfNotNull(found, if (count > 1) "${idx + 1} of $count" else null, if (pct >= 0) "$pct%" else null).joinToString("  ·  ") }
                        DownloadWorker.STAGE_SAVING -> { steps(2, 2); h.b.subtitle.text = "Saving to ${data.getString(DownloadWorker.KEY_TARGET) ?: ""}" + if (pct >= 0) "  ·  $pct%" else "" }
                        else -> { steps(0, 0); h.b.subtitle.text = ctx.getString(R.string.share_resolving) }
                    }
                    h.b.progress.visibility = View.VISIBLE
                    if (pct >= 0 && stage != DownloadWorker.STAGE_RESOLVING) { h.b.progress.isIndeterminate = false; h.b.progress.setProgressCompat(pct, true) } else h.b.progress.isIndeterminate = true
                }
                WorkInfo.State.SUCCEEDED -> {
                    steps(-1, 3)
                    val n = info.outputData.getInt(DownloadWorker.KEY_COUNT, 1)
                    h.b.subtitle.text = ctx.getString(R.string.job_done, info.outputData.getString(DownloadWorker.KEY_TARGET) ?: "") + if (n > 1) "  ·  $n files" else ""
                    h.b.progress.visibility = View.GONE
                    h.b.status.setImageResource(R.drawable.ic_check_circle); h.b.status.visibility = View.VISIBLE
                }
                else -> {
                    steps(-1, 0)
                    h.b.subtitle.text = info.outputData.getString(DownloadWorker.KEY_ERROR) ?: ctx.getString(R.string.job_failed)
                    h.b.progress.visibility = View.GONE
                    h.b.status.setImageResource(R.drawable.ic_error); h.b.status.visibility = View.VISIBLE
                    if (info.outputData.getString(DownloadWorker.KEY_TEXT) != null) { h.b.retry.visibility = View.VISIBLE; h.b.retry.setOnClickListener { onRetry(info) } }
                }
            }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<WorkInfo>() {
                override fun areItemsTheSame(a: WorkInfo, b: WorkInfo) = a.id == b.id
                override fun areContentsTheSame(a: WorkInfo, b: WorkInfo) = a == b
            }
        }
    }
}
