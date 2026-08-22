package com.v2plus.app.ui

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.v2plus.app.R
import com.v2plus.app.extension.toast
import com.v2plus.app.handler.AppFonts
import com.v2plus.app.handler.CustomizationManager
import com.v2plus.app.handler.CustomizationManager.opaque
import com.v2plus.app.handler.SettingsChangeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL


class CustomizationActivity : BaseActivity() {

    private data class ColorOption(
        val label: String,
        val getter: () -> Int,
        val setter: (Int) -> Unit
    )

    private lateinit var colorOptions: List<ColorOption>
    private lateinit var optionViews: List<View>
    private lateinit var presetAdapter: PresetAdapter
    private var hasChanges = false
    private var themeFadeOverlay: ImageView? = null
    private val themeInterp = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    private val pickBackgroundImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            applyBackgroundFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(R.layout.activity_customization, showHomeAsUp = true, title = getString(R.string.cust_title))

        colorOptions = listOf(
            ColorOption(getString(R.string.cust_accent), CustomizationManager::getAccent, CustomizationManager::setAccent),
            ColorOption(getString(R.string.cust_bg_start), CustomizationManager::getBgStart, CustomizationManager::setBgStart),
            ColorOption(getString(R.string.cust_bg_center), CustomizationManager::getBgCenter, CustomizationManager::setBgCenter),
            ColorOption(getString(R.string.cust_bg_end), CustomizationManager::getBgEnd, CustomizationManager::setBgEnd),
            ColorOption(getString(R.string.cust_card), CustomizationManager::getCard, CustomizationManager::setCard),
            ColorOption(getString(R.string.cust_card_stroke), CustomizationManager::getCardStroke, CustomizationManager::setCardStroke),
            ColorOption(getString(R.string.cust_header_start), CustomizationManager::getHeaderStart, CustomizationManager::setHeaderStart),
            ColorOption(getString(R.string.cust_header_end), CustomizationManager::getHeaderEnd, CustomizationManager::setHeaderEnd),
            ColorOption(getString(R.string.cust_text_primary), CustomizationManager::getTextPrimary, CustomizationManager::setTextPrimary),
            ColorOption(getString(R.string.cust_text_secondary), CustomizationManager::getTextSecondary, CustomizationManager::setTextSecondary),
            ColorOption(getString(R.string.cust_text_tertiary), CustomizationManager::getTextTertiary, CustomizationManager::setTextTertiary),
            ColorOption(getString(R.string.cust_icon_color), CustomizationManager::getIconColor, CustomizationManager::setIconColor),
            ColorOption(getString(R.string.cust_success_color), CustomizationManager::getSuccessColor, CustomizationManager::setSuccessColor),
            ColorOption(getString(R.string.cust_error_color), CustomizationManager::getErrorColor, CustomizationManager::setErrorColor),
            ColorOption(getString(R.string.cust_warning_color), CustomizationManager::getWarningColor, CustomizationManager::setWarningColor),
        )

        optionViews = listOf(
            findViewById(R.id.opt_accent),
            findViewById(R.id.opt_bg_start),
            findViewById(R.id.opt_bg_center),
            findViewById(R.id.opt_bg_end),
            findViewById(R.id.opt_card),
            findViewById(R.id.opt_card_stroke),
            findViewById(R.id.opt_header_start),
            findViewById(R.id.opt_header_end),
            findViewById(R.id.opt_text_primary),
            findViewById(R.id.opt_text_secondary),
            findViewById(R.id.opt_text_tertiary),
            findViewById(R.id.opt_icon_color),
            findViewById(R.id.opt_success_color),
            findViewById(R.id.opt_error_color),
            findViewById(R.id.opt_warning_color),
        )

        setupColorOptions()
        setupPresets()
        setupBackgroundImageActions()
        setupTypographyOptions()
        setupAdvancedOptions()
        updatePreview()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hasChanges) {
                    showRestartDialog()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        findViewById<View>(R.id.btn_reset).setOnClickListener {
            CustomizationManager.resetAll()
            CustomizationManager.clearBackgroundImage(this)
            setupColorOptions()
            setupTypographyOptions()
            setupAdvancedOptions()
            presetAdapter.refreshSelection()
            updatePreview()
            onThemeChanged()
        }
    }

    private fun setupBackgroundImageActions() {
        findViewById<View>(R.id.btn_bg_gallery).setOnClickListener {
            pickBackgroundImageLauncher.launch("image/*")
        }

        findViewById<View>(R.id.btn_bg_url).setOnClickListener {
            showBackgroundUrlDialog()
        }

        findViewById<View>(R.id.btn_bg_clear).setOnClickListener {
            CustomizationManager.clearBackgroundImage(this)
            updatePreview()
            onThemeChanged()
            toast(R.string.cust_bg_set_success)
        }
    }

    private fun showBackgroundUrlDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.cust_bg_url_hint)
            setTextColor(Color.parseColor("#E5E7EB"))
            setHintTextColor(Color.parseColor("#556677"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(getString(R.string.cust_bg_url_title))
            .setView(input)
            .setPositiveButton(getString(R.string.cust_apply_hex)) { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    toast(R.string.cust_bg_url_invalid)
                    return@setPositiveButton
                }
                applyBackgroundFromUrl(url)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyBackgroundFromUri(uri: Uri) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                val input = contentResolver.openInputStream(uri) ?: return@withContext false
                CustomizationManager.saveBackgroundImage(this@CustomizationActivity, input)
            }
            if (success) {
                updatePreview()
                onThemeChanged()
                toast(R.string.cust_bg_set_success)
            } else {
                toast(R.string.cust_bg_set_failed)
            }
        }
    }

    private fun applyBackgroundFromUrl(url: String) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10000
                        readTimeout = 15000
                        instanceFollowRedirects = true
                    }
                    conn.connect()
                    if (conn.responseCode !in 200..299) {
                        return@withContext false
                    }
                    val input = conn.inputStream ?: return@withContext false
                    CustomizationManager.saveBackgroundImage(this@CustomizationActivity, input)
                } catch (_: Exception) {
                    false
                } finally {
                    conn?.disconnect()
                }
            }

            if (success) {
                updatePreview()
                onThemeChanged()
                toast(R.string.cust_bg_set_success)
            } else {
                toast(R.string.cust_bg_set_failed)
            }
        }
    }

    private fun applyRootBackground() {
        val contentView = findViewById<View>(android.R.id.content)
        val root = (contentView as? ViewGroup)?.getChildAt(0) ?: return
        CustomizationManager.applyBackgroundGradient(root)
    }

    private fun onThemeChanged() {
        hasChanges = true
        SettingsChangeManager.makeRestartService()
    }

    private fun showRestartDialog() {
        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle(getString(R.string.cust_restart_title))
            .setMessage(getString(R.string.cust_restart_message))
            .setPositiveButton(getString(R.string.cust_restart_now)) { _, _ ->
                restartApp()
            }
            .setNegativeButton(getString(R.string.cust_restart_later), null)
            .setCancelable(false)
            .show()
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (hasChanges) {
            showRestartDialog()
            return true
        }
        return super.onSupportNavigateUp()
    }

    private fun setupColorOptions() {
        colorOptions.forEachIndexed { i, opt ->
            val view = optionViews[i]
            val preview = view.findViewById<View>(R.id.color_preview)
            val label = view.findViewById<TextView>(R.id.color_label)
            val value = view.findViewById<TextView>(R.id.color_value)

            label.text = opt.label
            updateColorOptionView(preview, value, opt.getter())

            view.setOnClickListener {
                showColorPickerSheet(opt.label, opt.getter()) { color ->
                    opt.setter(color)
                    updateColorOptionView(preview, value, color)
                    updatePreview()
                    onThemeChanged()
                }
            }
        }
    }

    private fun updateColorOptionView(preview: View, valueText: TextView, color: Int) {
        val dp = resources.displayMetrics.density
        val gd = GradientDrawable()
        gd.shape = GradientDrawable.RECTANGLE
        gd.cornerRadius = 8 * dp
        gd.setColor(opaque(color))
        gd.setStroke((1 * dp).toInt(), Color.parseColor("#334155"))
        preview.background = gd
        valueText.text = String.format("#%06X", color and 0xFFFFFF)
    }

    private fun applyThemeWithAnimation(block: () -> Unit) {
        val stage = findViewById<FrameLayout>(R.id.preview_stage)
        val animationsOff = Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f

        if (animationsOff || stage.width <= 0 || stage.height <= 0) {
            block()
            updatePreview()
            return
        }

        themeFadeOverlay?.let { old ->
            old.animate().cancel()
            (old.parent as? ViewGroup)?.removeView(old)
        }

        val snapshot = Bitmap.createBitmap(stage.width, stage.height, Bitmap.Config.ARGB_8888)
        stage.draw(Canvas(snapshot))
        val overlay = ImageView(this).apply {
            setImageBitmap(snapshot)
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true
        }
        themeFadeOverlay = overlay
        stage.addView(overlay)

        block()
        updatePreview()
        overlay.bringToFront()

        stage.scaleX = 0.985f
        stage.scaleY = 0.985f
        stage.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(320)
            .setInterpolator(themeInterp)
            .start()

        listOf(R.id.surface_bg, R.id.surface_colors, R.id.surface_fonts, R.id.surface_shape).forEach { id ->
            val surface = findViewById<View>(id)
            surface.animate().cancel()
            surface.alpha = 0.45f
            surface.animate()
                .alpha(1f)
                .setDuration(280)
                .setInterpolator(themeInterp)
                .start()
        }

        overlay.animate()
            .alpha(0f)
            .setDuration(280)
            .setInterpolator(themeInterp)
            .withEndAction {
                (overlay.parent as? ViewGroup)?.removeView(overlay)
                if (themeFadeOverlay === overlay) {
                    themeFadeOverlay = null
                }
                snapshot.recycle()
            }
            .start()
    }

    private fun updatePreview() {
        applyRootBackground()

        val dp = resources.displayMetrics.density
        val accent = opaque(CustomizationManager.getAccent())
        val cardRadius = findViewById<SeekBar>(R.id.seek_card_radius).progress.toFloat()
        val headerRadius = findViewById<SeekBar>(R.id.seek_header_radius).progress.toFloat()
        val serverRadius = findViewById<SeekBar>(R.id.seek_server_radius).progress.toFloat()

        val stage = findViewById<View>(R.id.preview_stage)
        CustomizationManager.applyBackgroundGradient(stage)
        applyRoundedClip(stage, cardRadius * dp)

        findViewById<View>(R.id.preview_header).background = GradientDrawable(
            GradientDrawable.Orientation.BL_TR,
            intArrayOf(opaque(CustomizationManager.getHeaderStart()), opaque(CustomizationManager.getHeaderEnd()))
        ).apply {
            cornerRadius = headerRadius * dp
        }

        findViewById<View>(R.id.preview_sample_card).background =
            CustomizationManager.createCardDrawable(serverRadius)

        findViewById<View>(R.id.preview_accent_bar).background = GradientDrawable().apply {
            setColor(accent)
            cornerRadius = 2 * dp
        }
        findViewById<View>(R.id.preview_header_dot).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accent)
        }

        val brand = findViewById<TextView>(R.id.preview_brand)
        val title = findViewById<TextView>(R.id.preview_title)
        val status = findViewById<TextView>(R.id.preview_status)
        val fontPreview = findViewById<TextView>(R.id.tv_font_preview)

        brand.setTextColor(opaque(CustomizationManager.getTextPrimary()))
        title.setTextColor(opaque(CustomizationManager.getTextPrimary()))
        status.setTextColor(opaque(CustomizationManager.getSuccessColor()))
        fontPreview.setTextColor(opaque(CustomizationManager.getTextPrimary()))
        applyPreviewFont(brand)
        applyPreviewFont(title)
        applyPreviewFont(fontPreview)

        listOf(R.id.surface_bg, R.id.surface_colors, R.id.surface_fonts, R.id.surface_shape).forEach { id ->
            CustomizationManager.applyCardStyle(findViewById(id))
        }

        val iconTint = ColorStateList.valueOf(opaque(CustomizationManager.getIconColor()))
        ImageViewCompat.setImageTintList(findViewById(R.id.btn_bg_gallery), iconTint)
        ImageViewCompat.setImageTintList(findViewById(R.id.btn_bg_url), iconTint)
        ImageViewCompat.setImageTintList(findViewById(R.id.btn_bg_clear), iconTint)

        tintSeekBar(findViewById(R.id.seek_font_scale), accent)
        tintSeekBar(findViewById(R.id.seek_card_radius), accent)
        tintSeekBar(findViewById(R.id.seek_header_radius), accent)
        tintSeekBar(findViewById(R.id.seek_server_radius), accent)
        tintSeekBar(findViewById(R.id.seek_drawer_radius), accent)
        tintSeekBar(findViewById(R.id.seek_bg_overlay), accent)

        updateBackgroundStatus()
        if (::presetAdapter.isInitialized) {
            presetAdapter.refreshSelection()
        }
    }

    private fun updateBackgroundStatus() {
        val thumb = findViewById<ImageView>(R.id.img_bg_thumb)
        val status = findViewById<TextView>(R.id.tv_bg_status)
        val dp = resources.displayMetrics.density
        thumb.background = GradientDrawable().apply {
            cornerRadius = 12 * dp
            setColor(opaque(CustomizationManager.getCard()))
            setStroke((1 * dp).toInt(), opaque(CustomizationManager.getCardStroke()))
        }
        applyRoundedClip(thumb, 12 * dp)
        if (CustomizationManager.hasBackgroundImage()) {
            status.text = getString(R.string.cust_bg_applied)
            thumb.setImageDrawable(Drawable.createFromPath(CustomizationManager.getBackgroundImagePath()))
        } else {
            status.text = getString(R.string.cust_bg_none)
            thumb.setImageDrawable(null)
        }
        status.setTextColor(opaque(CustomizationManager.getTextPrimary()))
    }

    private fun applyRoundedClip(view: View, radiusPx: Float) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
        if (view.width == 0 || view.height == 0) {
            view.post { view.invalidateOutline() }
        } else {
            view.invalidateOutline()
        }
    }

    private fun tintSeekBar(seekBar: SeekBar, accent: Int) {
        val tint = ColorStateList.valueOf(accent)
        seekBar.progressTintList = tint
        seekBar.thumbTintList = tint
    }

    private fun setupPresets() {
        val rv = findViewById<RecyclerView>(R.id.rv_presets)
        rv.layoutManager = GridLayoutManager(this, 2)
        rv.isNestedScrollingEnabled = false
        presetAdapter = PresetAdapter(CustomizationManager.PRESETS) { preset ->
            applyThemeWithAnimation {
                CustomizationManager.applyPreset(preset)
                setupColorOptions()
                setupTypographyOptions()
                setupAdvancedOptions()
            }
            onThemeChanged()
        }
        rv.adapter = presetAdapter
        presetAdapter.refreshSelection()
    }

    private fun setupAdvancedOptions() {
        bindSlider(
            seek = findViewById(R.id.seek_card_radius),
            value = findViewById(R.id.tv_card_radius),
            initial = CustomizationManager.getCardRadius().toInt(),
            format = { it.toString() },
            persist = { CustomizationManager.setCardRadius(it.toFloat()) }
        )
        bindSlider(
            seek = findViewById(R.id.seek_header_radius),
            value = findViewById(R.id.tv_header_radius),
            initial = CustomizationManager.getHeaderRadius().toInt(),
            format = { it.toString() },
            persist = { CustomizationManager.setHeaderRadius(it.toFloat()) }
        )
        bindSlider(
            seek = findViewById(R.id.seek_server_radius),
            value = findViewById(R.id.tv_server_radius),
            initial = CustomizationManager.getServerRadius().toInt(),
            format = { it.toString() },
            persist = { CustomizationManager.setServerRadius(it.toFloat()) }
        )
        bindSlider(
            seek = findViewById(R.id.seek_drawer_radius),
            value = findViewById(R.id.tv_drawer_radius),
            initial = CustomizationManager.getDrawerRadius().toInt(),
            format = { it.toString() },
            persist = { CustomizationManager.setDrawerRadius(it.toFloat()) }
        )
        bindSlider(
            seek = findViewById(R.id.seek_bg_overlay),
            value = findViewById(R.id.tv_bg_overlay),
            initial = CustomizationManager.getBgOverlay(),
            format = { "${it * 100 / 255}%" },
            persist = { CustomizationManager.setBgOverlay(it) }
        )
    }

    private fun bindSlider(
        seek: SeekBar,
        value: TextView,
        initial: Int,
        format: (Int) -> String,
        persist: (Int) -> Unit
    ) {
        seek.progress = initial
        value.text = format(initial)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                value.text = format(progress)
                if (fromUser) {
                    updatePreview()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                persist(seek.progress)
                updatePreview()
                onThemeChanged()
            }
        })
    }

    private fun setupTypographyOptions() {
        val dropdown = findViewById<MaterialAutoCompleteTextView>(R.id.dropdown_font_family)
        val seekScale = findViewById<SeekBar>(R.id.seek_font_scale)
        val scaleValue = findViewById<TextView>(R.id.tv_font_scale_value)
        val preview = findViewById<TextView>(R.id.tv_font_preview)

        val labels = resources.getStringArray(R.array.font_family_entries).toList()
        val values = resources.getStringArray(R.array.font_family_values)

        dropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        val currentFamily = CustomizationManager.getFontFamily()
        val currentIndex = values.indexOf(currentFamily).takeIf { it >= 0 } ?: 0
        dropdown.setText(labels[currentIndex], false)

        val initialScale = (CustomizationManager.getFontScale() - 80).coerceIn(0, 60)
        seekScale.progress = initialScale
        scaleValue.text = "${initialScale + 80}%"
        applyPreviewFont(preview)

        dropdown.setOnItemClickListener { _, _, position, _ ->
            val selected = values[position]
            if (CustomizationManager.getFontFamily() != selected) {
                CustomizationManager.setFontFamily(selected)
                applyPreviewFont(preview)
                updatePreview()
                onThemeChanged()
            }
        }

        seekScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                scaleValue.text = "${progress + 80}%"
                if (fromUser) {
                    applyPreviewFont(preview)
                    updatePreview()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val newScale = (seekScale.progress + 80).coerceIn(80, 140)
                if (CustomizationManager.getFontScale() != newScale) {
                    CustomizationManager.setFontScale(newScale)
                    applyPreviewFont(preview)
                    updatePreview()
                    onThemeChanged()
                }
            }
        })
    }

    private fun applyPreviewFont(preview: TextView) {
        val family = CustomizationManager.getFontFamily()
        preview.typeface = if (AppFonts.hasBundledFamily(family)) {
            AppFonts.resolveTypeface(this, family, preview.typeface)
        } else {
            when (family) {
                AppFonts.FAMILY_SERIF -> Typeface.SERIF
                AppFonts.FAMILY_MONO -> Typeface.MONOSPACE
                AppFonts.FAMILY_DEFAULT -> Typeface.DEFAULT
                else -> Typeface.SANS_SERIF
            }
        }
        val liveScale = findViewById<SeekBar>(R.id.seek_font_scale).progress + 80
        preview.textSize = 16f * (liveScale / 100f)
    }

    private class PresetAdapter(
        private val presets: List<CustomizationManager.ThemePreset>,
        private val onClick: (CustomizationManager.ThemePreset) -> Unit
    ) : RecyclerView.Adapter<PresetAdapter.VH>() {

        private var selectedIndex: Int = -1
        private var animateCheckAt: Int = -1

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val stage: View = view.findViewById(R.id.preset_stage)
            val header: View = view.findViewById(R.id.preset_header)
            val headerDot: View = view.findViewById(R.id.preset_header_dot)
            val headerLine: View = view.findViewById(R.id.preset_header_line)
            val miniCard: View = view.findViewById(R.id.preset_mini_card)
            val accentBar: View = view.findViewById(R.id.preset_accent_bar)
            val linePrimary: View = view.findViewById(R.id.preset_line_primary)
            val lineSecondary: View = view.findViewById(R.id.preset_line_secondary)
            val check: View = view.findViewById(R.id.preset_check)
            val name: TextView = view.findViewById(R.id.preset_name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_theme_preset, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val preset = presets[position]
            val dp = holder.itemView.resources.displayMetrics.density
            val selected = position == selectedIndex

            holder.stage.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(opaque(preset.bgStart), opaque(preset.bgCenter), opaque(preset.bgEnd))
            ).apply {
                cornerRadius = 16 * dp
                setStroke(
                    ((if (selected) 2 else 1) * dp).toInt(),
                    if (selected) opaque(preset.accent) else opaque(preset.cardStroke)
                )
            }

            holder.header.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(opaque(preset.headerStart), opaque(preset.headerEnd))
            ).apply {
                cornerRadius = 10 * dp
            }

            holder.headerDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(opaque(preset.accent))
            }
            holder.headerLine.background = GradientDrawable().apply {
                cornerRadius = 3 * dp
                setColor((opaque(preset.accent) and 0x00FFFFFF) or 0x66000000)
            }

            holder.miniCard.background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(opaque(preset.card))
                setStroke((1 * dp).toInt(), opaque(preset.cardStroke))
            }
            holder.accentBar.background = GradientDrawable().apply {
                cornerRadius = 2 * dp
                setColor(opaque(preset.accent))
            }
            holder.linePrimary.background = GradientDrawable().apply {
                cornerRadius = 3 * dp
                setColor(0xE6E8EAF6.toInt())
            }
            holder.lineSecondary.background = GradientDrawable().apply {
                cornerRadius = 3 * dp
                setColor(0x99B0BEC5.toInt())
            }

            holder.check.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(opaque(preset.accent))
            }
            holder.check.animate().cancel()
            if (selected) {
                holder.check.visibility = View.VISIBLE
                if (position == animateCheckAt) {
                    animateCheckAt = -1
                    holder.check.alpha = 0f
                    holder.check.scaleX = 0.65f
                    holder.check.scaleY = 0.65f
                    holder.check.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(220)
                        .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f))
                        .start()
                    holder.stage.animate().cancel()
                    holder.stage.scaleX = 0.97f
                    holder.stage.scaleY = 0.97f
                    holder.stage.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(280)
                        .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f))
                        .start()
                } else {
                    holder.check.alpha = 1f
                    holder.check.scaleX = 1f
                    holder.check.scaleY = 1f
                }
            } else {
                holder.check.visibility = View.GONE
                holder.check.alpha = 1f
                holder.check.scaleX = 1f
                holder.check.scaleY = 1f
                holder.stage.scaleX = 1f
                holder.stage.scaleY = 1f
            }
            holder.name.text = preset.name
            holder.itemView.setOnClickListener { onClick(preset) }
        }

        override fun getItemCount() = presets.size

        fun refreshSelection() {
            val next = presets.indexOfFirst { matchesCurrent(it) }
            if (next == selectedIndex) return
            val old = selectedIndex
            selectedIndex = next
            if (next >= 0) animateCheckAt = next
            if (old >= 0) notifyItemChanged(old)
            if (next >= 0) notifyItemChanged(next)
        }

        private fun matchesCurrent(preset: CustomizationManager.ThemePreset): Boolean {
            return preset.accent == CustomizationManager.getAccent() &&
                preset.bgStart == CustomizationManager.getBgStart() &&
                preset.bgCenter == CustomizationManager.getBgCenter() &&
                preset.bgEnd == CustomizationManager.getBgEnd() &&
                preset.card == CustomizationManager.getCard() &&
                preset.cardStroke == CustomizationManager.getCardStroke() &&
                preset.headerStart == CustomizationManager.getHeaderStart() &&
                preset.headerEnd == CustomizationManager.getHeaderEnd()
        }
    }
}
