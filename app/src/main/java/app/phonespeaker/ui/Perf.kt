@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.phonespeaker.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Performance mode: set from [app.phonespeaker.Config.performance], read by every animated piece of the UI. */
object Perf {
    var on by mutableStateOf(false)
}

/** Every Material animation snaps straight to its end value. */
object SnapMotion : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = snap()
}

/** Applies [block] to the modifier only when animations are on. */
inline fun Modifier.unlessPerf(block: Modifier.() -> Modifier): Modifier = if (Perf.on) this else block()

/** The busy indicator, which is an endless animation: nothing is drawn in performance mode. */
@Composable
fun Busy(modifier: Modifier) {
    if (!Perf.on) LoadingIndicator(modifier)
}
