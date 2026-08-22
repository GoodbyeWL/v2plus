package com.v2plus.app.contracts

import com.v2plus.app.dto.ProfileItem

interface MainAdapterListener :BaseAdapterListener {

    fun onEdit(guid: String, position: Int, profile: ProfileItem)

    fun onSelectServer(guid: String)

    fun onShare(guid: String, profile: ProfileItem, position: Int, more: Boolean)

}