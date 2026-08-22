package com.v2plus.app.dto

data class SubscriptionItem(
    var remarks: String = "",
    var url: String = "",
    var enabled: Boolean = true,
    val addedTime: Long = System.currentTimeMillis(),
    var lastUpdated: Long = -1,
    var autoUpdate: Boolean = false,
    val updateInterval: Int? = null,
    var prevProfile: String? = null,
    var nextProfile: String? = null,
    var filter: String? = null,
    var allowInsecureUrl: Boolean = true,
    var userAgent: String? = null,
    var upload: Long? = null,
    var download: Long? = null,
    var total: Long? = null,
    var expire: Long? = null,
    var group: String? = null,
    var supportUrl: String? = null,
    var announce: String? = null,
)

