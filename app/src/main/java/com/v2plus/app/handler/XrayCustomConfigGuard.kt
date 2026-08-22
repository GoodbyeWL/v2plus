package com.v2plus.app.handler

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2plus.app.AppConfig
import com.v2plus.app.util.JsonUtil

/**
 * Restricts custom full JSON passed to xray-core to client-safe subsets:
 * blocks server-style inbounds, reverse bridges, loopback outbounds, and strips
 * [freedom.settings.redirect] which can be abused for SSRF / traffic steering.
 */
object XrayCustomConfigGuard {

    private val ALLOWED_INBOUND_PROTOCOLS = setOf(
        "socks",
        "http",
        "tun",
        "mixed",
    )

    private val FORBIDDEN_OUTBOUND_PROTOCOLS = setOf(
        "loopback",
        "reverse",
    )

    sealed class Result {
        data class Ok(val json: String) : Result()
        data class Rejected(val reason: String) : Result()
    }

    /**
     * Validates structure and returns sanitized JSON (pretty) for core, or rejection reason.
     */
    fun validateAndSanitize(raw: String): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return Result.Rejected("empty")
        }
        val root: JsonObject = try {
            JsonParser.parseString(trimmed).asJsonObject
        } catch (e: Exception) {
            return Result.Rejected("invalid json")
        }

        root.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray?.let { arr ->
            val err = validateInbounds(arr)
            if (err != null) return Result.Rejected(err)
        }

        root.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray?.let { arr ->
            val err = validateAndSanitizeOutbounds(arr)
            if (err != null) return Result.Rejected(err)
        }

        if (root.has("reverse")) {
            root.remove("reverse")
            Log.i(AppConfig.TAG, "XrayCustomConfigGuard: removed top-level reverse")
        }

        val sanitized = JsonUtil.toJsonPretty(root) ?: JsonUtil.toJson(root)
        return Result.Ok(sanitized)
    }

    private fun validateInbounds(arr: JsonArray): String? {
        for (i in 0 until arr.size()) {
            val el = arr[i]
            if (!el.isJsonObject) continue
            val prot = el.asJsonObject.get("protocol")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
                ?.lowercase()
            if (prot.isNullOrEmpty()) {
                return "inbound missing protocol"
            }
            if (prot !in ALLOWED_INBOUND_PROTOCOLS) {
                return "forbidden inbound protocol: $prot"
            }
        }
        return null
    }

    private fun validateAndSanitizeOutbounds(arr: JsonArray): String? {
        for (i in 0 until arr.size()) {
            val el = arr[i]
            if (!el.isJsonObject) continue
            val out = el.asJsonObject
            val prot = out.get("protocol")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
                ?.lowercase()
                ?: continue
            if (prot in FORBIDDEN_OUTBOUND_PROTOCOLS) {
                return "forbidden outbound protocol: $prot"
            }
            if (prot == "freedom") {
                stripFreedomRedirect(out)
            }
            stripDangerousSockopt(out)
        }
        return null
    }

    private fun stripDangerousSockopt(outbound: JsonObject) {
        val streamSettings = outbound.get("streamSettings") ?: return
        if (!streamSettings.isJsonObject) return
        val s = streamSettings.asJsonObject
        val sockopt = s.get("sockopt") ?: return
        if (!sockopt.isJsonObject) return
        val opt = sockopt.asJsonObject
        val badKeys = listOf("interface", "mark", "tcpcongestion", "tproxy", "tcpMptcp", "tcpMaxSeg", "tcpWindowClamp", "tcpUserTimeout", "tcpKeepAliveIdle", "tcpKeepAliveInterval", "V6Only")
        var removed = false
        for (key in badKeys) {
            if (opt.has(key)) {
                opt.remove(key)
                removed = true
            }
        }
        if (removed) {
            Log.i(AppConfig.TAG, "XrayCustomConfigGuard: stripped dangerous sockopt fields")
        }
    }

    private fun stripFreedomRedirect(outbound: JsonObject) {
        val settings = outbound.get("settings") ?: return
        if (!settings.isJsonObject) return
        val s = settings.asJsonObject
        if (s.has("redirect")) {
            s.remove("redirect")
            Log.i(AppConfig.TAG, "XrayCustomConfigGuard: stripped freedom.settings.redirect")
        }
    }
}
