package com.v2plus.app.fmt

import com.v2plus.app.AppConfig
import com.v2plus.app.dto.ProfileItem
import com.v2plus.app.dto.V2rayConfig.OutboundBean
import com.v2plus.app.enums.EConfigType
import com.v2plus.app.enums.NetworkType
import com.v2plus.app.extension.idnHost
import com.v2plus.app.extension.isNotNullEmpty
import com.v2plus.app.extension.nullIfBlank
import com.v2plus.app.handler.MmkvManager
import com.v2plus.app.handler.V2rayConfigManager
import com.v2plus.app.util.Utils
import java.net.URI

/**
 * Hysteria protocol version 1 (hy1). Shares the same core outbound name as v2 but uses [OutSettingsBean.version] = 1
 * and does not use the Salamander UDP mask (Hy2).
 */
object HysteriaFmt : FmtBase() {

    fun parse(str: String): ProfileItem? {
        val allowInsecure = MmkvManager.decodeSettingsBool(AppConfig.PREF_ALLOW_INSECURE, false)
        val config = ProfileItem.create(EConfigType.HYSTERIA)

        val uri = URI(Utils.fixIllegalUrl(str))
        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).let { it.ifEmpty { "none" } }
        config.server = uri.idnHost
        val p = uri.port
        config.serverPort = (if (p > 0) p else AppConfig.DEFAULT_PORT).toString()

        if (!uri.userInfo.isNullOrBlank()) {
            config.password = uri.userInfo
        }
        config.security = AppConfig.TLS
        config.network = NetworkType.HYSTERIA.type

        if (!uri.rawQuery.isNullOrEmpty()) {
            val queryParam = getQueryParam(uri)
            queryParam["auth"]?.nullIfBlank()?.let { config.password = it }
            getItemFormQuery(config, queryParam, allowInsecure)
            queryParam["peer"]?.nullIfBlank()?.let { config.sni = it }
            config.security = queryParam["security"] ?: config.security ?: AppConfig.TLS
            queryParam["upmbps"]?.let { config.bandwidthUp = it }
            queryParam["downmbps"]?.let { config.bandwidthDown = it }
            queryParam["up"]?.let { config.bandwidthUp = it }
            queryParam["down"]?.let { config.bandwidthDown = it }
            queryParam["obfs"]?.nullIfBlank()?.let { config.headerType = it }
            queryParam["obfs-param"]?.nullIfBlank()?.let { config.obfsPassword = it }
            config.portHopping = queryParam["mport"]
            if (config.portHopping.isNotNullEmpty()) {
                config.portHoppingInterval = queryParam["mportHopInt"]
            }
            config.pinnedCA256 = queryParam["pinSHA256"]
        }

        config.network = NetworkType.HYSTERIA.type
        return config
    }

    fun toUri(config: ProfileItem): String {
        val dicQuery = HashMap<String, String>()
        dicQuery["protocol"] = "udp"
        config.security.let { if (it != null) dicQuery["security"] = it }
        config.sni?.nullIfBlank()?.let { dicQuery["peer"] = it }
        config.alpn?.nullIfBlank()?.let { dicQuery["alpn"] = it }
        config.insecure.let { dicQuery["insecure"] = if (it == true) "1" else "0" }
        config.bandwidthUp?.nullIfBlank()?.let { dicQuery["upmbps"] = it }
        config.bandwidthDown?.nullIfBlank()?.let { dicQuery["downmbps"] = it }
        config.headerType?.nullIfBlank()?.let { dicQuery["obfs"] = it }
        config.obfsPassword?.nullIfBlank()?.let { dicQuery["obfs-param"] = it }
        if (config.portHopping.isNotNullEmpty()) {
            dicQuery["mport"] = config.portHopping.orEmpty()
        }
        if (config.portHoppingInterval.isNotNullEmpty()) {
            dicQuery["mportHopInt"] = config.portHoppingInterval.orEmpty()
        }
        if (config.pinnedCA256.isNotNullEmpty()) {
            dicQuery["pinSHA256"] = config.pinnedCA256.orEmpty()
        }
        return toUri(config, config.password, dicQuery)
    }

    fun toOutbound(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = V2rayConfigManager.createInitOutbound(EConfigType.HYSTERIA) ?: return null
        profileItem.network = NetworkType.HYSTERIA.type
        // Hysteria1 servers negotiate "hysteria" ALPN (not "h3" which belongs to Hysteria2/HTTP3)
        if (profileItem.alpn.isNullOrBlank()) {
            profileItem.alpn = "hysteria"
        }

        outboundBean.settings?.let { server ->
            server.address = getServerAddress(profileItem)
            server.port = profileItem.serverPort.orEmpty().toInt()
            server.version = 1
        }

        val sni = outboundBean.streamSettings?.let {
            V2rayConfigManager.populateTransportSettings(it, profileItem)
        }

        outboundBean.streamSettings?.let {
            V2rayConfigManager.populateTlsSettings(it, profileItem, sni)
        }

        return outboundBean
    }
}
