package io.github.jqssun.gpssetter.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.room.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogListAdapter(
    private val onItemClick: ((LogEntry) -> Unit)? = null
) : ListAdapter<LogEntry, LogListAdapter.ViewHolder>(LogDiffCallback()) {

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val badge: TextView = view.findViewById(R.id.log_badge)
        private val pkgText: TextView = view.findViewById(R.id.log_package)
        private val timeText: TextView = view.findViewById(R.id.log_time)
        private val actionText: TextView = view.findViewById(R.id.log_action)
        private val detailsText: TextView = view.findViewById(R.id.log_details)

        fun bind(entry: LogEntry) {
            val ctx = itemView.context
            val bg = GradientDrawable().apply {
                cornerRadius = 16f
            }

            if (entry.category == LogEntry.CATEGORY_APP) {
                badge.text = ctx.getString(R.string.log_badge_app)
                bg.setColor(Color.parseColor("#263B82F6"))
                badge.setTextColor(Color.parseColor("#3B82F6"))
            } else if (entry.isSpoofed) {
                badge.text = ctx.getString(R.string.log_badge_spoofed)
                bg.setColor(Color.parseColor("#2610B981"))
                badge.setTextColor(Color.parseColor("#10B981"))
            } else {
                badge.text = ctx.getString(R.string.log_badge_passthrough)
                bg.setColor(Color.parseColor("#26F59E0B"))
                badge.setTextColor(Color.parseColor("#F59E0B"))
            }
            badge.background = bg

            pkgText.text = entry.packageName
            timeText.text = synchronized(timeFormat) {
                timeFormat.format(Date(entry.timestamp))
            }
            actionText.text = entry.action
            detailsText.text = entry.details

            itemView.setOnClickListener {
                onItemClick?.invoke(entry)
            }
        }
    }

    class LogDiffCallback : DiffUtil.ItemCallback<LogEntry>() {
        override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
            return oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.log_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        getItem(position)?.let { holder.bind(it) }
    }
}
