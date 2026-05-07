// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.ui.util

import android.content.Context
import androidx.annotation.StringRes

/**
 * A sealed class that wraps either a string-resource reference (resolved at the View layer
 * using `Context.getString`) or a raw string (typically from network/API responses or
 * exception messages that are already formatted).
 *
 * ViewModels emit `UiText` for any user-visible message; composables resolve it via
 * [resolve] and the local `Context` obtained from `LocalContext.current`.
 */
sealed class UiText {
    /**
     * A message backed by a string resource ID.
     *
     * @param id   The `R.string.*` resource identifier.
     * @param args Optional format arguments forwarded to [Context.getString].
     */
    data class Resource(
        @StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText()

    /**
     * A raw (pre-formatted) string — use for network error messages, API responses,
     * or any text that should not be translated.
     */
    data class Raw(val value: String) : UiText()
}

/**
 * Resolves this [UiText] to a displayable [String] using the supplied [context].
 *
 * ```kotlin
 * // In a composable:
 * val ctx = LocalContext.current
 * Text(uiState.error?.resolve(ctx) ?: "")
 * ```
 */
fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Resource -> if (args.isEmpty()) {
        context.getString(id)
    } else {
        context.getString(id, *args.toTypedArray())
    }
    is UiText.Raw      -> value
}
