package com.v2plus.app.handler

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import com.v2plus.app.R
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads bundled variable fonts (Google Fonts, OFL) and maps [Typeface] weights
 * for UI text (regular / medium / semibold / bold).
 */
object AppFonts {

    const val FAMILY_MONTSERRAT = "montserrat"
    const val FAMILY_MANROPE = "manrope"
    const val FAMILY_DM_SANS = "dm_sans"
    const val FAMILY_NUNITO_SANS = "nunito_sans"
    const val FAMILY_PLUS_JAKARTA = "plus_jakarta"
    const val FAMILY_SANS = "sans"
    const val FAMILY_SERIF = "serif"
    const val FAMILY_MONO = "mono"
    const val FAMILY_DEFAULT = "default"

    private val fontResByFamily: Map<String, Int> = mapOf(
        FAMILY_MONTSERRAT to R.font.montserrat_variable,
        FAMILY_MANROPE to R.font.manrope_variable,
        FAMILY_DM_SANS to R.font.dm_sans_variable,
        FAMILY_NUNITO_SANS to R.font.nunito_sans_variable,
        FAMILY_PLUS_JAKARTA to R.font.plus_jakarta_variable,
    )

    fun hasBundledFamily(familyKey: String): Boolean = familyKey in fontResByFamily

    private val typefaceCache = ConcurrentHashMap<Pair<Int, Int>, Typeface>()

    fun resolveTypeface(context: Context, familyKey: String, sample: Typeface?): Typeface {
        val resId = fontResByFamily[familyKey] ?: return legacySystemTypeface(familyKey)
        val weight = weightFromSample(sample)
        return loadVariableFont(context, resId, weight)
    }

    private fun legacySystemTypeface(familyKey: String): Typeface = when (familyKey) {
        FAMILY_SERIF -> Typeface.SERIF
        FAMILY_MONO -> Typeface.MONOSPACE
        FAMILY_DEFAULT -> Typeface.DEFAULT
        else -> Typeface.SANS_SERIF
    }

    private fun weightFromSample(sample: Typeface?): Int {
        if (sample == null) return 400
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val w = sample.weight
            if (w in 1..1000) return w
        }
        return if (sample.isBold) 700 else 400
    }

    private fun clampWeightForVariation(w: Int): Int = w.coerceIn(400, 700)

    private fun loadVariableFont(context: Context, @FontRes resId: Int, weight: Int): Typeface {
        val w = clampWeightForVariation(weight)
        val key = resId to w
        typefaceCache[key]?.let { return it }

        val built = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val file = fontFileInCache(context, resId)
            try {
                Typeface.Builder(file).setFontVariationSettings("'wght' $w").build()
            } catch (_: Exception) {
                ResourcesCompat.getFont(context, resId) ?: Typeface.SANS_SERIF
            }
        } else {
            ResourcesCompat.getFont(context, resId) ?: Typeface.SANS_SERIF
        }

        typefaceCache[key] = built
        return built
    }

    private fun fontFileInCache(context: Context, @FontRes resId: Int): File {
        val dir = File(context.cacheDir, "app_fonts").apply { mkdirs() }
        val out = File(dir, "f_$resId.ttf")
        if (out.exists() && out.length() > 0) return out
        context.resources.openRawResource(resId).use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        return out
    }
}
