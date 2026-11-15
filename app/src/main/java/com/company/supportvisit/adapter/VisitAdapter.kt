package com.company.supportvisit.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.company.supportvisit.R
import com.company.supportvisit.data.SyncStatus
import com.company.supportvisit.data.VisitEntity
import com.company.supportvisit.databinding.ItemVisitBinding
import com.company.supportvisit.ui.colorRes
import com.company.supportvisit.ui.labelRes
import com.company.supportvisit.ui.messageRes
import com.company.supportvisit.utils.DateUtils

class VisitAdapter(
    private val onClick: (VisitEntity) -> Unit,
    private val onRetry: (VisitEntity) -> Unit
) : ListAdapter<VisitEntity, VisitAdapter.ViewHolder>(DIFF) {

    class ViewHolder(val binding: ItemVisitBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemVisitBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val visit = getItem(position)
        val context = holder.itemView.context
        with(holder.binding) {
            tvClientName.text = visit.clientName
            tvDate.text = context.getString(
                R.string.visit_date_time,
                DateUtils.formatForDisplay(visit.visitDate),
                visit.startTime,
                visit.endTime
            )
            tvDescription.text = visit.workDescription

            chipStatus.setText(visit.syncStatus.labelRes)
            chipStatus.chipBackgroundColor =
                ColorStateList.valueOf(ContextCompat.getColor(context, visit.syncStatus.colorRes))

            val failed = visit.syncStatus == SyncStatus.FAILED
            failedRow.visibility = if (failed) View.VISIBLE else View.GONE
            tvSyncError.text = visit.syncError?.let { context.getString(it.messageRes) }.orEmpty()
            btnRetry.setOnClickListener { onRetry(visit) }

            root.setOnClickListener { onClick(visit) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VisitEntity>() {
            override fun areItemsTheSame(old: VisitEntity, new: VisitEntity) = old.id == new.id
            override fun areContentsTheSame(old: VisitEntity, new: VisitEntity) = old == new
        }
    }
}
