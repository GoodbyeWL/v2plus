package com.v2plus.app.viewmodel

import androidx.lifecycle.ViewModel
import com.v2plus.app.AppConfig
import com.v2plus.app.AppConfig.ANG_PACKAGE
import java.io.IOException

class LogcatViewModel : ViewModel() {
    private val logsetsAll: MutableList<String> = mutableListOf()
    private var filteredLogs: List<String> = emptyList()
    private var currentFilter: String = ""

    fun getAll(): List<String> = filteredLogs

    fun loadLogcat() {
        try {
            val lst = LinkedHashSet<String>()
            lst.add("logcat")
            lst.add("-d")
            lst.add("-v")
            lst.add("time")
            lst.add("-s")
            lst.add("GoLog,${ANG_PACKAGE},AndroidRuntime,System.err")
            val process = Runtime.getRuntime().exec(lst.toTypedArray())
            val allText = process.inputStream.bufferedReader().use { it.readLines() }.reversed()

            logsetsAll.clear()
            logsetsAll.addAll(allText)
            applyFilter()
        } catch (e: IOException) {
            android.util.Log.e(AppConfig.TAG, "Failed to get logcat", e)
        }
    }

    fun clearLogcat() {
        try {
            val lst = LinkedHashSet<String>()
            lst.add("logcat")
            lst.add("-c")
            val process = Runtime.getRuntime().exec(lst.toTypedArray())
            process.waitFor()

            logsetsAll.clear()
            filteredLogs = emptyList()
        } catch (e: IOException) {
            android.util.Log.e(AppConfig.TAG, "Failed to clear logcat", e)
        }
    }

    fun filter(content: String?) {
        currentFilter = content?.trim() ?: ""
        applyFilter()
    }

    fun getFilteredText(): String = getAll().joinToString("\n")

    /**
     * Returns the newest crash block (AndroidRuntime FATAL EXCEPTION) if found.
     * `logsetsAll` is stored newest-first.
     */
    fun getLatestCrashBlock(maxLines: Int = 140): String? {
        if (logsetsAll.isEmpty()) return null
        val startIdx = logsetsAll.indexOfFirst {
            it.contains("FATAL EXCEPTION", ignoreCase = true) ||
                (it.contains("AndroidRuntime", ignoreCase = true) && it.contains("FATAL", ignoreCase = true))
        }
        if (startIdx < 0) return null

        val lines = mutableListOf<String>()
        val tsRegex = Regex("""^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}""")

        // Walk "down" towards older logs (higher indices) to collect stacktrace.
        for (i in startIdx until logsetsAll.size) {
            val line = logsetsAll[i]
            if (lines.isNotEmpty() && tsRegex.containsMatchIn(line) && line.contains("FATAL EXCEPTION", ignoreCase = true)) {
                // another crash starts; stop at first block
                break
            }
            lines.add(line)
            if (lines.size >= maxLines) break
        }

        return lines.joinToString("\n").trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Returns a compact list of newest error lines (E/ + System.err) from the current filtered view.
     */
    fun getLatestErrorsText(maxLines: Int = 120): String? {
        val src = if (currentFilter.isEmpty()) logsetsAll else filteredLogs
        if (src.isEmpty()) return null

        val out = ArrayList<String>(maxLines)
        for (line in src) { // newest-first
            val isError = line.contains(" E ", ignoreCase = false) ||
                line.contains("System.err", ignoreCase = true) ||
                line.contains("AndroidRuntime", ignoreCase = true)
            if (isError) {
                out.add(line)
                if (out.size >= maxLines) break
            }
        }
        return out.joinToString("\n").trim().takeIf { it.isNotEmpty() }
    }

    private fun applyFilter() {
        filteredLogs = if (currentFilter.isEmpty()) {
            logsetsAll.toList()
        } else {
            logsetsAll.filter { it.contains(currentFilter) }
        }
    }
}
