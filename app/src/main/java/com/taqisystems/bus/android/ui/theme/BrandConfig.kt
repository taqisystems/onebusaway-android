// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.ui.theme

import androidx.compose.ui.graphics.Color
import com.taqisystems.bus.android.BuildConfig

/**
 * Centralised white-label brand configuration.
 *
 * All values are driven by [BuildConfig] fields that are populated at
 * compile-time from `local.properties` (see `app/build.gradle.kts`).
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  local.properties key      │  What it controls                          │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │  APP_NAME                  │  Launcher label, About screen, onboarding  │
 * │  APP_PRIMARY_COLOR  #RRGGBB│  Primary brand colour (buttons, toolbar…)  │
 * │  APP_SECONDARY_COLOR       │  Accent chips, live-status badges, tabs    │
 * │  APP_TERTIARY_COLOR        │  Supporting ink / icon tint                │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * For launcher icon, in-app logo, and notification alert sound, place overrides
 * in the appropriate product-flavour source set:
 *   app/src/<flavourName>/res/drawable/logo.png          ← in-app / onboarding logo
 *   app/src/<flavourName>/res/mipmap-DENSITY/ic_launcher*.png
 *   app/src/<flavourName>/res/raw/alert.wav              ← notification alert sound
 *     Supported formats: WAV, OGG (recommended), MP3.
 *     Keep under 1 MB / 30 s. The channel ID is scoped to packageName so the
 *     OS always picks up the new sound even if another brand was installed before.
 */
object BrandConfig {

    /** Human-readable app name shown in About screen, onboarding, etc. */
    val appName: String
        get() = BuildConfig.APP_NAME

    /** Primary Material 3 colour role (e.g. toolbar, FAB, active nav icon). */
    val primary: Color by lazy { hex(BuildConfig.BRAND_PRIMARY) }

    /** Secondary colour role (accent chips, live badges, active tab indicator). */
    val secondary: Color by lazy { hex(BuildConfig.BRAND_SECONDARY) }

    /** Tertiary colour role (supporting UI, secondary text, icon tints). */
    val tertiary: Color by lazy { hex(BuildConfig.BRAND_TERTIARY) }

    // ── Derived dark-theme variants ───────────────────────────────────────────
    // We lighten the primary 40 % (blend with white) for the dark theme so the
    // brand colour remains accessible on dark surfaces without reconfiguring
    // additional local.properties keys.

    val primaryDark: Color by lazy { primary.blend(Color.White, 0.40f) }
    val secondaryDark: Color by lazy { secondary.blend(Color.White, 0.28f) }

    // ─────────────────────────────────────────────────────────────────────────

    private fun hex(color: String): Color =
        Color(android.graphics.Color.parseColor(color))
}

/** Blend [this] colour toward [other] by [fraction] (0 = no change, 1 = full [other]). */
private fun Color.blend(other: Color, fraction: Float): Color {
    val inv = 1f - fraction
    return Color(
        red   = red   * inv + other.red   * fraction,
        green = green * inv + other.green * fraction,
        blue  = blue  * inv + other.blue  * fraction,
        alpha = alpha,
    )
}
