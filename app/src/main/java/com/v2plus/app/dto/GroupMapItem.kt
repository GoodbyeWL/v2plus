package com.v2plus.app.dto

data class GroupMapItem(
    var id: String,
    var remarks: String,
    var isGroup: Boolean = false,
    var group: String = "",
)