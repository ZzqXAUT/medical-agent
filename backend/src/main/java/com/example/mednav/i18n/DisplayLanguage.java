package com.example.mednav.i18n;

import java.util.regex.Pattern;

/** Resolves the language used for patient-facing text without changing internal enums. */
public final class DisplayLanguage {

    public static final String ZH_CN = "zh-CN";
    public static final String EN_US = "en-US";
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");
    private static final Pattern LATIN = Pattern.compile("[A-Za-z]");

    private DisplayLanguage() {}

    public static String resolve(String text, String fallback) {
        if (text != null && HAN.matcher(text).find()) {
            return ZH_CN;
        }
        if (text != null && LATIN.matcher(text).find()) {
            return EN_US;
        }
        return normalize(fallback);
    }

    public static String normalize(String language) {
        return language != null && language.toLowerCase().startsWith("en") ? EN_US : ZH_CN;
    }

    public static boolean isChinese(String language) {
        return ZH_CN.equals(normalize(language));
    }
}
