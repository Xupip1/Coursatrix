package com.coursatrix.app.ui.timetable

import android.content.Context
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val MIN_ZOOM = 0.4f
private const val MAX_ZOOM = 3f

/** Device-local, since a comfortable size depends on the screen. [FIT_WIDTH] fits the whole week to the screen width. */
object TimetableZoomSettings {
    const val FIT_WIDTH = 0f
    private const val PREFS = "display"
    private const val KEY = "timetable_zoom"

    fun initial(context: Context): Float = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY, 1f)
        .let { if (it == FIT_WIDTH || it in MIN_ZOOM..MAX_ZOOM) it else 1f }

    fun save(context: Context, value: Float) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putFloat(KEY, value) }

    fun label(value: Float) = if (value == FIT_WIDTH) "适应屏幕宽度" else "${(value * 100).roundToInt()}%"
}

/**
 * Zoom and pan of the timetable. [pan] is in screen pixels (already scaled); the sizes below are unscaled pixels
 * reported by the layout. The frozen header row and time column scale with the grid but never scroll away.
 */
@Stable
class GridTransform {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    internal var initialized = false
    internal var viewport = Size.Zero
    internal var content = Size.Zero
    internal var sticky = Offset.Zero

    private fun maxPan(z: Float) = Offset(
        (content.width * z - (viewport.width - sticky.x * z)).coerceAtLeast(0f),
        (content.height * z - (viewport.height - sticky.y * z)).coerceAtLeast(0f),
    )

    fun set(newZoom: Float, newPan: Offset) {
        zoom = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val max = maxPan(zoom)
        pan = Offset(newPan.x.coerceIn(0f, max.x), newPan.y.coerceIn(0f, max.y))
    }

    /** Zooms around [focus] (viewport coordinates) so the point under the fingers stays put. */
    fun transform(focus: Offset, panChange: Offset, zoomChange: Float) {
        val old = zoom
        val new = (old * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val anchor = focus - sticky * old
        set(new, (pan + anchor) * (new / old) - anchor - panChange)
    }

    /** Returns how far the grid actually moved, so a fling can stop at the edges. */
    fun scrollBy(delta: Offset): Offset {
        val before = pan
        set(zoom, pan + delta)
        return pan - before
    }

    fun resolve(initial: Float): Float = if (initial != TimetableZoomSettings.FIT_WIDTH) initial.coerceIn(MIN_ZOOM, MAX_ZOOM)
        else (viewport.width / (sticky.x + content.width)).coerceIn(MIN_ZOOM, MAX_ZOOM)

    /** Animated zoom keeping the viewport centre fixed; [resetPan] returns to the top-left corner. */
    suspend fun animateTo(target: Float, resetPan: Boolean = false) {
        val startZoom = zoom
        val startPan = pan
        val anchor = Offset(viewport.width / 2, viewport.height / 2) - sticky * startZoom
        val point = (startPan + anchor) / startZoom
        animate(0f, 1f, animationSpec = tween(260)) { t, _ ->
            val z = startZoom + (target.coerceIn(MIN_ZOOM, MAX_ZOOM) - startZoom) * t
            set(z, if (resetPan) startPan * (1 - t) else point * z - anchor)
        }
    }

    companion object {
        val Saver = listSaver<GridTransform, Float>(
            save = { listOf(it.zoom, it.pan.x, it.pan.y, if (it.initialized) 1f else 0f) },
            restore = { GridTransform().apply { zoom = it[0]; pan = Offset(it[1], it[2]); initialized = it[3] == 1f } },
        )
    }
}

/**
 * Freely pannable and pinch-zoomable grid with a frozen [header] row and [side] column.
 * Content is laid out at full size and scaled with a graphics layer, so text stays sharp and taps hit correctly.
 */
@Composable
fun ZoomableGrid(
    state: GridTransform, initialZoom: Float, contentWidth: Dp, contentHeight: Dp, sideWidth: Dp, headerHeight: Dp,
    modifier: Modifier = Modifier,
    corner: @Composable () -> Unit, header: @Composable () -> Unit, side: @Composable () -> Unit, body: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    BoxWithConstraints(modifier.clipToBounds().pointerInput(state) { gridGestures(state, scope, decay) }) {
        with(LocalDensity.current) {
            state.viewport = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            state.content = Size(contentWidth.toPx(), contentHeight.toPx())
            state.sticky = Offset(sideWidth.toPx(), headerHeight.toPx())
        }
        if (!state.initialized) {
            state.initialized = true
            state.set(state.resolve(initialZoom), Offset.Zero)
        }
        // Re-clamp when the grid or screen size changes (weekend toggle, rotation, more periods).
        LaunchedEffect(state.viewport, state.content) { state.set(state.zoom, state.pan) }
        val z = state.zoom
        Box(Modifier.fillMaxSize().padding(start = sideWidth * z, top = headerHeight * z).clipToBounds()) {
            Layer(state, contentWidth, contentHeight, scrollX = true, scrollY = true, body)
        }
        Box(Modifier.fillMaxWidth().height(headerHeight * z).padding(start = sideWidth * z).clipToBounds()) {
            Layer(state, contentWidth, headerHeight, scrollX = true, scrollY = false, header)
        }
        Box(Modifier.width(sideWidth * z).fillMaxHeight().padding(top = headerHeight * z).clipToBounds()) {
            Layer(state, sideWidth, contentHeight, scrollX = false, scrollY = true, side)
        }
        Box(Modifier.size(sideWidth * z, headerHeight * z).clipToBounds()) {
            Layer(state, sideWidth, headerHeight, scrollX = false, scrollY = false, corner)
        }
    }
}

@Composable
private fun Layer(state: GridTransform, width: Dp, height: Dp, scrollX: Boolean, scrollY: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(width, height).graphicsLayer {
        scaleX = state.zoom
        scaleY = state.zoom
        transformOrigin = TransformOrigin(0f, 0f)
        translationX = if (scrollX) -state.pan.x else 0f
        translationY = if (scrollY) -state.pan.y else 0f
    }) { content() }
}

/** One finger pans in any direction (with fling), two fingers pinch-zoom. Taps still reach the course cards. */
private suspend fun PointerInputScope.gridGestures(state: GridTransform, scope: CoroutineScope, decay: DecayAnimationSpec<Float>) {
    var fling: Job? = null
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        fling?.cancel()
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var moving = false
        var totalZoom = 1f
        var totalPan = Offset.Zero
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty() || event.changes.any { it.isConsumed }) break
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            if (!moving) {
                totalZoom *= zoomChange
                totalPan += panChange
                val zoomMotion = abs(1 - totalZoom) * event.calculateCentroidSize(useCurrent = false)
                moving = zoomMotion > viewConfiguration.touchSlop || totalPan.getDistance() > viewConfiguration.touchSlop
            }
            if (moving) {
                state.transform(event.calculateCentroid(useCurrent = false), panChange, zoomChange)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
            if (pressed.size == 1) tracker.addPosition(pressed[0].uptimeMillis, pressed[0].position) else tracker.resetTracking()
        }
        if (!moving) return@awaitEachGesture
        val velocity = tracker.calculateVelocity()
        val speed = hypot(velocity.x, velocity.y)
        if (speed < 120f) return@awaitEachGesture
        val direction = Offset(velocity.x / speed, velocity.y / speed)
        fling = scope.launch {
            var last = 0f
            AnimationState(0f, speed).animateDecay(decay) {
                val moved = state.scrollBy(-direction * (value - last))
                last = value
                if (moved.getDistance() < 0.5f) cancelAnimation()
            }
        }
    }
}

/** Small floating pill: zoom out, current zoom (menu to choose the initial size), zoom in, reset. */
@Composable
fun ZoomControls(state: GridTransform, initialZoom: Float, onInitialZoom: (Float) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ scope.launch { state.animateTo(state.zoom / 1.25f) } }, Modifier.size(40.dp)) { Icon(Icons.Outlined.Remove, "缩小") }
            Box {
                TextButton({ menu = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("${(state.zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                }
                DropdownMenu(menu, { menu = false }) {
                    Text("初始大小：${TimetableZoomSettings.label(initialZoom)}", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val current = (state.zoom * 20).roundToInt() / 20f
                    DropdownMenuItem(text = { Text("将当前大小（${TimetableZoomSettings.label(current)}）设为初始大小") },
                        onClick = { menu = false; onInitialZoom(current) })
                    DropdownMenuItem(text = { Text("初始大小设为「适应屏幕宽度」") }, onClick = {
                        menu = false
                        onInitialZoom(TimetableZoomSettings.FIT_WIDTH)
                        scope.launch { state.animateTo(state.resolve(TimetableZoomSettings.FIT_WIDTH), resetPan = true) }
                    })
                    DropdownMenuItem(text = { Text("初始大小恢复为 100%") }, onClick = {
                        menu = false
                        onInitialZoom(1f)
                        scope.launch { state.animateTo(1f, resetPan = true) }
                    })
                }
            }
            IconButton({ scope.launch { state.animateTo(state.zoom * 1.25f) } }, Modifier.size(40.dp)) { Icon(Icons.Outlined.Add, "放大") }
            IconButton({ scope.launch { state.animateTo(state.resolve(initialZoom), resetPan = true) } }, Modifier.size(40.dp)) {
                Icon(Icons.Outlined.FitScreen, "恢复初始大小")
            }
        }
    }
}
