package com.scrawl.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

enum class PenButtonAction(val label: String) {
    NONE("None"),
    ERASER("Toggle eraser"),
    WRITE_SELECT("Write / Select toggle"),
    UNDO("Undo"),
    RADIAL_MENU("Radial menu");

    companion object {
        fun fromName(name: String): PenButtonAction =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}

object ScrawlSettings {
    private const val PREFS_NAME = "scrawl_settings"
    private const val KEY_RECORDING_FOLDER_URI = "recording_folder_uri"
    private const val KEY_SCREENSHOT_FOLDER_URI = "screenshot_folder_uri"
    private const val KEY_HWR_LANGUAGE = "hwr_language"
    private const val KEY_CUSTOM_LEXICON = "custom_lexicon"
    private const val KEY_TOP_BAR_EDGE = "top_bar_edge"
    private const val KEY_TOP_BAR_OFFSET = "top_bar_offset"
    private const val KEY_BOTTOM_BAR_EDGE = "bottom_bar_edge"
    private const val KEY_BOTTOM_BAR_OFFSET = "bottom_bar_offset"
    private const val KEY_PEN_BUTTON_1 = "pen_button_1"
    private const val KEY_PEN_BUTTON_2 = "pen_button_2"
    private const val KEY_APPEND_NEWLINE_ON_COMMIT = "append_newline_on_commit"
    private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    private const val KEY_TOOLTIPS_SHOWN = "tooltips_shown"
    private const val KEY_PEN_WIDTH = "pen_width"
    private const val KEY_PRESSURE_SENSITIVITY = "pressure_sensitivity"
    private const val KEY_RENDER_BACKEND = "render_backend"

    const val PEN_WIDTH_MIN = 3f
    const val PEN_WIDTH_MAX = 10f
    const val PEN_WIDTH_DEFAULT = 5f
    /** Sensitivity: 0.0 = flat (no pressure effect), 1.0 = max progressive response */
    const val SENSITIVITY_MIN = 0f
    const val SENSITIVITY_MAX = 1f
    const val SENSITIVITY_DEFAULT = 0.5f

    // Set to true to re-trigger onboarding/tooltips for testing
    const val DEBUG_RESET_ONBOARDING = false

    private const val DEFAULT_HWR_LANGUAGE = "en_US"

    val SUPPORTED_LANGUAGES = linkedMapOf(
        "en_US" to "English (US)",
        "en_GB" to "English (UK)",
        "en_CA" to "English (Canada)",
        "zh_CN" to "Chinese (Simplified)",
        "zh_TW" to "Chinese (Traditional)",
        "zh_HK" to "Chinese (Hong Kong)",
        "ja_JP" to "Japanese",
        "ko_KR" to "Korean",
        "af_ZA" to "Afrikaans",
        "az_AZ" to "Azerbaijani",
        "be_BY" to "Belarusian",
        "bg_BG" to "Bulgarian",
        "bs_BA" to "Bosnian",
        "ca_ES" to "Catalan",
        "cs_CZ" to "Czech",
        "da_DK" to "Danish",
        "de_AT" to "German (Austria)",
        "de_DE" to "German",
        "el_GR" to "Greek",
        "es_ES" to "Spanish",
        "es_MX" to "Spanish (Mexico)",
        "et_EE" to "Estonian",
        "eu_ES" to "Basque",
        "fi_FI" to "Finnish",
        "fr_CA" to "French (Canada)",
        "fr_FR" to "French",
        "ga_IE" to "Irish",
        "gl_ES" to "Galician",
        "hr_HR" to "Croatian",
        "hu_HU" to "Hungarian",
        "hy_AM" to "Armenian",
        "id_ID" to "Indonesian",
        "is_IS" to "Icelandic",
        "it_IT" to "Italian",
        "ka_GE" to "Georgian",
        "kk_KZ" to "Kazakh",
        "lt_LT" to "Lithuanian",
        "lv_LV" to "Latvian",
        "mk_MK" to "Macedonian",
        "mn_MN" to "Mongolian",
        "ms_MY" to "Malay",
        "nl_BE" to "Dutch (Belgium)",
        "nl_NL" to "Dutch",
        "no_NO" to "Norwegian",
        "pl_PL" to "Polish",
        "pt_BR" to "Portuguese (BR)",
        "pt_PT" to "Portuguese (PT)",
        "ro_RO" to "Romanian",
        "ru_RU" to "Russian",
        "sk_SK" to "Slovak",
        "sl_SI" to "Slovenian",
        "sq_AL" to "Albanian",
        "sr_Cyrl_RS" to "Serbian (Cyrillic)",
        "sr_Latn_RS" to "Serbian (Latin)",
        "sv_SE" to "Swedish",
        "tr_TR" to "Turkish",
        "tt_RU" to "Tatar",
        "uk_UA" to "Ukrainian",
        "vi_VN" to "Vietnamese",
        "ar_SA" to "Arabic",
        "hi_IN" to "Hindi",
        "th_TH" to "Thai",
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Recording folder ---
    fun getRecordingFolderUri(context: Context): String? =
        prefs(context).getString(KEY_RECORDING_FOLDER_URI, null)?.ifEmpty { null }

    fun setRecordingFolderUri(context: Context, uri: String) =
        prefs(context).edit().putString(KEY_RECORDING_FOLDER_URI, uri).apply()

    fun getRecordingDocumentFile(context: Context): DocumentFile? {
        val uriStr = getRecordingFolderUri(context) ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(uriStr))
    }

    // --- Screenshot folder ---
    fun getScreenshotFolderUri(context: Context): String? =
        prefs(context).getString(KEY_SCREENSHOT_FOLDER_URI, null)?.ifEmpty { null }

    fun setScreenshotFolderUri(context: Context, uri: String) =
        prefs(context).edit().putString(KEY_SCREENSHOT_FOLDER_URI, uri).apply()

    fun getScreenshotDocumentFile(context: Context): DocumentFile? {
        val uriStr = getScreenshotFolderUri(context) ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(uriStr))
    }

    // --- Display helpers ---
    /** Extract a human-readable path from a tree URI for display */
    fun displayPath(uriStr: String?): String {
        if (uriStr.isNullOrEmpty()) return "(not set)"
        try {
            val uri = Uri.parse(uriStr)
            val docId = DocumentsContract.getTreeDocumentId(uri) ?: return uriStr
            // "primary:Documents/obsidian" -> "Documents/obsidian"
            if (docId.startsWith("primary:")) return docId.removePrefix("primary:")
            return docId
        } catch (_: Exception) {
            return uriStr
        }
    }

    // --- Other settings ---
    fun getHwrLanguage(context: Context): String =
        prefs(context).getString(KEY_HWR_LANGUAGE, DEFAULT_HWR_LANGUAGE) ?: DEFAULT_HWR_LANGUAGE

    fun setHwrLanguage(context: Context, lang: String) =
        prefs(context).edit().putString(KEY_HWR_LANGUAGE, lang).apply()

    fun getCustomLexicon(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_LEXICON, "") ?: ""

    fun setCustomLexicon(context: Context, lexicon: String) =
        prefs(context).edit().putString(KEY_CUSTOM_LEXICON, lexicon).apply()

    fun getTopBarEdge(context: Context): String =
        prefs(context).getString(KEY_TOP_BAR_EDGE, "LEFT") ?: "LEFT"

    fun setTopBarEdge(context: Context, edge: String) =
        prefs(context).edit().putString(KEY_TOP_BAR_EDGE, edge).apply()

    fun getTopBarOffset(context: Context): Float =
        prefs(context).getFloat(KEY_TOP_BAR_OFFSET, 0f)

    fun setTopBarOffset(context: Context, offset: Float) =
        prefs(context).edit().putFloat(KEY_TOP_BAR_OFFSET, offset).apply()

    fun getBottomBarEdge(context: Context): String =
        prefs(context).getString(KEY_BOTTOM_BAR_EDGE, "LEFT") ?: "LEFT"

    fun setBottomBarEdge(context: Context, edge: String) =
        prefs(context).edit().putString(KEY_BOTTOM_BAR_EDGE, edge).apply()

    fun getBottomBarOffset(context: Context): Float =
        prefs(context).getFloat(KEY_BOTTOM_BAR_OFFSET, -1f)

    fun setBottomBarOffset(context: Context, offset: Float) =
        prefs(context).edit().putFloat(KEY_BOTTOM_BAR_OFFSET, offset).apply()


    fun getPenButton1(context: Context): PenButtonAction =
        PenButtonAction.fromName(prefs(context).getString(KEY_PEN_BUTTON_1, PenButtonAction.RADIAL_MENU.name) ?: PenButtonAction.RADIAL_MENU.name)

    fun setPenButton1(context: Context, action: PenButtonAction) =
        prefs(context).edit().putString(KEY_PEN_BUTTON_1, action.name).apply()

    fun getPenButton2(context: Context): PenButtonAction =
        PenButtonAction.fromName(prefs(context).getString(KEY_PEN_BUTTON_2, PenButtonAction.RADIAL_MENU.name) ?: PenButtonAction.RADIAL_MENU.name)

    fun setPenButton2(context: Context, action: PenButtonAction) =
        prefs(context).edit().putString(KEY_PEN_BUTTON_2, action.name).apply()

    fun getAppendNewlineOnCommit(context: Context): Boolean =
        prefs(context).getBoolean(KEY_APPEND_NEWLINE_ON_COMMIT, true)

    fun setAppendNewlineOnCommit(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_APPEND_NEWLINE_ON_COMMIT, enabled).apply()

    // --- Pen width & sensitivity ---
    fun getPenWidth(context: Context): Float =
        prefs(context).getFloat(KEY_PEN_WIDTH, PEN_WIDTH_DEFAULT)

    fun setPenWidth(context: Context, width: Float) =
        prefs(context).edit().putFloat(KEY_PEN_WIDTH, width.coerceIn(PEN_WIDTH_MIN, PEN_WIDTH_MAX)).apply()

    fun getPressureSensitivity(context: Context): Float =
        prefs(context).getFloat(KEY_PRESSURE_SENSITIVITY, SENSITIVITY_DEFAULT)

    fun setPressureSensitivity(context: Context, sensitivity: Float) =
        prefs(context).edit().putFloat(KEY_PRESSURE_SENSITIVITY, sensitivity.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)).apply()

    // --- Render backend ---
    // Canvas is the only runtime drawing backend. Old persisted backend values
    // are ignored by the render manager for compatibility.
    fun getRenderBackend(context: Context): String =
        "canvas"

    fun setRenderBackend(context: Context, backend: String) =
        prefs(context).edit().putString(KEY_RENDER_BACKEND, "canvas").apply()

    // --- Onboarding ---
    fun isOnboardingComplete(context: Context): Boolean {
        if (DEBUG_RESET_ONBOARDING) return false
        return prefs(context).getBoolean(KEY_ONBOARDING_COMPLETE, false)
    }

    fun setOnboardingComplete(context: Context) =
        prefs(context).edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()

    fun hasSeenTooltips(context: Context): Boolean {
        if (DEBUG_RESET_ONBOARDING) return false
        return prefs(context).getBoolean(KEY_TOOLTIPS_SHOWN, false)
    }

    fun setTooltipsShown(context: Context) =
        prefs(context).edit().putBoolean(KEY_TOOLTIPS_SHOWN, true).apply()
}
