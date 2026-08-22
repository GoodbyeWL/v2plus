package com.v2plus.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.v2plus.app.databinding.ItemRecyclerBypassListBinding
import com.v2plus.app.dto.AppInfo

class VpnPauseAppsAdapter(
    private val apps: List<AppInfo>,
    private val selected: Set<String>,
    private val onToggle: (String, Boolean) -> Unit
) : RecyclerView.Adapter<VpnPauseAppsAdapter.VH>() {

    override fun getItemCount() = apps.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemRecyclerBypassListBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(apps[position])
    }

    inner class VH(private val b: ItemRecyclerBypassListBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(app: AppInfo) {
            b.icon.setImageDrawable(app.appIcon)
            b.name.text = if (app.isSystemApp) "** ${app.appName}" else app.appName
            b.packageName.text = app.packageName
            b.checkBox.isChecked = selected.contains(app.packageName)

            itemView.setOnClickListener {
                val newState = !b.checkBox.isChecked
                b.checkBox.isChecked = newState
                onToggle(app.packageName, newState)
            }
        }
    }
}
