package dev.omalauncher.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import dev.omalauncher.wayland.AXIS_SOURCE_FINGER
import dev.omalauncher.wayland.AXIS_SOURCE_WHEEL
import dev.omalauncher.wayland.BTN_LEFT
import dev.omalauncher.wayland.BTN_MIDDLE
import dev.omalauncher.wayland.BTN_RIGHT
import dev.omalauncher.wayland.SurfaceHit
import dev.omalauncher.wayland.XdgToplevel
import kotlin.math.roundToInt

private const val WHEEL_STEP = 15f

/**
 * Routes pointer input over a Linux window's view to its surfaces. Mouse input
 * is forwarded as-is. Touch is mapped for a phone: tap = left click, long
 * press = right click, drag = scroll; clients with wl_touch get real touch.
 * With [touchpad] on, touch drives a cursor instead (see [TouchpadState]).
 */
fun Modifier.linuxWindowInput(
    wm: WindowManager,
    window: XdgToplevel,
    touchpad: TouchpadState? = null,
) = pointerInput(wm, window, touchpad) {
    handlePointer(wm, window, touchpad, viewConfiguration.touchSlop, viewConfiguration.longPressTimeoutMillis)
}

private fun XdgToplevel.hitTest(
    x: Float,
    y: Float,
) = renderer?.hitTest(x, y)

private suspend fun PointerInputScope.handlePointer(
    wm: WindowManager,
    window: XdgToplevel,
    touchpad: TouchpadState?,
    touchSlop: Float,
    longPressMs: Long,
) = awaitPointerEventScope {
    var mouseButtons = 0
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull() ?: continue
        if (change.type == PointerType.Mouse) {
            mouseButtons = handleMouse(wm, window, event, mouseButtons)
            change.consume()
            continue
        }
        if (event.type != PointerEventType.Press || change.isConsumed) continue
        if (touchpad?.on == true) {
            touchpad.bounds = size
            touchpadGesture(wm, window, touchpad, event, touchSlop)
            continue
        }

        val start = change.position
        val hit = window.hitTest(start.x, start.y)
        change.consume()
        if (!wm.onPress(window, hit) || hit == null) continue

        // Clients with wl_touch (foot, GTK) get real touch, every finger of it: foot turns
        // hold-and-drag into selection, and apps see pinches and two-finger gestures.
        if (wm.wantsTouch(hit.surface)) {
            forwardTouches(wm, window, change, hit)
            continue
        }

        var scrolling = false
        var longPressed = false
        var last = start
        while (true) {
            val next =
                if (!scrolling && !longPressed) {
                    withTimeoutOrNull(longPressMs) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
            if (next == null) {
                longPressed = true
                wm.click(hit, BTN_RIGHT)
                continue
            }
            val c = next.changes.firstOrNull { it.id == change.id } ?: break
            if (c.isConsumed) {
                if (scrolling) wm.scrollStop()
                break
            }
            c.consume()
            if (!c.pressed) {
                if (scrolling) wm.scrollStop() else if (!longPressed) wm.click(hit, BTN_LEFT)
                break
            }
            if (!scrolling && !longPressed && (c.position - start).getDistance() > touchSlop) {
                scrolling = true
                wm.pointerMove(hit)
            }
            if (scrolling) {
                val d = c.position - last
                wm.scroll(-d.x / hit.scale, -d.y / hit.scale, AXIS_SOURCE_FINGER)
            }
            last = c.position
        }
    }
}

/**
 * Sends each finger to the surface its own down landed on, until the last one
 * lifts. Fingers that land while others are down get their own wl_touch id.
 */
private suspend fun AwaitPointerEventScope.forwardTouches(
    wm: WindowManager,
    window: XdgToplevel,
    first: PointerInputChange,
    firstHit: SurfaceHit,
) {
    class Touch(
        val start: Offset,
        val hit: SurfaceHit,
    )
    val touches = HashMap<PointerId, Touch>()
    touches[first.id] = Touch(first.position, firstHit)
    wm.touchDown(firstHit, first.id.value.toInt())
    while (touches.isNotEmpty()) {
        val event = awaitPointerEvent()
        for (c in event.changes) {
            val id = c.id.value.toInt()
            val touch = touches[c.id]
            if (touch == null) {
                if (!c.changedToDown() || c.isConsumed) continue
                val hit = window.hitTest(c.position.x, c.position.y) ?: continue
                c.consume()
                touches[c.id] = Touch(c.position, hit)
                wm.touchDown(hit, id)
                continue
            }
            // Lifted, or taken by the home screen (a long press lifts a tile): that finger's touch ends here.
            if (c.isConsumed || !c.pressed) {
                touches.remove(c.id)
                wm.touchUp(id)
                continue
            }
            c.consume()
            val d = (c.position - touch.start) / touch.hit.scale
            wm.touchMotion(id, touch.hit.x + d.x, touch.hit.y + d.y)
        }
    }
}

private fun handleMouse(
    wm: WindowManager,
    window: XdgToplevel,
    event: PointerEvent,
    previousButtons: Int,
): Int {
    val change = event.changes.first()
    when (event.type) {
        PointerEventType.Exit -> {
            wm.pointerMove(null)
            return previousButtons
        }
        PointerEventType.Scroll -> {
            val d = change.scrollDelta
            wm.scroll(d.x * WHEEL_STEP, d.y * WHEEL_STEP, AXIS_SOURCE_WHEEL, d.y.roundToInt())
            return previousButtons
        }
        else -> Unit
    }
    val hit: SurfaceHit? = window.hitTest(change.position.x, change.position.y)
    val buttons =
        (if (event.buttons.isPrimaryPressed) 1 else 0) or
            (if (event.buttons.isSecondaryPressed) 2 else 0) or
            (if (event.buttons.isTertiaryPressed) 4 else 0)
    val pressed = buttons and previousButtons.inv()
    if (pressed != 0 && !wm.onPress(window, hit)) return buttons
    // Keep the grab on the pressed surface while dragging.
    if (previousButtons == 0 || wm.server.input.pointerFocus == hit?.surface) wm.pointerMove(hit)
    for ((bit, code) in BUTTONS) {
        if (pressed and bit != 0) wm.pointerButton(code, true)
        if (previousButtons and bit != 0 && buttons and bit == 0) wm.pointerButton(code, false)
    }
    return buttons
}

private val BUTTONS = listOf(1 to BTN_LEFT, 2 to BTN_RIGHT, 4 to BTN_MIDDLE)
