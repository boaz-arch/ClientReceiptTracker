package com.company.supportvisit.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.company.supportvisit.databinding.ItemVisitBinding
import com.company.supportvisit.model.Visit

class VisitAdapter(
    private var visits: List<Visit>,
    private val onClick: (Visit) -> Unit
) : RecyclerView.Adapter<VisitAdapter.ViewHolder>() {

    inner class ViewHolder(
        val binding: ItemVisitBinding
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {

        val binding =
            ItemVisitBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

        return ViewHolder(binding)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {

        val visit =
            visits[position]

        holder.binding.tvClientName.text =
            visit.clientName

        holder.binding.tvDate.text =
            "${visit.visitDate} • ${visit.startTime} - ${visit.endTime}"

        holder.binding.tvDescription.text =
            visit.workDescription

        holder.itemView.setOnClickListener {

            onClick(visit)
        }
    }

    override fun getItemCount(): Int {

        return visits.size
    }

    fun updateData(
        newList: List<Visit>
    ) {

        visits = newList

        notifyDataSetChanged()
    }
}