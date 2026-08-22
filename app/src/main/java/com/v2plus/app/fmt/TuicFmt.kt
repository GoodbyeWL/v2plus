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

object TuicFmt : FmtBase() {
    /**
     * Parses a TUIC URI string into a ProfileItem object.
     *
     * @param str the TUIC URI string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parse(str: String): ProfileItem? {
        var allowInsecure = MmkvManager.decodeSettingsBool(AppConfig.PREF_ALLOW_INSECURE, false)
        val config = ProfileItem.create(EConfigType.TUIC)

        val uri = URI(Utils.fixIllegalUrl(str))
        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).let { it.ifEmpty { "none" } }
        config.server = uri.idnHost
        config.serverPort = uri.port.toString()
        
        // TUIC URI format: tuic://uuid:password@server:port?params#name
        val userInfo = uri.userInfo?.split(":")
        if (userInfo != null && userInfo.size >= 2) {
            config.uuid = userInfo[0]
            config.password = userInfo[1]
        } else if (userInfo != null && userInfo.size == 1) {
            config.uuid = userInfo[0]
        }
        
        config.security = AppConfig.TLS
        config.network = NetworkType.TCP.type

        if (!uri.rawQuery.isNullOrEmpty()) {
            val queryParam = getQueryParam(uri)

            getItemFormQuery(config, queryParam, allowInsecure)

            config.congestionControl = queryParam["congestion_control"]
            config.udpRelayMode = queryParam["udp_relay_mode"]
            config.zeroRttHandshake = queryParam["zero_rtt_handshake"] == "1"
        }

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

        if (config.congestionControl.isNotNullEmpty()) {
            dicQuery["congestion_control"] = config.congestionControl.orEmpty()
        }
        if (config.udpRelayMode.isNotNullEmpty()) {
            dicQuery["udp_relay_mode"] = config.udpRelayMode.orEmpty()
        }
        if (config.zeroRttHandshake == true) {
            dicQuery["zero_rtt_handshake"] = "1"
        }

        val userInfo = if (config.password.isNotNullEmpty()) {
            "${config.uuid}:${config.password}"
        } else {
            config.uuid.orEmpty()
        }

        return toUri(config, userInfo, dicQuery)
    }

    /**
     * Converts a ProfileItem object to an OutboundBean object.
     *
     * @param profileItem the ProfileItem object to convert
     * @return the converted OutboundBean object, or null if conversion fails
     */
    fun toOutbound(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = V2rayConfigManager.createInitOutbound(EConfigType.TUIC) ?: return null
        profileItem.network = NetworkType.TCP.type

        outboundBean.settings?.let { server ->
            server.address = getServerAddress(profileItem)
            server.port = profileItem.serverPort.orEmpty().toInt()
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