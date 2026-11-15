package com.company.supportvisit.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.company.supportvisit.databinding.ItemPhotoBinding
import com.company.supportvisit.utils.PhotoCompressor

/** Thumbnails of the photos attached to a visit, each with a remove button. */
class PhotoAdapter(
    private val onRemove: (String) -> Unit
) : ListAdapter<String, PhotoAdapter.ViewHolder>(DIFF) {

    class ViewHolder(val binding: ItemPhotoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val path = getItem(position)
        // Thumbnails are decoded small (a few KB of pixels), so this is cheap.
        holder.binding.ivPhoto.setImageBitmap(PhotoCompressor.loadScaled(path, THUMB_SIZE))
        holder.binding.btnRemovePhoto.setOnClickListener { onRemove(path) }
    }

    companion object {
        private const val THUMB_SIZE = 320

        private val DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(old: String, new: String) = old == new
            override fun areContentsTheSame(old: String, new: String) = old == new
        }
    }
}
