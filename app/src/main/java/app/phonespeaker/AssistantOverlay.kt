package app.phonespeaker

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import app.phonespeaker.ui.OverlayContent

/**
 * The floating assistant as a window over every other app (needs "display over other apps").
 * Touches pass through it, so whatever is underneath stays usable.
 */
class AssistantOverlay(private val ctx: Context) {
    private val windowManager = ctx.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: Owner? = null

    val showing: Boolean get() = view != null
    private var params: WindowManager.LayoutParams? = null
    private var touchable = false

    /** A question with buttons needs touches; the plain assistant pill lets them through. */
    fun setTouchable(value: Boolean) {
        val v = view ?: return
        val p = params ?: return
        if (value == touchable) return
        touchable = value
        p.flags = if (value) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    fun show() {
        if (view != null) return
        val density = ctx.resources.displayMetrics.density
        val width = minOf((ctx.resources.displayMetrics.widthPixels * 0.94f).toInt(), (660 * density).toInt())
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (28 * density).toInt()
        }
        val lifecycleOwner = Owner().also { owner = it }
        val composeView = ComposeView(ctx).apply {
            // a window added by a service has no activity: give Compose the owners it looks for
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent { OverlayContent() }
        }
        try {
            windowManager.addView(composeView, params)
            this.params = params
            touchable = false
            lifecycleOwner.resume()
            view = composeView
        } catch (e: Exception) {
            // no permission or the window could not be added: stay quiet
            owner?.destroy()
            owner = null
        }
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        owner?.destroy()
        owner = null
    }

    private class Owner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val viewModelStore = ViewModelStore()
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        init {
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }

        fun resume() { registry.currentState = Lifecycle.State.RESUMED }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
