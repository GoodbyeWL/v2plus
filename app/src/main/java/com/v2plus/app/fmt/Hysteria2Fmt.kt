package com.v2plus.app.fmt

import com.v2plus.app.AppConfig
import com.v2plus.app.dto.ProfileItem
import com.v2plus.app.dto.V2rayConfig.OutboundBean
import com.v2plus.app.dto.V2rayConfig.OutboundBean.StreamSettingsBean.FinalMaskBean
import com.v2plus.app.enums.EConfigType
import com.v2plus.app.enums.NetworkType
import com.v2plus.app.extension.idnHost
import com.v2plus.app.extension.isNotNullEmpty
import com.v2plus.app.extension.nullIfBlank
import com.v2plus.app.handler.MmkvManager
import com.v2plus.app.handler.V2rayConfigManager
import com.v2plus.app.util.Utils
import java.net.URI

object Hysteria2Fmt : FmtBase() {
    /**
     * Parses a Hysteria2 URI string into a ProfileItem object.
     *
     * @param str the Hysteria2 URI string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parse(str: String): ProfileItem? {
        val allowInsecure = MmkvManager.decodeSettingsBool(AppConfig.PREF_ALLOW_INSECURE, false)
        val config = ProfileItem.create(EConfigType.HYSTERIA2)

        val uri = URI(Utils.fixIllegalUrl(str))
        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).let { it.ifEmpty { "none" } }
        config.server = uri.idnHost
        val p = uri.port
        config.serverPort = (if (p > 0) p else AppConfig.DEFAULT_PORT).toString()
        config.password = uri.userInfo
        config.security = AppConfig.TLS

        if (!uri.rawQuery.isNullOrEmpty()) {
            val queryParam = getQueryParam(uri)
            queryParam["auth"]?.nullIfBlank()?.let { config.password = it }
            getItemFormQuery(config, queryParam, allowInsecure)
            config.security = queryParam["security"] ?: config.security ?: AppConfig.TLS
            config.obfsPassword = queryParam["obfs-password"]
            config.portHopping = queryParam["mport"]
            if (config.portHopping.isNotNullEmpty()) {
                config.portHoppingInterval = queryParam["mportHopInt"]
            }
            config.pinnedCA256 = queryParam["pinSHA256"]
            queryParam["upmbps"]?.let { config.bandwidthUp = it }
            queryParam["downmbps"]?.let { config.bandwidthDown = it }
        }

        // getItemFormQuery() defaults type=tcp when absent — hysteria outbound must use QUIC/hysteria transport
        config.network = NetworkType.HYSTERIA.type
        return config
    }

    /**
     * Converts a ProfileItem object to a URI string.
     *
     * @param config the ProfileItem object to convert
     * @return the converted URI string
     */
    fun toUri(config: ProfileItem): String {
        val dicQuery = HashMap<String, String>()

        config.security.let { if (it != null) dicQuery["security"] = it }
        config.sni?.nullIfBlank()?.let { dicQuery["sni"] = it }
        config.alpn?.nullIfBlank()?.let { dicQuery["alpn"] = it }
        config.insecure.let { dicQuery["insecure"] = if (it == true) "1" else "0" }

        if (config.obfsPassword.isNotNullEmpty()) {
            dicQuery["obfs"] = "salamander"
            dicQuery["obfs-password"] = config.obfsPassword.orEmpty()
        }
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

    /**
     * Converts a ProfileItem object to an OutboundBean object.
     *
     * @param profileItem the ProfileItem object to convert
     * @return the converted OutboundBean object, or null if conversion fails
     */
    fun toOutbound(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = V2rayConfigManager.createInitOutbound(EConfigType.HYSTERIA2) ?: return null
        profileItem.network = NetworkType.HYSTERIA.type
        if (profileItem.alpn.isNullOrBlank()) {
            profileItem.alpn = "h3"
        }

        outboundBean.settings?.let { server ->
            server.address = getServerAddress(profileItem)
            server.port = profileItem.serverPort.orEmpty().toInt()
            server.version = 2
        }

        val sni = outboundBean.streamSettings?.let {
            V2rayConfigManager.populateTransportSettings(it, profileItem)
        }

        outboundBean.streamSettings?.let {
            V2rayConfigManager.populateTlsSettings(it, profileItem, sni)
        }

        if (profileItem.obfsPassword.isNotNullEmpty()) {
            outboundBean.streamSettings?.finalmask = FinalMaskBean(
                udp = listOf(
                    FinalMaskBean.MaskBean(
                        type = "salamander",
                        settings = FinalMaskBean.MaskBean.MaskSettingsBean(
                            password = profileItem.obfsPassword
                        )
                    )
                )
            )
        }
        return outboundBean
    }
}