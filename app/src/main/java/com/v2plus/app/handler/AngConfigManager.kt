package com.v2plus.app.handler

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import android.util.Log
import com.v2plus.app.AppConfig
import com.v2plus.app.AppConfig.HY2
import com.v2plus.app.R
import com.v2plus.app.dto.ProfileItem
import com.v2plus.app.dto.SubscriptionCache
import com.v2plus.app.dto.SubscriptionItem
import com.v2plus.app.dto.SubscriptionUpdateResult
import com.v2plus.app.enums.EConfigType
import com.v2plus.app.extension.isNotNullEmpty
import com.v2plus.app.fmt.CustomFmt
import com.v2plus.app.fmt.HysteriaFmt
import com.v2plus.app.fmt.Hysteria2Fmt
import com.v2plus.app.fmt.ShadowsocksFmt
import com.v2plus.app.fmt.SocksFmt
import com.v2plus.app.fmt.TrojanFmt
import com.v2plus.app.fmt.TuicFmt
import com.v2plus.app.fmt.VlessFmt
import com.v2plus.app.fmt.VmessFmt
import com.v2plus.app.fmt.WireguardFmt
import com.google.gson.JsonParser
import com.v2plus.app.util.HttpUtil
import com.v2plus.app.util.JsonUtil
import com.v2plus.app.util.MessageUtil
import com.v2plus.app.util.QRCodeDecoder
import com.v2plus.app.util.Utils
import java.net.URI

object AngConfigManager {


    /**
     * Shares the configuration to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @return The result code.
     */
    fun share2Clipboard(context: Context, guid: String): Int {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return -1
            }

            Utils.setClipboard(context, conf)

        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share config to clipboard", e)
            return -1
        }
        return 0
    }

    /**
     * Shares non-custom configurations to the clipboard.
     *
     * @param context The context.
     * @param serverList The list of server GUIDs.
     * @return The number of configurations shared.
     */
    fun shareNonCustomConfigsToClipboard(context: Context, serverList: List<String>): Int {
        try {
            val sb = StringBuilder()
            for (guid in serverList) {
                val url = shareConfig(guid)
                if (TextUtils.isEmpty(url)) {
                    continue
                }
                sb.append(url)
                sb.appendLine()
            }
            if (sb.count() > 0) {
                Utils.setClipboard(context, sb.toString())
            }
            return sb.lines().count() - 1
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share non-custom configs to clipboard", e)
            return -1
        }
    }

    fun exportNonCustomConfigs(serverList: List<String>): List<String> {
        return serverList.mapNotNull { guid ->
            shareConfig(guid).takeIf { it.isNotBlank() }
        }
    }

    /**
     * Shares the configuration as a QR code.
     *
     * @param guid The GUID of the configuration.
     * @return The QR code bitmap.
     */
    fun share2QRCode(guid: String): Bitmap? {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return null
            }
            return QRCodeDecoder.createQRCode(conf)

        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share config as QR code", e)
            return null
        }
    }

    /**
     * Shares the full content of the configuration to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @return The result code.
     */
    fun shareFullContent2Clipboard(context: Context, guid: String?): Int {
        try {
            if (guid == null) return -1
            val result = V2rayConfigManager.getV2rayConfig(context, guid)
            if (result.status) {
                Utils.setClipboard(context, result.content)
            } else {
                return -1
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share full content to clipboard", e)
            return -1
        }
        return 0
    }

    /**
     * Shares the configuration.
     *
     * @param guid The GUID of the configuration.
     * @return The configuration string.
     */
    fun shareConfig(guid: String): String {
        try {
            val config = MmkvManager.decodeServerConfig(guid) ?: return ""

            return config.configType.protocolScheme + when (config.configType) {
                EConfigType.VMESS -> VmessFmt.toUri(config)
                EConfigType.CUSTOM -> ""
                EConfigType.SHADOWSOCKS -> ShadowsocksFmt.toUri(config)
                EConfigType.SOCKS -> SocksFmt.toUri(config)
                EConfigType.HTTP -> ""
                EConfigType.VLESS -> VlessFmt.toUri(config)
                EConfigType.TROJAN -> TrojanFmt.toUri(config)
                EConfigType.WIREGUARD -> WireguardFmt.toUri(config)
                EConfigType.HYSTERIA -> HysteriaFmt.toUri(config)
                EConfigType.HYSTERIA2 -> Hysteria2Fmt.toUri(config)
                EConfigType.POLICYGROUP -> ""
                else -> {}
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share config for GUID: $guid", e)
            return ""
        }
    }

    /**
     * Imports a batch of configurations.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return A pair containing the number of configurations and subscriptions imported.
     */
    fun importBatchConfig(context: Context?, server: String?, subid: String, append: Boolean): Pair<Int, Int> {
        val count = parseConfigsPayload(server, subid, append)

        var countSub = parseBatchSubscription(server)
        if (countSub <= 0) {
            countSub = parseBatchSubscription(Utils.decode(server))
        }
        var broadcastSent = false
        if (countSub > 0) {
            val res = updateConfigViaSubAll(context)
            if (res.successCount > 0) broadcastSent = true
        }

        if (context != null && count > 0 && !broadcastSent) {
            MessageUtil.sendMsg2UI(context, AppConfig.MSG_RELOAD_SERVER_LIST, "")
        }

        return count to countSub
    }

    /**
     * Parses a batch of subscriptions.
     *
     * @param servers The servers string.
     * @return The number of subscriptions parsed.
     */
    private fun parseBatchSubscription(servers: String?): Int {
        try {
            if (servers == null) {
                return 0
            }

            var count = 0
            servers.lines()
                .distinct()
                .forEach { str ->
                    if (Utils.isValidSubUrl(str)) {
                        count += importUrlAsSubscription(str)
                    }
                }
            return count
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to parse batch subscription", e)
        }
        return 0
    }

    /**
     * Parses a batch of configurations.
     *
     * @param servers The servers string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseBatchConfig(servers: String?, subid: String, append: Boolean): Int {
        try {
            if (servers == null) {
                return 0
            }
            //  Find the currently selected server that matches the subscription ID
            val removedSelected = if (subid.isNotBlank() && !append) {
                MmkvManager.getSelectServer()
                    .takeIf { it?.isNotBlank() == true }
                    ?.let { MmkvManager.decodeServerConfig(it) }
                    ?.takeIf { it.subscriptionId == subid }
            } else {
                null
            }

            val subItem = MmkvManager.decodeSubscription(subid)

            // Parse all configs first (no I/O during parsing)
            val configs = mutableListOf<ProfileItem>()
            servers.lines()
                .distinct()
                .reversed()
                .forEach {
                    val config = parseConfig(it, subid, subItem)
                    if (config != null) {
                        configs.add(config)
                    }
                }

            // Batch save all parsed configs (only one serverList read/write)
            if (configs.isNotEmpty()) {
                if (!append) {
                    MmkvManager.removeServerViaSubid(subid)
                }
                val keyToProfile = batchSaveConfigs(configs, subid)
                val matchKey = findMatchedProfileKey(keyToProfile, removedSelected)
                matchKey?.let { MmkvManager.setSelectServer(it) }
            }

            return configs.size
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to parse batch config", e)
        }
        return 0
    }

    /**
     * Batch save configurations to reduce serverList read/write operations.
     * Reads serverList once, saves all configs, then writes serverList once.
     *
     * @param configs The list of ProfileItem to save.
     * @param subid The subscription ID.
     * @return Map of generated keys to their corresponding ProfileItem.
     */
    private fun batchSaveConfigs(configs: List<ProfileItem>, subid: String): Map<String, ProfileItem> {
        val keyToProfile = mutableMapOf<String, ProfileItem>()

        // Read serverList once
        val serverList = MmkvManager.decodeServerList(subid)
        var needSetSelected = MmkvManager.getSelectServer().isNullOrBlank()

        configs.forEach { config ->
            val key = Utils.getUuid()
            // Save profile directly without updating serverList
            MmkvManager.encodeProfileDirect(key, JsonUtil.toJson(config))

            if (!serverList.contains(key)) {
                serverList.add(0, key)
                if (needSetSelected) {
                    MmkvManager.setSelectServer(key)
                    needSetSelected = false
                }
            }
            keyToProfile[key] = config
        }

        // Write serverList once
        MmkvManager.encodeServerList(serverList, subid)
        return keyToProfile
    }

    /**
     * Finds a matched profile key from the given key-profile map using multi-level matching.
     * Matching priority (from highest to lowest):
     * 1. Exact match: server + port + password
     * 2. Match by remarks (exact match)
     * 3. Match by server + port
     * 4. Match by server only
     *
     * @param keyToProfile Map of server keys to their ProfileItem
     * @param target Target profile to match
     * @return Matched key or null
     */
    private fun findMatchedProfileKey(keyToProfile: Map<String, ProfileItem>, target: ProfileItem?): String? {
        if (keyToProfile.isEmpty() || target == null) return null

        // Level 1: Match by remarks
        if (target.remarks.isNotBlank()) {
            keyToProfile.entries.firstOrNull { (_, saved) ->
                isSameText(saved.remarks, target.remarks)
            }?.key?.let { return it }
        }

        // Level 2: Exact match (server + port + password)
        keyToProfile.entries.firstOrNull { (_, saved) ->
            isSameText(saved.server, target.server) &&
                    isSameText(saved.serverPort, target.serverPort) &&
                    isSameText(saved.password, target.password)
        }?.key?.let { return it }

        // Level 3: Match by server + port
        keyToProfile.entries.firstOrNull { (_, saved) ->
            isSameText(saved.server, target.server) &&
                    isSameText(saved.serverPort, target.serverPort)
        }?.key?.let { return it }

        // Level 4: Match by server only
        keyToProfile.entries.firstOrNull { (_, saved) ->
            isSameText(saved.server, target.server)
        }?.key?.let { return it }

        return null
    }

    /**
     * Case-insensitive trimmed string comparison.
     *
     * @param left First string
     * @param right Second string
     * @return True if both are non-empty and equal (case-insensitive, trimmed)
     */
    private fun isSameText(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return false
        return left.trim().equals(right.trim(), ignoreCase = true)
    }

    /**
     * Parses a custom configuration server.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseCustomConfigServer(server: String?, subid: String, append: Boolean): Int {
        if (server == null) {
            return 0
        }
        if (server.contains("inbounds")
            && server.contains("outbounds")
            && server.contains("routing")
        ) {
            // Try parsing as JSON array of full Xray configs (e.g. astracat subscription format).
            // We use JsonParser directly to avoid the Array<Any> deserialization path which
            // converts all JSON numbers to Double, causing "port":443 → "port":443.0 and
            // breaking Int field deserialization in V2rayConfig.
            try {
                val jsonEl = JsonParser.parseString(server)
                if (jsonEl.isJsonArray) {
                    val jsonArr = jsonEl.asJsonArray
                    Log.i(AppConfig.TAG, "parseCustomConfigServer: JSON array detected, size=${jsonArr.size()}, subid=$subid")
                    if (jsonArr.size() > 0) {
                        if (!append) {
                            MmkvManager.removeServerViaSubid(subid)
                        }
                        var count = 0
                        // Process in reverse so that first element ends up at top of list
                        for (i in (jsonArr.size() - 1) downTo 0) {
                            val el = jsonArr[i]
                            if (!el.isJsonObject) continue
                            val srvJson = JsonUtil.toJson(el.asJsonObject)
                            val safe = when (val v = XrayCustomConfigGuard.validateAndSanitize(srvJson)) {
                                is XrayCustomConfigGuard.Result.Ok -> v.json
                                is XrayCustomConfigGuard.Result.Rejected -> {
                                    Log.w(AppConfig.TAG, "parseCustomConfigServer[$i]: rejected — ${v.reason}")
                                    continue
                                }
                            }
                            val config = try {
                                CustomFmt.parse(safe)
                            } catch (ex: Exception) {
                                Log.e(AppConfig.TAG, "parseCustomConfigServer[$i]: CustomFmt.parse threw: $ex")
                                null
                            }
                            if (config == null) {
                                Log.w(AppConfig.TAG, "parseCustomConfigServer[$i]: CustomFmt.parse returned null")
                                continue
                            }
                            config.subscriptionId = subid
                            config.description = generateDescription(config)
                            val key = MmkvManager.encodeServerConfig("", config)
                            MmkvManager.encodeServerRaw(key, safe)
                            count += 1
                            Log.i(AppConfig.TAG, "parseCustomConfigServer[$i]: saved ${config.configType} '${config.remarks}' key=$key")
                        }
                        Log.i(AppConfig.TAG, "parseCustomConfigServer: array done, count=$count")
                        return count
                    }
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to parse custom config server JSON array", e)
            }

            try {
                val safe = when (val v = XrayCustomConfigGuard.validateAndSanitize(server)) {
                    is XrayCustomConfigGuard.Result.Ok -> v.json
                    is XrayCustomConfigGuard.Result.Rejected -> {
                        Log.w(AppConfig.TAG, "Custom config rejected on import: ${v.reason}")
                        return 0
                    }
                }
                // For compatibility
                val config = CustomFmt.parse(safe) ?: return 0
                config.subscriptionId = subid
                config.description = generateDescription(config)
                if (!append) {
                    MmkvManager.removeServerViaSubid(subid)
                }
                val key = MmkvManager.encodeServerConfig("", config)
                MmkvManager.encodeServerRaw(key, safe)
                return 1
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to parse custom config server as single config", e)
            }
            return 0
        } else if (server.startsWith("[Interface]") && server.contains("[Peer]")) {
            try {
                val config = WireguardFmt.parseWireguardConfFile(server) ?: return R.string.toast_incorrect_protocol
                config.description = generateDescription(config)
                if (!append) {
                    MmkvManager.removeServerViaSubid(subid)
                }
                val key = MmkvManager.encodeServerConfig("", config)
                MmkvManager.encodeServerRaw(key, server)
                return 1
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to parse WireGuard config file", e)
            }
            return 0
        } else {
            return 0
        }
    }

    /**
     * Parses the configuration from a QR code or string.
     * Only parses and returns ProfileItem, does not save.
     *
     * @param str The configuration string.
     * @param subid The subscription ID.
     * @param subItem The subscription item.
     * @return The parsed ProfileItem or null if parsing fails or filtered out.
     */
    private fun parseConfig(
        str: String?,
        subid: String,
        subItem: SubscriptionItem?
    ): ProfileItem? {
        try {
            if (str == null || TextUtils.isEmpty(str)) {
                return null
            }

            val config = if (str.startsWith(EConfigType.VMESS.protocolScheme)) {
                VmessFmt.parse(str)
            } else if (str.startsWith(EConfigType.SHADOWSOCKS.protocolScheme)) {
                ShadowsocksFmt.parse(str)
            } else if (str.startsWith(EConfigType.SOCKS.protocolScheme)) {
                SocksFmt.parse(str)
            } else if (str.startsWith(EConfigType.TROJAN.protocolScheme)) {
                TrojanFmt.parse(str)
            } else if (str.startsWith(EConfigType.VLESS.protocolScheme)) {
                VlessFmt.parse(str)
            } else if (str.startsWith(EConfigType.WIREGUARD.protocolScheme)) {
                WireguardFmt.parse(str)
            } else if (str.startsWith(EConfigType.TUIC.protocolScheme)) {
                TuicFmt.parse(str)
            } else if (str.startsWith(EConfigType.HYSTERIA2.protocolScheme) || str.startsWith(HY2)) {
                Hysteria2Fmt.parse(str)
            } else if (str.startsWith(EConfigType.HYSTERIA.protocolScheme)) {
                HysteriaFmt.parse(str)
            } else {
                null
            }

            if (config == null) {
                return null
            }

            // Apply filter
            if (subItem?.filter.isNotNullEmpty() && config.remarks.isNotNullEmpty()) {
                val matched = Regex(pattern = subItem?.filter.orEmpty())
                    .containsMatchIn(input = config.remarks)
                if (!matched) return null
            }

            config.subscriptionId = subid
            config.description = generateDescription(config)

            return config
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to parse config", e)
            return null
        }
    }

    /**
     * Updates the configuration via all subscriptions.
     *
     * @return Detailed result of the subscription update operation.
     */
    fun updateConfigViaSubAll(context: Context? = null): SubscriptionUpdateResult {
        return try {
            val subscriptions = MmkvManager.decodeSubscriptions()
            val result = subscriptions.fold(SubscriptionUpdateResult()) { acc, subscription ->
                acc + updateConfigViaSub(subscription)
            }
            if (context != null && result.successCount > 0) {
                MessageUtil.sendMsg2UI(context, AppConfig.MSG_RELOAD_SERVER_LIST, "")
            }
            result
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to update config via all subscriptions", e)
            SubscriptionUpdateResult()
        }
    }

    /**
     * Updates the configuration via a subscription.
     *
     * @param it The subscription item.
     * @return Subscription update result.
     */
    fun updateConfigViaSub(it: SubscriptionCache): SubscriptionUpdateResult {
        try {
            // Check if disabled
            if (!it.subscription.enabled) {
                return SubscriptionUpdateResult(skipCount = 1)
            }

            // Validate subscription info
            if (TextUtils.isEmpty(it.guid)
                || TextUtils.isEmpty(it.subscription.remarks)
                || TextUtils.isEmpty(it.subscription.url)
            ) {
                return SubscriptionUpdateResult(skipCount = 1)
            }

            val url = HttpUtil.toIdnUrl(it.subscription.url)
            if (!Utils.isValidUrl(url)) {
                return SubscriptionUpdateResult(failureCount = 1)
            }
            if (!it.subscription.allowInsecureUrl) {
                if (!Utils.isValidSubUrl(url)) {
                    return SubscriptionUpdateResult(failureCount = 1)
                }
            }
            Log.i(AppConfig.TAG, url)
            val userAgent = it.subscription.userAgent

            var configText: String
            var headers: Map<String, List<String>>
            val insecureTls = it.subscription.allowInsecureUrl

            try {
                val httpPort = SettingsManager.getHttpPort()
                val result = HttpUtil.getUrlContentAndHeadersWithUserAgent(url, userAgent, 15000, httpPort, insecureTls)
                headers = result.first
                configText = result.second
            } catch (e: Exception) {
                Log.e(AppConfig.ANG_PACKAGE, "Update subscription: proxy not ready or other error", e)
                try {
                    val result = HttpUtil.getUrlContentAndHeadersWithUserAgent(url, userAgent, insecureTls = insecureTls)
                    headers = result.first
                    configText = result.second
                } catch (e2: Exception) {
                    Log.e(AppConfig.TAG, "Update subscription: Failed to get URL content with user agent", e2)
                    return SubscriptionUpdateResult(failureCount = 1)
                }
            }
            
            if (configText.isEmpty()) {
                return SubscriptionUpdateResult(failureCount = 1)
            }

            // Parse subscription-userinfo header
            val userInfoHeader = headers.entries.firstOrNull { (key, _) ->
                key.equals("subscription-userinfo", ignoreCase = true)
            }?.value?.firstOrNull()
            
            if (!userInfoHeader.isNullOrEmpty()) {
                val userInfo = parseSubscriptionUserInfo(userInfoHeader)
                it.subscription.upload = userInfo["upload"]
                it.subscription.download = userInfo["download"]
                it.subscription.total = userInfo["total"]
                it.subscription.expire = userInfo["expire"]
                Log.i(AppConfig.TAG, "Parsed subscription-userinfo: upload=${it.subscription.upload}, download=${it.subscription.download}, total=${it.subscription.total}, expire=${it.subscription.expire}")
            }
            
            // Only parse #profile-title, #support-url, #announce from subscription content
            // Do NOT overwrite user's custom subscription name from headers
            parseSubscriptionHeaders(configText, it.subscription)
            
            // Parse subscription-userinfo header for quota info
            // (already handled above)

            val count = parseConfigViaSub(configText, it.guid, false)
            if (count > 0) {
                it.subscription.lastUpdated = System.currentTimeMillis()
                MmkvManager.encodeSubscription(it.guid, it.subscription)
                Log.i(AppConfig.TAG, "Subscription updated: ${it.subscription.remarks}, $count configs")
                return SubscriptionUpdateResult(
                    configCount = count,
                    successCount = 1
                )
            } else {
                // Got response but no valid configs parsed
                return SubscriptionUpdateResult(failureCount = 1)
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to update config via subscription", e)
            return SubscriptionUpdateResult(failureCount = 1)
        }
    }

    /**
     * Parses subscription-userinfo header.
     *
     * @param header The subscription-userinfo header value.
     * @return A map of key-value pairs.
     */
    private fun parseSubscriptionUserInfo(header: String): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        try {
            val pairs = header.split(";")
            for (pair in pairs) {
                val keyValue = pair.trim().split("=")
                if (keyValue.size == 2) {
                    val key = keyValue[0].trim().lowercase()
                    val value = keyValue[1].trim().toLongOrNull()
                    if (value != null) {
                        result[key] = value
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to parse subscription-userinfo header", e)
        }
        return result
    }

    /**
     * Parses #support-url, #announce from subscription content.
     * Note: #profile-title is intentionally NOT parsed to preserve user's custom subscription name.
     *
     * @param content The subscription content.
     * @param subscription The subscription item to update.
     */
    private fun parseSubscriptionHeaders(content: String, subscription: SubscriptionItem) {
        try {
            val lines = content.lines()
            for (line in lines) {
                val trimmedLine = line.trim()
                when {
                    trimmedLine.startsWith("#support-url:") -> {
                        val url = trimmedLine.substringAfter("#support-url:").trim()
                        if (url.isNotEmpty()) {
                            subscription.supportUrl = url
                            Log.i(AppConfig.TAG, "Parsed #support-url: $url")
                        }
                    }
                    trimmedLine.startsWith("#announce:") -> {
                        val announce = trimmedLine.substringAfter("#announce:").trim()
                        if (announce.isNotEmpty()) {
                            subscription.announce = announce
                            Log.i(AppConfig.TAG, "Parsed #announce: $announce")
                        }
                    }
                    // Note: #profile-title is intentionally ignored to preserve user's custom name
                }
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to parse subscription headers", e)
        }
    }

    /**
     * Parses the configuration via a subscription.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseConfigViaSub(server: String?, subid: String, append: Boolean): Int {
        return parseConfigsPayload(server, subid, append)
    }

    /**
     * Tries to parse imported payload in both encoded and decoded forms.
     *
     * Remnawave-style subscriptions can mix URI-scheme lines (vless://, hysteria2://)
     * with full Xray JSON objects in the same payload. We intentionally run BOTH
     * parseBatchConfig (handles URIs) and parseCustomConfigServer (handles JSON)
     * so that all servers are captured regardless of format.
     *
     * If parseBatchConfig already saved some configs (batchCount > 0), we pass
     * append=true to parseCustomConfigServer so it doesn't wipe those configs.
     */
    private fun parseConfigsPayload(server: String?, subid: String, append: Boolean): Int {
        val candidates = linkedSetOf<String>().apply {
            Utils.decode(server).takeIf { it.isNotBlank() }?.let(::add)
            server?.takeIf { it.isNotBlank() }?.let(::add)
        }

        var batchCount = 0
        for (candidate in candidates) {
            val count = parseBatchConfig(candidate, subid, append)
            if (count > 0) {
                batchCount = count
                break
            }
        }

        // Always also attempt JSON-object import so that mixed-format subscriptions
        // (e.g. vless:// URI + hysteria full-JSON objects in one payload) work.
        // If batchCount > 0 the URI configs are already saved, so pass append=true
        // to avoid deleting them.
        val customAppend = append || batchCount > 0
        var customCount = 0
        for (candidate in candidates) {
            val count = parseCustomConfigServer(candidate, subid, customAppend)
            if (count > 0) {
                customCount = count
                break
            }
        }

        return batchCount + customCount
    }

    /**
     * Imports a URL as a subscription.
     *
     * @param url The URL.
     * @return The number of subscriptions imported.
     */
    private fun importUrlAsSubscription(url: String): Int {
        val subscriptions = MmkvManager.decodeSubscriptions()
        subscriptions.forEach {
            if (it.subscription.url == url) {
                return 0
            }
        }
        val uri = URI(Utils.fixIllegalUrl(url))
        val subItem = SubscriptionItem()
        
        // Try to get name from fragment first
        if (!uri.fragment.isNullOrBlank()) {
            subItem.remarks = uri.fragment!!
        } else {
            // Try to extract name from URL path
            val path = uri.path
            if (!path.isNullOrBlank()) {
                val pathSegments = path.split("/").filter { it.isNotEmpty() }
                if (pathSegments.isNotEmpty()) {
                    val lastSegment = pathSegments.last()
                    // Remove file extension if present
                    val name = lastSegment.substringBeforeLast(".")
                    if (name.isNotEmpty()) {
                        subItem.remarks = name
                    } else {
                        subItem.remarks = "Subscription"
                    }
                } else {
                    subItem.remarks = "Subscription"
                }
            } else {
                subItem.remarks = "Subscription"
            }
        }
        
        subItem.url = url
        MmkvManager.encodeSubscription("", subItem)
        return 1
    }

    /** Generates a description for the profile.
     *
     * @param profile The profile item.
     * @return The generated description.
     */
    fun generateDescription(profile: ProfileItem): String {
        // Hide xxx:xxx:***/xxx.xxx.xxx.***
        val server = profile.server
        val port = profile.serverPort
        if (server.isNullOrBlank() && port.isNullOrBlank()) return ""

        val addrPart = server?.let {
            if (it.contains(":"))
                it.split(":").take(2).joinToString(":", postfix = ":***")
            else
                it.split('.').dropLast(1).joinToString(".", postfix = ".***")
        } ?: ""

        return "$addrPart : ${port ?: ""}"
    }
}
