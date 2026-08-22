package com.v2plus.app.handler

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.v2plus.app.AppConfig
import com.v2plus.app.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.io.InputStream

object CustomizationManager {

    private const val KEY_ACCENT = "cust_accent"
    private const val KEY_BG_START = "cust_bg_start"
    private const val KEY_BG_CENTER = "cust_bg_center"
    private const val KEY_BG_END = "cust_bg_end"
    private const val KEY_CARD_COLOR = "cust_card"
    private const val KEY_CARD_STROKE = "cust_card_stroke"
    private const val KEY_HEADER_START = "cust_hdr_start"
    private const val KEY_HEADER_END = "cust_hdr_end"
    private const val KEY_BG_IMAGE_PATH = "cust_bg_image_path"
    private const val KEY_CARD_RADIUS = "cust_card_radius"
    private const val KEY_HEADER_RADIUS = "cust_header_radius"
    private const val KEY_SERVER_RADIUS = "cust_server_radius"
    private const val KEY_DRAWER_RADIUS = "cust_drawer_radius"
    private const val KEY_BG_OVERLAY = "cust_bg_overlay"
    private const val KEY_TEXT_PRIMARY = "cust_text_primary"
    private const val KEY_TEXT_SECONDARY = "cust_text_secondary"
    private const val KEY_TEXT_TERTIARY = "cust_text_tertiary"
    private const val KEY_ICON_COLOR = "cust_icon_color"
    private const val KEY_SUCCESS_COLOR = "cust_success_color"
    private const val KEY_ERROR_COLOR = "cust_error_color"
    private const val KEY_WARNING_COLOR = "cust_warning_color"
    private const val KEY_FONT_FAMILY = "cust_font_family"
    private const val KEY_FONT_SCALE = "cust_font_scale"
    private const val BG_IMAGE_FILE_NAME = "custom_bg_image"

    const val DEFAULT_ACCENT = 0x2F81F7
    const val DEFAULT_BG_START = 0x050A14
    const val DEFAULT_BG_CENTER = 0x091224
    const val DEFAULT_BG_END = 0x0B1220
    const val DEFAULT_CARD = 0x0F172A
    const val DEFAULT_CARD_STROKE = 0x1E2A44
    const val DEFAULT_HEADER_START = 0x13284A
    const val DEFAULT_HEADER_END = 0x0B1830
    const val DEFAULT_CARD_RADIUS = 24f
    const val DEFAULT_HEADER_RADIUS = 28f
    const val DEFAULT_SERVER_RADIUS = 24f
    const val DEFAULT_DRAWER_RADIUS = 16f
    const val DEFAULT_BG_OVERLAY = 150
    const val DEFAULT_TEXT_PRIMARY = 0xE8EAF6
    const val DEFAULT_TEXT_SECONDARY = 0xB0BEC5
    const val DEFAULT_TEXT_TERTIARY = 0x78909C
    const val DEFAULT_ICON_COLOR = 0x90A4AE
    const val DEFAULT_SUCCESS_COLOR = 0x4CAF50
    const val DEFAULT_ERROR_COLOR = 0xF44336
    const val DEFAULT_WARNING_COLOR = 0xFF9800
    const val DEFAULT_FONT_FAMILY = AppFonts.FAMILY_MONTSERRAT
    const val DEFAULT_FONT_SCALE = 100

    fun getAccent(): Int = readColor(KEY_ACCENT, DEFAULT_ACCENT)
    fun setAccent(c: Int) = writeColor(KEY_ACCENT, c)

    fun getBgStart(): Int = readColor(KEY_BG_START, DEFAULT_BG_START)
    fun setBgStart(c: Int) = writeColor(KEY_BG_START, c)

    fun getBgCenter(): Int = readColor(KEY_BG_CENTER, DEFAULT_BG_CENTER)
    fun setBgCenter(c: Int) = writeColor(KEY_BG_CENTER, c)

    fun getBgEnd(): Int = readColor(KEY_BG_END, DEFAULT_BG_END)
    fun setBgEnd(c: Int) = writeColor(KEY_BG_END, c)

    fun getCard(): Int = readColor(KEY_CARD_COLOR, DEFAULT_CARD)
    fun setCard(c: Int) = writeColor(KEY_CARD_COLOR, c)

    fun getCardStroke(): Int = readColor(KEY_CARD_STROKE, DEFAULT_CARD_STROKE)
    fun setCardStroke(c: Int) = writeColor(KEY_CARD_STROKE, c)

    fun getHeaderStart(): Int = readColor(KEY_HEADER_START, DEFAULT_HEADER_START)
    fun setHeaderStart(c: Int) = writeColor(KEY_HEADER_START, c)

    fun getHeaderEnd(): Int = readColor(KEY_HEADER_END, DEFAULT_HEADER_END)
    fun setHeaderEnd(c: Int) = writeColor(KEY_HEADER_END, c)

    fun getCardRadius(): Float = MmkvManager.decodeSettingsFloat(KEY_CARD_RADIUS, DEFAULT_CARD_RADIUS)
    fun setCardRadius(r: Float) = MmkvManager.encodeSettings(KEY_CARD_RADIUS, r)

    fun getHeaderRadius(): Float = MmkvManager.decodeSettingsFloat(KEY_HEADER_RADIUS, DEFAULT_HEADER_RADIUS)
    fun setHeaderRadius(r: Float) = MmkvManager.encodeSettings(KEY_HEADER_RADIUS, r)

    fun getServerRadius(): Float = MmkvManager.decodeSettingsFloat(KEY_SERVER_RADIUS, DEFAULT_SERVER_RADIUS)
    fun setServerRadius(r: Float) = MmkvManager.encodeSettings(KEY_SERVER_RADIUS, r)

    fun getDrawerRadius(): Float = MmkvManager.decodeSettingsFloat(KEY_DRAWER_RADIUS, DEFAULT_DRAWER_RADIUS)
    fun setDrawerRadius(r: Float) = MmkvManager.encodeSettings(KEY_DRAWER_RADIUS, r)

    fun getBgOverlay(): Int = MmkvManager.decodeSettingsInt(KEY_BG_OVERLAY, DEFAULT_BG_OVERLAY)
    fun setBgOverlay(v: Int) = MmkvManager.encodeSettings(KEY_BG_OVERLAY, v)

    fun getTextPrimary(): Int = readColor(KEY_TEXT_PRIMARY, DEFAULT_TEXT_PRIMARY)
    fun setTextPrimary(c: Int) = writeColor(KEY_TEXT_PRIMARY, c)

    fun getTextSecondary(): Int = readColor(KEY_TEXT_SECONDARY, DEFAULT_TEXT_SECONDARY)
    fun setTextSecondary(c: Int) = writeColor(KEY_TEXT_SECONDARY, c)

    fun getTextTertiary(): Int = readColor(KEY_TEXT_TERTIARY, DEFAULT_TEXT_TERTIARY)
    fun setTextTertiary(c: Int) = writeColor(KEY_TEXT_TERTIARY, c)

    fun getIconColor(): Int = readColor(KEY_ICON_COLOR, DEFAULT_ICON_COLOR)
    fun setIconColor(c: Int) = writeColor(KEY_ICON_COLOR, c)

    fun getSuccessColor(): Int = readColor(KEY_SUCCESS_COLOR, DEFAULT_SUCCESS_COLOR)
    fun setSuccessColor(c: Int) = writeColor(KEY_SUCCESS_COLOR, c)

    fun getErrorColor(): Int = readColor(KEY_ERROR_COLOR, DEFAULT_ERROR_COLOR)
    fun setErrorColor(c: Int) = writeColor(KEY_ERROR_COLOR, c)

    fun getWarningColor(): Int = readColor(KEY_WARNING_COLOR, DEFAULT_WARNING_COLOR)
    fun setWarningColor(c: Int) = writeColor(KEY_WARNING_COLOR, c)

    fun getFontFamily(): String =
        MmkvManager.decodeSettingsString(KEY_FONT_FAMILY, DEFAULT_FONT_FAMILY) ?: DEFAULT_FONT_FAMILY
    fun setFontFamily(v: String) = MmkvManager.encodeSettings(KEY_FONT_FAMILY, v)

    fun getFontScale(): Int = MmkvManager.decodeSettingsInt(KEY_FONT_SCALE, DEFAULT_FONT_SCALE)
    fun setFontScale(v: Int) = MmkvManager.encodeSettings(KEY_FONT_SCALE, v.coerceIn(80, 140))

    fun resetAll() {
        setAccent(DEFAULT_ACCENT)
        setBgStart(DEFAULT_BG_START)
        setBgCenter(DEFAULT_BG_CENTER)
        setBgEnd(DEFAULT_BG_END)
        setCard(DEFAULT_CARD)
        setCardStroke(DEFAULT_CARD_STROKE)
        setHeaderStart(DEFAULT_HEADER_START)
        setHeaderEnd(DEFAULT_HEADER_END)
        setCardRadius(DEFAULT_CARD_RADIUS)
        setHeaderRadius(DEFAULT_HEADER_RADIUS)
        setServerRadius(DEFAULT_SERVER_RADIUS)
        setDrawerRadius(DEFAULT_DRAWER_RADIUS)
        setBgOverlay(DEFAULT_BG_OVERLAY)
        setTextPrimary(DEFAULT_TEXT_PRIMARY)
        setTextSecondary(DEFAULT_TEXT_SECONDARY)
        setTextTertiary(DEFAULT_TEXT_TERTIARY)
        setIconColor(DEFAULT_ICON_COLOR)
        setSuccessColor(DEFAULT_SUCCESS_COLOR)
        setErrorColor(DEFAULT_ERROR_COLOR)
        setWarningColor(DEFAULT_WARNING_COLOR)
        setFontFamily(DEFAULT_FONT_FAMILY)
        setFontScale(DEFAULT_FONT_SCALE)
        setBackgroundImagePath(null)
    }

    fun getBackgroundImagePath(): String? = MmkvManager.decodeSettingsString(KEY_BG_IMAGE_PATH)

    fun setBackgroundImagePath(path: String?) {
        MmkvManager.encodeSettings(KEY_BG_IMAGE_PATH, path)
    }

    fun hasBackgroundImage(): Boolean {
        val path = getBackgroundImagePath().orEmpty()
        return path.isNotBlank() && File(path).exists()
    }

    fun clearBackgroundImage(context: android.content.Context) {
        val file = File(context.filesDir, BG_IMAGE_FILE_NAME)
        if (file.exists()) {
            file.delete()
        }
        setBackgroundImagePath(null)
    }

    fun saveBackgroundImage(context: android.content.Context, inputStream: InputStream): Boolean {
        return try {
            val file = File(context.filesDir, BG_IMAGE_FILE_NAME)
            inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            setBackgroundImagePath(file.absolutePath)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isCompatibilityMode(): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_COMPATIBILITY_MODE, false)
    }

    fun applyBackgroundGradient(view: View) {
        if (isCompatibilityMode()) {
            view.setBackgroundColor(opaque(getBgStart()))
            return
        }

        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(opaque(getBgStart()), opaque(getBgCenter()), opaque(getBgEnd()))
        )

        val path = getBackgroundImagePath().orEmpty()
        val imageDrawable = if (path.isNotBlank() && File(path).exists()) {
            Drawable.createFromPath(path)
        } else {
            null
        }

        view.background = if (imageDrawable != null) {
            // Keep a tinted gradient on top for text readability.
            val overlay = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    withAlpha(opaque(getBgStart()), 150),
                    withAlpha(opaque(getBgCenter()), 150),
                    withAlpha(opaque(getBgEnd()), 170)
                )
            )
            LayerDrawable(arrayOf(imageDrawable, overlay))
        } else {
            gradient
        }
    }

    fun createCardDrawable(radius: Float = 24f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius * android.content.res.Resources.getSystem().displayMetrics.density
            setColor(opaque(getCard()))
            setStroke(
                (1 * android.content.res.Resources.getSystem().displayMetrics.density).toInt(),
                opaque(getCardStroke())
            )
        }
    }

    fun createHeaderDrawable(): GradientDrawable {
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        return GradientDrawable(
            GradientDrawable.Orientation.BL_TR,
            intArrayOf(opaque(getHeaderStart()), opaque(getHeaderEnd()))
        ).apply {
            cornerRadius = 28 * dp
            setStroke((1 * dp).toInt(), blendColor(opaque(getCardStroke()), opaque(getAccent()), 0.3f))
        }
    }

    fun createDrawerCardDrawable(): GradientDrawable {
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        val cardWithAccentTint = blendColor(opaque(getCard()), opaque(getAccent()), 0.05f)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * dp
            setColor(cardWithAccentTint)
        }
    }

    fun applyCardStyle(view: android.view.View) {
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        val cardWithAccentTint = blendColor(opaque(getCard()), opaque(getAccent()), 0.05f)
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * dp
            setColor(cardWithAccentTint)
            setStroke((1 * dp).toInt(), opaque(getCardStroke()))
        }
        view.background = drawable
    }

    fun createIconBgDrawable(): GradientDrawable {
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        val accentAlpha = (opaque(getAccent()) and 0x00FFFFFF) or 0x1A000000
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10 * dp
            setColor(accentAlpha)
        }
    }

    fun createServerPanelDrawable(): GradientDrawable {
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = if (isCompatibilityMode()) 0f else getServerRadius() * dp
            setColor(opaque(getCard()))
            setStroke((1 * dp).toInt(), opaque(getCardStroke()))
        }
    }

    fun applySubscriptionBannerStyle(view: android.view.View) {
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        val cardWithAccentTint = blendColor(opaque(getCard()), opaque(getAccent()), 0.03f)
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * dp
            setColor(cardWithAccentTint)
            setStroke((1 * dp).toInt(), opaque(getCardStroke()))
        }
        view.background = drawable
    }

    fun applyButtonStyle(button: com.google.android.material.button.MaterialButton, isPrimary: Boolean = true) {
        if (isPrimary) {
            button.backgroundTintList = ColorStateList.valueOf(opaque(getAccent()))
            button.setTextColor(opaque(getBgStart()))
            button.iconTint = ColorStateList.valueOf(opaque(getBgStart()))
        } else {
            val accentAlpha = (opaque(getAccent()) and 0x00FFFFFF) or 0x33000000
            button.backgroundTintList = ColorStateList.valueOf(accentAlpha)
            button.setTextColor(opaque(getAccent()))
            button.iconTint = ColorStateList.valueOf(opaque(getAccent()))
        }
    }

    fun createServerPanelSelectedDrawable(): GradientDrawable {
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        val selectedBg = if (isCompatibilityMode()) opaque(getCard()) else blendColor(opaque(getCard()), opaque(getAccent()), 0.12f)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = if (isCompatibilityMode()) 0f else getServerRadius() * dp
            setColor(selectedBg)
            setStroke((1 * dp).toInt(), opaque(getAccent()))
        }
    }

    fun createServerPanelActiveDrawable(): GradientDrawable {
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        val activeBg = if (isCompatibilityMode()) opaque(getCard()) else blendColor(opaque(getCard()), opaque(getAccent()), 0.18f)
        val lightAccent = blendColor(opaque(getAccent()), Color.WHITE, 0.4f)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = if (isCompatibilityMode()) 0f else getServerRadius() * dp
            setColor(activeBg)
            setStroke((2 * dp).toInt(), lightAccent)
        }
    }

    fun getAccentOpaque(): Int = opaque(getAccent())

    fun applyAccentToFab(fab: FloatingActionButton) {
        fab.backgroundTintList = ColorStateList.valueOf(opaque(getAccent()))
    }

    fun applyAccentToTabIndicator(tab: TabLayout) {
        tab.setSelectedTabIndicatorColor(opaque(getAccent()))
    }

    fun tintDrawerIcon(icon: ImageView) {
        icon.setColorFilter(opaque(getAccent()))
    }

    fun tintAllDrawerIcons(drawerRoot: View) {
        if (drawerRoot is android.view.ViewGroup) {
            for (i in 0 until drawerRoot.childCount) {
                val child = drawerRoot.getChildAt(i)
                if (child is ImageView) {
                    child.setColorFilter(opaque(getAccent()))
                } else if (child is android.view.ViewGroup) {
                    tintAllDrawerIcons(child)
                }
            }
        }
    }

    fun applyDrawerCardBgs(root: View) {
        if (isCompatibilityMode()) return
        if (root !is android.view.ViewGroup) return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is android.view.ViewGroup) {
                applyDrawerCardBgs(child)
            }
        }
    }

    fun applyTypography(root: View) {
        val family = getFontFamily()
        val scale = getFontScale() / 100f
        val ctx = root.context
        applyTypographyRecursive(ctx, root, family, scale)
    }

    private fun applyTypographyRecursive(context: android.content.Context, view: View, family: String, scale: Float) {
        if (view is TextView) {
            var basePx = view.getTag(R.id.tag_typography_base_text_size_px) as? Float
            if (basePx == null) {
                basePx = view.textSize
                view.setTag(R.id.tag_typography_base_text_size_px, basePx)
            }
            val sample = view.typeface
            val newTf = if (AppFonts.hasBundledFamily(family)) {
                AppFonts.resolveTypeface(context, family, sample)
            } else {
                when (family) {
                    AppFonts.FAMILY_SERIF -> Typeface.SERIF
                    AppFonts.FAMILY_MONO -> Typeface.MONOSPACE
                    AppFonts.FAMILY_DEFAULT -> Typeface.DEFAULT
                    else -> Typeface.SANS_SERIF
                }
            }
            view.typeface = newTf
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, basePx * scale)
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyTypographyRecursive(context, view.getChildAt(i), family, scale)
            }
        }
    }

    private fun blendColor(c1: Int, c2: Int, ratio: Float): Int {
        val r = ((Color.red(c1) * (1 - ratio)) + (Color.red(c2) * ratio)).toInt()
        val g = ((Color.green(c1) * (1 - ratio)) + (Color.green(c2) * ratio)).toInt()
        val b = ((Color.blue(c1) * (1 - ratio)) + (Color.blue(c2) * ratio)).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    fun opaque(rgb: Int): Int = rgb or 0xFF000000.toInt()

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
    }

    private fun readColor(key: String, default: Int): Int {
        return MmkvManager.decodeSettingsInt(key, default)
    }

    private fun writeColor(key: String, value: Int) {
        MmkvManager.encodeSettings(key, value and 0x00FFFFFF)
    }

    data class ThemePreset(
        val name: String,
        val accent: Int,
        val bgStart: Int,
        val bgCenter: Int,
        val bgEnd: Int,
        val card: Int,
        val cardStroke: Int,
        val headerStart: Int,
        val headerEnd: Int
    )

    val PRESETS = listOf(
        ThemePreset(
            "Ocean Blue",
            0x2F81F7, 0x050A14, 0x091224, 0x0B1220,
            0x0F172A, 0x1E2A44, 0x13284A, 0x0B1830
        ),
        ThemePreset(
            "Purple Haze",
            0x9B59B6, 0x0A0515, 0x140A24, 0x1A0E2E,
            0x1E1233, 0x2E1F4A, 0x2D1854, 0x1A0E35
        ),
        ThemePreset(
            "Emerald",
            0x00C853, 0x030F0A, 0x061A10, 0x082214,
            0x0C2A18, 0x1A3D28, 0x0F3A20, 0x082818
        ),
        ThemePreset(
            "Crimson",
            0xE53935, 0x150505, 0x200A0A, 0x280D0D,
            0x2A1010, 0x4A1E1E, 0x3A1515, 0x2A0E0E
        ),
        ThemePreset(
            "Sunset Orange",
            0xFF6D00, 0x150A03, 0x201205, 0x281808,
            0x2A1A0A, 0x4A2E15, 0x3A2210, 0x2A180A
        ),
        ThemePreset(
            "Cyber Pink",
            0xE91E63, 0x150510, 0x200A18, 0x280D1E,
            0x2A1020, 0x4A1E38, 0x3A1530, 0x2A0E22
        ),
        ThemePreset(
            "Arctic",
            0x00BCD4, 0x030E10, 0x061820, 0x082028,
            0x0C282F, 0x1A3D45, 0x0F3A42, 0x08282F
        ),
        ThemePreset(
            "Gold",
            0xFFAB00, 0x15100A, 0x201A0A, 0x282008,
            0x2E2610, 0x4A3D1E, 0x3A3010, 0x2A2208
        ),
        ThemePreset(
            "Forest",
            0x4CAF50, 0x0A150A, 0x102010, 0x182818,
            0x1E301E, 0x2E4A2E, 0x253A25, 0x1A2A1A
        ),
        ThemePreset(
            "Rose",
            0xFF4081, 0x150A10, 0x201018, 0x281520,
            0x2A1822, 0x4A2838, 0x3A2030, 0x2A1822
        ),
        ThemePreset(
            "Teal",
            0x009688, 0x051010, 0x0A1818, 0x0E2020,
            0x122828, 0x1A3D3D, 0x153535, 0x0E2828
        ),
        ThemePreset(
            "Indigo",
            0x3F51B5, 0x0A0E1A, 0x101828, 0x182030,
            0x1E2838, 0x2E3A50, 0x253045, 0x1A2538
        ),
        ThemePreset(
            "Amber",
            0xFFC107, 0x151205, 0x201A08, 0x28200A,
            0x2E2610, 0x4A3D18, 0x3A3012, 0x2A220A
        ),
        ThemePreset(
            "Cyan",
            0x00E5FF, 0x051015, 0x0A1820, 0x0E2028,
            0x122830, 0x1A3D45, 0x153540, 0x0E2830
        ),
        ThemePreset(
            "Lime",
            0xCDDC39, 0x101505, 0x182008, 0x20280A,
            0x283010, 0x3A4518, 0x303A12, 0x28300A
        ),
        ThemePreset(
            "Deep Purple",
            0x7C4DFF, 0x0E0A1A, 0x151028, 0x1C1830,
            0x221E38, 0x322E50, 0x2A2645, 0x201C38
        ),
        ThemePreset(
            "Warm Gray",
            0x9E9E9E, 0x121212, 0x1A1A1A, 0x222222,
            0x2A2A2A, 0x3A3A3A, 0x303030, 0x282828
        ),
        ThemePreset(
            "AMOLED",
            0x2F81F7, 0x000000, 0x000000, 0x000000,
            0x000000, 0x1A1A1A, 0x000000, 0x000000
        )
    )

    fun applyPreset(preset: ThemePreset) {
        setAccent(preset.accent)
        setBgStart(preset.bgStart)
        setBgCenter(preset.bgCenter)
        setBgEnd(preset.bgEnd)
        setCard(preset.card)
        setCardStroke(preset.cardStroke)
        setHeaderStart(preset.headerStart)
        setHeaderEnd(preset.headerEnd)
    }
}
