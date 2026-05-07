// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.ui.theme

import androidx.compose.ui.graphics.Color

// ─── Design system palette — "Transit Red" ───────────────────────────────────
//
// Inspired by world-class public transit brands (TfL, KL Rapid, Singapore MRT).
// Bold, confident red for primary actions; clean neutral surfaces so
// real-time data stays readable; vibrant accent for chips and status badges.

// Primary: "Transit Red" — C62828 (Material Red 800); bold, legible, iconic
val Primary             = Color(0xFFC62828)
val OnPrimary           = Color(0xFFFFFFFF)
val PrimaryContainer    = Color(0xFFFFCDD2)  // soft Red-100 tint for cards/chips
val OnPrimaryContainer  = Color(0xFF7F0000)  // deep red on light container
val InversePrimary      = Color(0xFFEF9A9A)  // light coral on dark surfaces

// Secondary: "Signal Red" — slightly brighter for active tabs, FABs, live badge
val Secondary               = Color(0xFFE53935)  // Red 600 — energetic accent
val OnSecondary             = Color(0xFFFFFFFF)
val SecondaryContainer      = Color(0xFFFFEBEE)  // Red 50 — very subtle tint
val OnSecondaryContainer    = Color(0xFF7F0000)

// Tertiary: warm neutral ink for supporting UI elements
val Tertiary            = Color(0xFF37474F)  // Blue-Grey 700
val OnTertiary          = Color(0xFFFFFFFF)
val TertiaryContainer   = Color(0xFFECEFF1)  // Blue-Grey 50
val OnTertiaryContainer = Color(0xFF263238)

// Background / Surface — clean white with an imperceptibly warm blush
// Keeps the map and data front and centre without competing with red
val Background              = Color(0xFFFFFBFB)
val OnBackground            = Color(0xFF1C1B1B)
val Surface                 = Color(0xFFFFFBFB)
val SurfaceDim              = Color(0xFFE6D6D6)
val SurfaceBright           = Color(0xFFFFFBFB)
val SurfaceVariant          = Color(0xFFF5DDDD)  // faint red tint in variant containers
val SurfaceContainerLowest  = Color(0xFFFFFFFF)
val SurfaceContainerLow     = Color(0xFFFFF0F0)  // lightest blush — card backgrounds
val SurfaceContainer        = Color(0xFFFFE8E8)
val SurfaceContainerHigh    = Color(0xFFFFDEDE)
val SurfaceContainerHighest = Color(0xFFFFD6D6)
val OnSurface               = Color(0xFF1C1B1B)
val OnSurfaceVariant        = Color(0xFF4E4040)
val InverseSurface          = Color(0xFF312020)
val InverseOnSurface        = Color(0xFFFFEDED)

// Outline
val Outline        = Color(0xFF857070)
val OutlineVariant = Color(0xFFD9C4C4)

// Error — mapped to amber/orange so it contrasts with the red brand
val Error            = Color(0xFFB45309)  // amber-700 — distinct from transit red
val OnError          = Color(0xFFFFFFFF)
val ErrorContainer   = Color(0xFFFEF3C7)
val OnErrorContainer = Color(0xFF78350F)

// ─── Named aliases ────────────────────────────────────────────────────────────
/** Brand accent — Signal Red used for interactive elements and transit chips. */
val Blue600 = Secondary     // 0xFFE53935

// ─── Status colours ──────────────────────────────────────────────────────────
val StatusOnTime    = Color(0xFF16A34A)   // green
val StatusDelayed   = Color(0xFF1A73E8)   // blue
val StatusEarly     = Color(0xFFDC2626)   // red
val StatusScheduled = Color(0xFF6B7280)   // cool grey

// ─── Dark-theme colours ──────────────────────────────────────────────────────
val PrimaryDark     = Color(0xFFEF9A9A)   // coral on dark
val BackgroundDark  = Color(0xFF1C1B1B)
val SurfaceDark     = Color(0xFF231A1A)   // dark with subtle red tint
val OnSurfaceDark   = Color(0xFFEDE0E0)

// Dark surface container ramp (darkest → highest)
val SurfaceContainerLowestDark  = Color(0xFF180F0F)
val SurfaceContainerLowDark     = Color(0xFF2A1A1A)
val SurfaceContainerDark        = Color(0xFF321F1F)
val SurfaceContainerHighDark    = Color(0xFF3A2424)
val SurfaceContainerHighestDark = Color(0xFF422929)

// Dark primary containers — deep red so chips don't blind on dark bg
val PrimaryContainerDark    = Color(0xFF7F0000)   // dark red container
val OnPrimaryContainerDark  = Color(0xFFFFCDD2)   // light pink text on it
val OnPrimaryDark           = Color(0xFF7F0000)   // dark text on coral primary

// Dark secondary
val SecondaryDark               = Color(0xFFE57373)   // Red 300
val OnSecondaryDark             = Color(0xFF7F0000)
val SecondaryContainerDark      = Color(0xFF7F0000)
val OnSecondaryContainerDark    = Color(0xFFFFCDD2)

// Dark tertiary
val TertiaryDark            = Color(0xFF90A4AE)   // Blue-Grey 300
val TertiaryContainerDark   = Color(0xFF263238)   // Blue-Grey 900
val OnTertiaryContainerDark = Color(0xFFECEFF1)

// Dark error — amber shifted lighter for dark backgrounds
val ErrorDark            = Color(0xFFFFB74D)   // Amber 300
val OnErrorDark          = Color(0xFF3E2000)
val ErrorContainerDark   = Color(0xFF5E2F00)
val OnErrorContainerDark = Color(0xFFFFCC80)

// Dark outlines / variants
val OnSurfaceVariantDark = Color(0xFFC4A8A8)   // muted warm pink-grey
val SurfaceVariantDark   = Color(0xFF4A3030)
val OutlineDark          = Color(0xFF8A6C6C)
val OutlineVariantDark   = Color(0xFF513C3C)
