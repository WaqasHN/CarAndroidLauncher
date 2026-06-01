package com.waqas.carlauncher

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.waqas.carlauncher.databinding.ItemTrackBinding

class AudioListAdapter(
    private val tracks: List<Track>,
    private val onClick: (index: Int) -> Unit
) : RecyclerView.Adapter<AudioListAdapter.VH>() {

    class VH(val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return VH(ItemTrackBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = tracks[position]
        holder.binding.trackTitle.text = t.title
        holder.binding.trackArtist.text = t.artist ?: ""
        holder.binding.root.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = tracks.size
}
