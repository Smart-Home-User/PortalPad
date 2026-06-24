package com.portalpad.app.presentation

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.portalpad.app.PortalPadApp
import com.portalpad.app.data.DockConfig
import com.portalpad.app.data.DockPosition
import com.portalpad.app.ui.dock.DockBar
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.PortalPadTheme

/**
 * Hosts the dock on the external display.
 *
 * Why this class is more code than you'd expect: a Presentation's decor view
 * doesn't have a LifecycleOwner / SavedStateRegistryOwner / ViewModelStoreOwner
 * by default, but ComposeView requires all three to be attached via the view-tree
 * owners. Without them, Compose throws "ViewTreeLifecycleOwner not found" the
 * first time the view composes — and the throw happens asynchronously on the
 * UI thread, so a plain try-catch in the caller can't catch it.
 *
 * The fix: implement those three owners on this class itself, then attach via
 * setViewTreeLifecycleOwner / setViewTreeSavedStateRegistryOwner /
 * setViewTreeViewModelStoreOwner on the ComposeView before setContent.
 */
class DockPresentation(
    serviceContext: Context,
    display: Display,
) : Presentation(serviceContext, display),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val _viewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = _viewModelStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@DockPresentation)
            setViewTreeSavedStateRegistryOwner(this@DockPresentation)
            setViewTreeViewModelStoreOwner(this@DockPresentation)
        }
        composeView.setContent { PortalPadTheme { DockContent() } }
        setContentView(composeView)
    }

    override fun show() {
        super.show()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun dismiss() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        _viewModelStore.clear()
        super.dismiss()
    }

    @Composable
    private fun DockContent() {
        val app = PortalPadApp.instance
        // The Presentation's decor view is fullscreen, so here the alignment
        // of this Box is what positions the dock. Drive it from the configured
        // edge; recomposes automatically when the user changes position.
        val cfg = app.prefs.dockConfig.collectAsState(initial = DockConfig()).value
        val align = when (cfg.position) {
            DockPosition.BOTTOM -> Alignment.BottomCenter
            DockPosition.TOP -> Alignment.TopCenter
            DockPosition.LEFT -> Alignment.CenterStart
            DockPosition.RIGHT -> Alignment.CenterEnd
        }
        Box(
            Modifier.fillMaxSize().background(AbBackground.copy(alpha = 0f)),
            contentAlignment = align,
        ) {
            DockBar(
                dockFlow = app.prefs.dockConfig,
                onLaunchEntry = { entry ->
                    val component = if (entry.isActivity) "${entry.packageName}/${entry.componentName}"
                    else app.packageManager.getLaunchIntentForPackage(entry.packageName)
                        ?.component?.flattenToString() ?: return@DockBar
                    if (app.access.isReady) {
                        app.access.startActivityOnDisplay(component, display.displayId)
                    }
                },
            )
        }
    }
}
