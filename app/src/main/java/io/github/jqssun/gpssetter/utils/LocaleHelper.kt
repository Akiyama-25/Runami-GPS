package io.github.jqssun.gpssetter.utils

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object LocaleHelper {

    fun applyLanguage(langValue: String) {
        val locales = when (langValue) {
            "zh-CN" -> LocaleListCompat.forLanguageTags("zh-CN")
            "zh-TW" -> LocaleListCompat.forLanguageTags("zh-TW")
            "en" -> LocaleListCompat.forLanguageTags("en")
            "ja" -> LocaleListCompat.forLanguageTags("ja")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
