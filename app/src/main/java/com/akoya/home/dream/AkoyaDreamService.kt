package com.akoya.home.dream

import android.service.dreams.DreamService
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.akoya.home.data.ConfigStore
import com.akoya.home.ui.screensaver.ScreensaverContent
import com.akoya.home.ui.theme.AkoyaTheme

/**
 * System screensaver ("Daydream"). Enable with:
 * adb shell settings put secure screensaver_components com.akoya.home/.dream.AkoyaDreamService
 */
class AkoyaDreamService : DreamService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true

        val store = ConfigStore.get(this)
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AkoyaDreamService)
            setViewTreeSavedStateRegistryOwner(this@AkoyaDreamService)
            setContent {
                val config by store.config.collectAsState()
                val festival = com.akoya.home.data.Festivals.active(java.time.LocalDate.now(), config.festivalMode, config.seasonalArt)
                AkoyaTheme(config, festival) { ScreensaverContent(config, store) }
            }
        }
        window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDreamingStopped() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }
}
