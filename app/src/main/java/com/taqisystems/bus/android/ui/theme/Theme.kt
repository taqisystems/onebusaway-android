package com.taqisystems.bus.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary                 = BrandConfig.primary,
    onPrimary               = OnPrimary,
    primaryContainer        = PrimaryContainer,
    onPrimaryContainer      = OnPrimaryContainer,
    inversePrimary          = InversePrimary,
    secondary               = BrandConfig.secondary,
    onSecondary             = OnSecondary,
    secondaryContainer      = SecondaryContainer,
    onSecondaryContainer    = OnSecondaryContainer,
    tertiary                = BrandConfig.tertiary,
    onTertiary              = OnTertiary,
    tertiaryContainer       = TertiaryContainer,
    onTertiaryContainer     = OnTertiaryContainer,
    error                   = Error,
    onError                 = OnError,
    errorContainer          = ErrorContainer,
    onErrorContainer        = OnErrorContainer,
    background              = Background,
    onBackground            = OnBackground,
    surface                 = Surface,
    onSurface               = OnSurface,
    onSurfaceVariant        = OnSurfaceVariant,
    surfaceVariant          = SurfaceVariant,
    surfaceContainerLowest  = SurfaceContainerLowest,
    surfaceContainerLow     = SurfaceContainerLow,
    surfaceContainer        = SurfaceContainer,
    surfaceContainerHigh    = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    outline                 = Outline,
    outlineVariant          = OutlineVariant,
    inverseSurface          = InverseSurface,
    inverseOnSurface        = InverseOnSurface,
)

private val DarkColorScheme = darkColorScheme(
    primary                 = BrandConfig.primaryDark,
    onPrimary               = OnPrimaryDark,
    primaryContainer        = PrimaryContainerDark,
    onPrimaryContainer      = OnPrimaryContainerDark,
    inversePrimary          = BrandConfig.primary,
    secondary               = BrandConfig.secondaryDark,
    onSecondary             = OnSecondaryDark,
    secondaryContainer      = SecondaryContainerDark,
    onSecondaryContainer    = OnSecondaryContainerDark,
    tertiary                = TertiaryDark,
    onTertiary              = OnPrimary,
    tertiaryContainer       = TertiaryContainerDark,
    onTertiaryContainer     = OnTertiaryContainerDark,
    error                   = ErrorDark,
    onError                 = OnErrorDark,
    errorContainer          = ErrorContainerDark,
    onErrorContainer        = OnErrorContainerDark,
    background              = BackgroundDark,
    onBackground            = OnSurfaceDark,
    surface                 = SurfaceDark,
    onSurface               = OnSurfaceDark,
    onSurfaceVariant        = OnSurfaceVariantDark,
    surfaceVariant          = SurfaceVariantDark,
    surfaceContainerLowest  = SurfaceContainerLowestDark,
    surfaceContainerLow     = SurfaceContainerLowDark,
    surfaceContainer        = SurfaceContainerDark,
    surfaceContainerHigh    = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    outline                 = OutlineDark,
    outlineVariant          = OutlineVariantDark,
    inverseSurface          = InverseOnSurface,
    inverseOnSurface        = InverseSurface,
)

@Composable
fun KelantanBusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Use background colour for status bar to keep it light/clean
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
