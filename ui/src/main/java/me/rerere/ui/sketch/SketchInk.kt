package me.rerere.ui.sketch

import android.annotation.SuppressLint
import androidx.ink.nativeloader.InkInternalOnlyApi
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer

// Sketch brushes have no textures. Render their Ink-generated outlines as paths so that opening
// the normal Compose canvas does not require a new mesh/shader pipeline before ink is visible.
// Ink 1.1.0-alpha09 exposes this choice via a restricted API; isolate it here and recheck on upgrade.
@SuppressLint("RestrictedApi")
@OptIn(InkInternalOnlyApi::class)
internal fun createSketchStrokeRenderer(): CanvasStrokeRenderer =
    CanvasStrokeRenderer.create(forcePathRendering = true)

