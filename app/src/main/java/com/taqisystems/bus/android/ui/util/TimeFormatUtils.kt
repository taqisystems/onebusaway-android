package com.taqisystems.bus.android.ui.util

import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Returns a [SimpleDateFormat] with pattern "h:mm a" that uses locale-aware AM/PM symbols.
 * For Malay (ms) the symbols are overridden to "PG" (AM) and "PTG" (PM).
 */
fun localizedTimeFormat(pattern: String = "h:mm a"): SimpleDateFormat {
    val locale = Locale.getDefault()
    val sdf = SimpleDateFormat(pattern, locale)
    if (locale.language == "ms" || locale.language == "in") {
        val symbols = DateFormatSymbols(locale)
        symbols.amPmStrings = arrayOf("PG", "PTG")
        sdf.dateFormatSymbols = symbols
    }
    return sdf
}

/**
 * Returns the locale-aware AM/PM period string for the given 24-hour [hour].
 * Returns "PG"/"PTG" for Malay, "AM"/"PM" otherwise.
 */
fun amPmString(hour: Int): String {
    val locale = Locale.getDefault()
    return if (locale.language == "ms" || locale.language == "in") {
        if (hour < 12) "PG" else "PTG"
    } else {
        if (hour < 12) "AM" else "PM"
    }
}
