package com.constx.onvyra.ui.pages.novel

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private fun Context.readerBarsActivity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.readerBarsActivity()
    else -> null
}

/** Reader owns the bars only while visible; stable ignoring-visibility insets prevent repagination. */
@Composable internal fun ReaderSystemBars(visible: Boolean) {
    val window = LocalContext.current.readerBarsActivity()?.window ?: return
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    val currentVisible by rememberUpdatedState(visible)
    fun update() {
        val controller=WindowCompat.getInsetsController(window,view)
        controller.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if(currentVisible) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(window,view,owner) {
        val controller=WindowCompat.getInsetsController(window,view)
        val originalBehavior=controller.systemBarsBehavior
        val original=ViewCompat.getRootWindowInsets(view)
        val status=original?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val navigation=original?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME) view.post { update() } }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            controller.systemBarsBehavior=originalBehavior
            if(status) controller.show(WindowInsetsCompat.Type.statusBars()) else controller.hide(WindowInsetsCompat.Type.statusBars())
            if(navigation) controller.show(WindowInsetsCompat.Type.navigationBars()) else controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }
    // Dispatch after Compose has committed the controls and the window has
    // consumed the previous hide request. Issuing show during SideEffect can
    // otherwise be overtaken by the initial immersive-mode insets transaction.
    LaunchedEffect(window,view,visible) {
        view.post { update() }
    }
}
