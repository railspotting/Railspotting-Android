package com.railspotting.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// branding/brand-guide.md (receiver submodule)
val Tile = Color(0xFF16191C)
val Amber = Color(0xFFE0A100)
val Rail = Color(0xFFF1EFE9)
private val Paper = Color(0xFFF4F2EE)
private val Slate = Color(0xFF8A9099)
private val Coral = Color(0xFFFF6B61)

private val Dark = darkColorScheme(
    primary = Amber, onPrimary = Tile,
    background = Tile, onBackground = Rail,
    surface = Color(0xFF1F2327), onSurface = Rail,
    surfaceVariant = Color(0xFF2F353B), onSurfaceVariant = Color(0xFFC9CDD2),
    // the selected tab's pill
    secondaryContainer = Color(0xFF2F353B), onSecondaryContainer = Amber,
    outline = Slate, error = Coral,
)

private val Light = lightColorScheme(
    // amber text on paper needs a darker shade to be readable
    primary = Color(0xFF8F6700), onPrimary = Color.White,
    background = Paper, onBackground = Tile,
    surface = Color.White, onSurface = Tile,
    surfaceVariant = Color(0xFFE9E6E0), onSurfaceVariant = Color(0xFF41474D),
    secondaryContainer = Color(0xFFFFF4D6), onSecondaryContainer = Color(0xFF8F6700),
    outline = Slate, error = Color(0xFFB3261E),
)

@Composable
fun RsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
