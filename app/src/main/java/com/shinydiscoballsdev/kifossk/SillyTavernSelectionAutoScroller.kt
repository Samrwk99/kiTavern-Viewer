package com.shinydiscoballsdev.kifossk

import android.annotation.SuppressLint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * Experimental wrapper-side bridge for Chromium's native selection popups.
 *
 * The touch handles are separate PopupWindow views; the ordinary WebView
 * OnTouchListener cannot see their drag stream. This class hooks *only* those
 * views, and only changes their coordinates for a selection entirely in #chat.
 * Incompatible WebView implementations automatically fall back to native input.
 *
 * No SillyTavern files are changed and no DOM selection ranges are rewritten.
 * This depends on Chromium implementation details, not a supported WebView API.
 */
class SillyTavernSelectionAutoScroller(private val webView: WebView) {
    companion object {
        private const val TAG = "KiTavernHandleBridge"
        private const val BRIDGE = "STSelectionBridge"
        private const val EDGE_ZONE_DP = 52f
        private const val MAX_OVERSHOOT_DP = 96f
        private const val MIN_SPEED_DP_S = 45f
        private const val MAX_SPEED_DP_S = 750f
        // Only sustained native-handle edge auto-scrolling accelerates.
        // Every 1.5 seconds in one direction adds 40%, up to 4x the usual speed.
        private const val ACCELERATION_INTERVAL_MS = 1_500L
        private const val ACCELERATION_STEP = 0.40f
        private const val MAX_ACCELERATION_FACTOR = 4.0f
        private const val TOP_SAFE_DP = 36f
        private const val BOTTOM_SAFE_DP = 18f
        private const val SIDE_SAFE_DP = 14f
    }

    private val density get() = webView.resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val hooked = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val screenLocation = IntArray(2)
    private val popupLocation = IntArray(2)

    @Volatile private var pageReady = false
    @Volatile private var hasChatSelection = false
    @Volatile private var rectCss = RectF()
    @Volatile private var viewportWidthCss = 1f
    @Volatile private var viewportHeightCss = 1f
    @Volatile private var canScrollUp = false
    @Volatile private var canScrollDown = false

    private var dragging = false
    private var handleRef: WeakReference<View>? = null
    private var downTime = 0L
    private var fingerOffsetX = 0f
    private var fingerOffsetY = 0f
    private var rawX = 0f
    private var rawY = 0f
    private var fedX = 0f
    private var fedY = 0f
    private var running = false
    private var lastFrameNanos = 0L
    // This clock runs only while the handle is actively edge-scrolling #chat.
    // A change of direction, moving out of the edge zone, or lifting the
    // finger resets it, so ordinary handle movement is never accelerated.
    private var edgeScrollDirection = 0
    private var edgeScrollStartedMs = 0L
    private var jitter = false
    private var lastScanMs = 0L
    private val lastScanEnqueuedMs = AtomicLong(0L)
    private var closed = false

    init {
        // Register before page navigation. This JS interface contains only a
        // geometry report and never loads arbitrary URLs or runs JS strings.
        webView.addJavascriptInterface(SelectionBridge(), BRIDGE)
    }

    fun onNavigationStarted() {
        pageReady = false
        hasChatSelection = false
        cancelDrag()
    }

    fun onPageReady() {
        pageReady = true
    }

    fun cancelDrag() {
        stopFrames()
        if (dragging) webView.evaluateJavascript(
            "window.__stSetHandleDragging && window.__stSetHandleDragging(false);", null
        )
        dragging = false
        handleRef = null
    }

    fun close() {
        if (closed) return
        cancelDrag()
        closed = true
        pageReady = false
        // Do not leave our touch listeners attached to Chromium popup views.
        for (view in hooked.toList()) {
            if (view.isAttachedToWindow) view.setOnTouchListener(null)
        }
        hooked.clear()
        try { webView.removeJavascriptInterface(BRIDGE) } catch (_: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private val interceptor = View.OnTouchListener { handle, event ->
        if (closed || !pageReady) return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hasChatSelection || event.pointerCount != 1) {
                    return@OnTouchListener false
                }
                // The popup origin is the caret's nominal handle position in
                // current Chromium WebView. Maintain the actual finger-to-origin
                // offset rather than assuming the finger touches the tip.
                handle.getLocationOnScreen(popupLocation)
                val originX = popupLocation[0] + handle.width / 2f
                val originY = popupLocation[1].toFloat()
                fingerOffsetX = event.rawX - originX
                fingerOffsetY = event.rawY - originY
                rawX = event.rawX
                rawY = event.rawY
                fedX = rawX
                fedY = rawY
                downTime = event.downTime
                handleRef = WeakReference(handle)
                dragging = true
                jitter = false
                resetAcceleration()
                webView.evaluateJavascript(
                    "window.__stSetHandleDragging && window.__stSetHandleDragging(true);", null
                )
                Log.d(TAG, "Chat native handle DOWN intercepted")
                // Forward DOWN exactly once to Chromium. Returning true means
                // View.dispatchTouchEvent will not call onTouchEvent again.
                handle.onTouchEvent(event)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging || handleRef?.get() !== handle) return@OnTouchListener false
                if (event.pointerCount != 1) {
                    cancelDrag()
                    return@OnTouchListener false
                }
                rawX = event.rawX
                rawY = event.rawY
                val result = clampAndVelocity(rawX, rawY)
                fedX = result.x
                fedY = result.y
                if (abs(result.velocity) > 0.01f) {
                    // Start or restart the timer on the actual drag movement,
                    // including when the user switches scroll direction.
                    acceleratedVelocity(result.velocity, SystemClock.uptimeMillis())
                    if (!running) {
                        Log.d(TAG, "Native handle edge auto-scroll started")
                        startFrames()
                    }
                    // Send the latest *clamped* coordinate now too, rather than
                    // waiting for the first animation callback.
                    forwardSynthetic(handle, MotionEvent.ACTION_MOVE, fedX, fedY,
                        event.eventTime)
                } else {
                    stopFrames()
                    forwardSynthetic(handle, MotionEvent.ACTION_MOVE, fedX, fedY,
                        event.eventTime)
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging || handleRef?.get() !== handle) return@OnTouchListener false
                stopFrames()
                forwardSynthetic(handle, event.actionMasked, fedX, fedY, event.eventTime)
                webView.evaluateJavascript(
                    "window.__stSetHandleDragging && window.__stSetHandleDragging(false);", null
                )
                dragging = false
                handleRef = null
                Log.d(TAG, "Native handle UP/CANCEL")
                true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-finger gestures are not supported by this experimental
                // bridge. Cancel the intercepted handle sequence safely.
                if (dragging && handleRef?.get() === handle) {
                    stopFrames()
                    forwardSynthetic(handle, MotionEvent.ACTION_CANCEL, fedX, fedY,
                        event.eventTime)
                    cancelDrag()
                    return@OnTouchListener true
                }
                false
            }
            else -> false
        }
    }

    private data class Feed(val x: Float, val y: Float, val velocity: Float)

    private fun clampAndVelocity(fingerX: Float, fingerY: Float): Feed {
        webView.getLocationOnScreen(screenLocation)
        // Convert CSS viewport units to actual Android screen pixels.
        val scaleX = webView.width.toFloat() / viewportWidthCss.coerceAtLeast(1f)
        val scaleY = webView.height.toFloat() / viewportHeightCss.coerceAtLeast(1f)
        val bounds = rectCss
        val left = screenLocation[0] + bounds.left * scaleX
        val right = screenLocation[0] + bounds.right * scaleX
        val top = screenLocation[1] + bounds.top * scaleY
        val bottom = screenLocation[1] + bounds.bottom * scaleY
        // Fail open on stale/invalid geometry instead of shifting a native
        // selection handle into a completely unrelated place.
        if (right - left < dp(60f) || bottom - top < dp(80f)) {
            return Feed(fingerX, fingerY, 0f)
        }
        val tipX = fingerX - fingerOffsetX
        val tipY = fingerY - fingerOffsetY
        val zone = dp(EDGE_ZONE_DP)
        val overshootMax = dp(MAX_OVERSHOOT_DP)
        var v = 0f
        if (tipY < top + zone && canScrollUp) {
            val t = ((top + zone - tipY) / overshootMax).coerceIn(0f, 1f)
            v = -(MIN_SPEED_DP_S + (MAX_SPEED_DP_S - MIN_SPEED_DP_S) * t * t)
        } else if (tipY > bottom - zone && canScrollDown) {
            val t = ((tipY - (bottom - zone)) / overshootMax).coerceIn(0f, 1f)
            v = MIN_SPEED_DP_S + (MAX_SPEED_DP_S - MIN_SPEED_DP_S) * t * t
        }
        val topInset = dp(if (canScrollUp) TOP_SAFE_DP else 14f)
        val bottomInset = dp(if (canScrollDown) BOTTOM_SAFE_DP else 4f)
        val clampedTipX = tipX.coerceIn(left + dp(SIDE_SAFE_DP),
            max(left + dp(SIDE_SAFE_DP) + 1f, right - dp(SIDE_SAFE_DP)))
        val clampedTipY = tipY.coerceIn(top + topInset,
            max(top + topInset + 1f, bottom - bottomInset))
        return Feed(clampedTipX + fingerOffsetX,
            clampedTipY + fingerOffsetY, v)
    }

    private fun resetAcceleration() {
        edgeScrollDirection = 0
        edgeScrollStartedMs = 0L
    }

    private fun acceleratedVelocity(baseVelocity: Float, nowMs: Long): Float {
        val direction = when {
            baseVelocity > 0.01f -> 1
            baseVelocity < -0.01f -> -1
            else -> 0
        }
        if (direction == 0) {
            resetAcceleration()
            return 0f
        }
        if (direction != edgeScrollDirection) {
            edgeScrollDirection = direction
            edgeScrollStartedMs = nowMs
        }
        val steps = ((nowMs - edgeScrollStartedMs).coerceAtLeast(0L) /
            ACCELERATION_INTERVAL_MS).toFloat()
        val factor = (1f + steps * ACCELERATION_STEP)
            .coerceAtMost(MAX_ACCELERATION_FACTOR)
        return baseVelocity * factor
    }

    private fun forwardSynthetic(handle: View, action: Int, x: Float, y: Float,
                                 whenMs: Long) {
        // Chromium's PopupTouchHandleDrawable converts RAW/local coordinates
        // into coordinates local to its WebView parent. Using an event whose
        // raw and local coordinates are both the desired screen position makes
        // the forwarded point land at (screen - WebView position).
        val synthetic = MotionEvent.obtain(downTime, whenMs, action, x, y, 0)
        synthetic.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            handle.onTouchEvent(synthetic)
        } finally {
            synthetic.recycle()
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running || !dragging || closed) return
            val handle = handleRef?.get()
            if (handle == null || !handle.isAttachedToWindow || !pageReady) {
                cancelDrag()
                return
            }
            if (lastFrameNanos != 0L && frameTimeNanos - lastFrameNanos < 15_000_000L) {
                Choreographer.getInstance().postFrameCallback(this)
                return
            }
            val deltaSeconds = if (lastFrameNanos == 0L) 0.016f else
                ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNanos = frameTimeNanos
            val result = clampAndVelocity(rawX, rawY)
            fedX = result.x
            fedY = result.y
            if (abs(result.velocity) < 0.01f) {
                stopFrames()
                return
            }
            // Increase speed only during uninterrupted edge auto-scrolling
            // in the same direction; keep the existing native handle pump.
            val accelerated = acceleratedVelocity(
                result.velocity, SystemClock.uptimeMillis()
            )
            // Let the JS side scroll #chat without rewriting Range/Selection.
            val delta = accelerated * deltaSeconds
            if (delta.isFinite()) {
                webView.evaluateJavascript(
                    "window.__stAutoScrollStep && window.__stAutoScrollStep($delta);", null
                )
            }
            // The supplied diagnosis proposes a small alternating offset to
            // make Chromium reconsider the native endpoint as chat moves.
            jitter = !jitter
            val deltaJitter = if (jitter) -sign(result.velocity) * dp(1.1f) else 0f
            forwardSynthetic(handle, MotionEvent.ACTION_MOVE,
                fedX, fedY + deltaJitter, SystemClock.uptimeMillis())
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startFrames() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrames() {
        resetAcceleration()
        if (!running) return
        running = false
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun scanForHandles() {
        if (closed || !pageReady || !hasChatSelection) return
        val now = SystemClock.uptimeMillis()
        if (now - lastScanMs < 180) return
        lastScanMs = now
        val roots: List<View> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { WindowInspector.getGlobalWindowViews() } catch (_: Throwable) { emptyList() }
        } else {
            // Best-effort legacy fallback only. Android's hidden API
            // restrictions can prevent access on some devices.
            try {
                val klass = Class.forName("android.view.WindowManagerGlobal")
                val instance = klass.getMethod("getInstance").invoke(null)
                val field = klass.getDeclaredField("mViews").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                (field.get(instance) as? List<View>)?.toList() ?: emptyList()
            } catch (_: Throwable) { emptyList() }
        }
        for (root in roots) findAndHook(root)
    }

    private fun findAndHook(view: View) {
        if (view.javaClass.name.endsWith(".PopupTouchHandleDrawable")) {
            if (hooked.add(view)) {
                view.setOnTouchListener(interceptor)
                Log.d(TAG, "Hooked ${view.javaClass.name}")
            }
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findAndHook(view.getChildAt(i))
        }
    }

    private inner class SelectionBridge {
        @JavascriptInterface
        fun onSelectionStateChanged(
            inside: Boolean, left: Float, top: Float, right: Float, bottom: Float,
            cssWidth: Float, cssHeight: Float, scrollUp: Boolean, scrollDown: Boolean
        ) {
            if (closed || !pageReady) return
            if (!left.isFinite() || !top.isFinite() || !right.isFinite() ||
                !bottom.isFinite() || !cssWidth.isFinite() || !cssHeight.isFinite() ||
                cssWidth <= 0 || cssHeight <= 0 || right <= left || bottom <= top) return
            hasChatSelection = inside
            rectCss = RectF(left, top, right, bottom)
            viewportWidthCss = cssWidth
            viewportHeightCss = cssHeight
            canScrollUp = scrollUp
            canScrollDown = scrollDown
            if (inside) {
                // selectionchange and chat scrolling can each fire dozens of
                // times a second. Never enqueue a new 3-scan batch per event.
                val now = SystemClock.uptimeMillis()
                val previous = lastScanEnqueuedMs.get()
                if (now - previous >= 320L &&
                    lastScanEnqueuedMs.compareAndSet(previous, now)) {
                    webView.post {
                        scanForHandles()
                        webView.postDelayed({ scanForHandles() }, 85)
                        webView.postDelayed({ scanForHandles() }, 240)
                    }
                }
            }
        }
    }
}
