package com.v2plus.app.fmt

import com.v2plus.app.dto.ProfileItem
import com.v2plus.app.dto.V2rayConfig
import com.v2plus.app.enums.EConfigType
import com.v2plus.app.util.JsonUtil

object CustomFmt : FmtBase() {
    /**
     * Parses a JSON string into a ProfileItem object.
     *
     * @param str the JSON string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parse(str: String): ProfileItem? {
        val fullConfig = try {
            JsonUtil.fromJson(str, V2rayConfig::class.java)
        } catch (e: Exception) {
            android.util.Log.e("V2plus", "CustomFmt.parse: fromJson failed: $e")
            null
        }
        val outbound = fullConfig?.getProxyOutbound()

        if (outbound != null) {
            val protocol = outbound.protocol.lowercase()
            if (protocol == "hysteria" || protocol == "hysteria2") {
                val version = outbound.settings?.version ?: outbound.streamSettings?.hysteriaSettings?.version ?: 1
                val isHy2 = protocol == "hysteria2" || version == 2
                
                val config = ProfileItem.create(if (isHy2) EConfigType.HYSTERIA2 else EConfigType.HYSTERIA)
                config.remarks = fullConfig.remarks ?: System.currentTimeMillis().toString()
                config.server = outbound.getServerAddress()
                config.serverPort = outbound.getServerPort()?.toString()
                
                val stream = outbound.streamSettings
                config.network = stream?.network ?: "hysteria"
                config.password = stream?.hysteriaSettings?.auth ?: outbound.settings?.obfsPassword
                
                config.security = stream?.security ?: "tls"
                config.sni = stream?.tlsSettings?.serverName
                config.alpn = stream?.tlsSettings?.alpn?.firstOrNull() ?: if (isHy2) "h3" else "hysteria"
                config.fingerPrint = stream?.tlsSettings?.fingerprint
                config.insecure = stream?.tlsSettings?.allowInsecure
                
                if (isHy2) {
                    val salamander = stream?.finalmask?.udp?.firstOrNull { it.type == "salamander" }
                    if (salamander != null) {
                        config.obfsPassword = salamander.settings?.password
                    }
                } else {
                    config.bandwidthUp = stream?.hysteriaSettings?.up
                    config.bandwidthDown = stream?.hysteriaSettings?.down
                }
                
                return config
            } else if (protocol == "vless") {
                val config = ProfileItem.create(EConfigType.VLESS)
                config.remarks = fullConfig.remarks ?: System.currentTimeMillis().toString()
                config.server = outbound.getServerAddress()
                config.serverPort = outbound.getServerPort()?.toString()
                
                val vnext = outbound.settings?.vnext?.firstOrNull()
                val user = vnext?.users?.firstOrNull()
                config.password = user?.id
                config.flow = user?.flow
                
                val stream = outbound.streamSettings
                config.network = stream?.network ?: "tcp"
                config.security = stream?.security ?: "none"
                
                if (config.security == "reality") {
                    config.sni = stream?.realitySettings?.serverName
                    config.fingerPrint = stream?.realitySettings?.fingerprint
                    config.publicKey = stream?.realitySettings?.publicKey
                    config.shortId = stream?.realitySettings?.shortId
                    config.spiderX = stream?.realitySettings?.spiderX
                } else if (config.security == "tls") {
                    config.sni = stream?.tlsSettings?.serverName
                    config.fingerPrint = stream?.tlsSettings?.fingerprint
                    config.alpn = stream?.tlsSettings?.alpn?.firstOrNull()
                    config.insecure = stream?.tlsSettings?.allowInsecure
                }
                
                return config
            }
        }

        val config = ProfileItem.create(EConfigType.CUSTOM)

        config.remarks = fullConfig?.remarks ?: System.currentTimeMillis().toString()
        config.server = outbound?.getServerAddress()
        config.serverPort = outbound?.getServerPort().toString()

        return config
    }
}
