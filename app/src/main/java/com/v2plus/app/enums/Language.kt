package com.v2plus.app.enums

enum class Language(val code: String) {
    AUTO("auto"),
    ENGLISH("en"),
    CHINA("zh-rCN"),
    TRADITIONAL_CHINESE("zh-rTW"),
    VIETNAMESE("vi"),
    RUSSIAN("ru"),
    BELARUSIAN("be"),
    UKRAINIAN("uk"),
    PERSIAN("fa"),
    ARABIC("ar"),
    BANGLA("bn"),
    BAKHTIARI("bqi-rIR");

    companion object {
        fun fromCode(code: String): Language {
            return entries.find { it.code == code } ?: AUTO
        }
    }
}
