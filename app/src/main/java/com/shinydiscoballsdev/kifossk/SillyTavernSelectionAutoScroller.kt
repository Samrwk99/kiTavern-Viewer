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
import android.view.ViewConfiguration
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
 * views, and only changes their coordinates for text selected inside one
 * scroll region (#chat, a settings/drawer panel, or a text field), confirmed
 * by the page at the moment the handle is grabbed. Anything else stays fully
 * native.
 * Incompatible WebView implementations automatically fall back to native input.
 *
 * SillyTavern files are unchanged. A guarded DOM-anchor recovery runs only
 * if Chromium corrupts the fixed endpoint when a handle is re-grabbed.
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
        // Settings panels and text fields are short and dense: a gentle,
        // constant speed range with no time-based acceleration.
        private const val PANEL_MIN_SPEED_DP_S = 30f
        private const val PANEL_MAX_SPEED_DP_S = 1_000f
        // Only sustained native-handle edge auto-scrolling accelerates.
        // Every 1.5 seconds in one direction adds 40%, up to 4x the usual speed.
        private const val ACCELERATION_INTERVAL_MS = 1_500L
        private const val ACCELERATION_STEP = 0.40f
        private const val MAX_ACCELERATION_FACTOR = 4.0f
        // Chromium's drag-selection autoscroll, used for text fields and popups:
        // 20 CSS px edge belt, one step per frame (60 a second), each step as
        // deep as the point is into the belt. The cap keeps a step within
        // what the page-side scroll step accepts (100 px).
        private const val CHROMIUM_BELT_CSS = 20f
        private const val CHROMIUM_FRAMES_PER_S = 60f
        private const val CHROMIUM_MAX_STEP_CSS = 90f
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
    // Cached hints only. A grab is intercepted only after JavaScript confirms,
    // at that moment, that the live selection is inside a scroll region.
    @Volatile private var hasRegionSelection = false
    @Volatile private var regionIsChat = true
    // Speed profile of the drag in progress: #chat (fast, accelerating) or a
    // settings panel / text field (gentle, constant).
    private var dragIsChat = true
    // Text fields and popup windows: Chromium's own drag-selection scroll
    // speed (see clampAndVelocity) instead of this class's speed curves.
    private var dragChromiumSpeed = false
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
    // This clock runs only while the handle is actively edge-scrolling #chat
    // (settings panels never accelerate).
    // A change of direction, moving out of the edge zone, or lifting the
    // finger resets it, so ordinary handle movement is never accelerated.
    private var edgeScrollDirection = 0
    private var edgeScrollStartedMs = 0L
    private var jitter = false
    // evaluateJavascript scrolls asynchronously. Do not finish a drag until
    // every scroll requested by that drag has completed and Chromium has seen
    // the endpoint at the final scrolled position. Otherwise a second grab
    // can re-anchor against a stale/offscreen bound and swap the handles.
    private var gestureGeneration = 0L
    private var pendingScrollSteps = 0
    private var scrolledDuringGesture = false
    private data class PendingRelease(val action: Int, val eventTime: Long)
    private var pendingRelease: PendingRelease? = null
    private var lastScanMs = 0L
    private val lastScanEnqueuedMs = AtomicLong(0L)
    // A handle drag that is not assisted (text fields, other pages, text
    // outside any scroll region). Chromium handles it entirely; we only
    // observe when it ends.
    private var passthroughRef: WeakReference<View>? = null
    private var closed = false
    // Capture the genuine DOM fixed endpoint before Chromium receives the
    // first MOVE of a newly grabbed popup handle. WebView JS evaluation is
    // asynchronous, so hold any intervening touch events until that snapshot
    // is acknowledged. This is only at gesture start, not every frame.
    private var waitingForAnchorSnapshot = false
    private var bufferedDown: MotionEvent? = null
    private var nativeDownDelivered = false
    private var bufferedMove: MotionEvent? = null
    private var bufferedRelease: MotionEvent? = null

    init {
        // Register before page navigation. This JS interface contains only a
        // geometry report and never loads arbitrary URLs or runs JS strings.
        webView.addJavascriptInterface(SelectionBridge(), BRIDGE)
    }

    fun onNavigationStarted() {
        pageReady = false
        hasRegionSelection = false
        cancelDrag()
    }

    fun onPageReady() {
        pageReady = true
    }

    /**
     * Chromium shows the selection handles when the long-press finger lifts,
     * which can be well after the selectionchange that started the last scan.
     * Re-scan then so the very first handle drag is already hooked.
     */
    fun onWebViewTouchEnded() {
        if (!closed && pageReady) requestScanBurst()
    }

    fun cancelDrag() {
        lpCancel()
        endPassthrough()
        stopFrames()
        val wasDeliveredToChromium = nativeDownDelivered
        waitingForAnchorSnapshot = false
        bufferedDown?.recycle()
        bufferedDown = null
        nativeDownDelivered = false
        bufferedMove?.recycle()
        bufferedMove = null
        bufferedRelease?.recycle()
        bufferedRelease = null
        // Invalidate callbacks from the previous navigation/drag. If Chromium
        // still owns a handle gesture, finish it with CANCEL before discarding
        // our reference; otherwise the next DOWN can inherit active state.
        gestureGeneration++
        pendingScrollSteps = 0
        pendingRelease = null
        if (dragging) {
            handleRef?.get()?.let { handle ->
                if (wasDeliveredToChromium && handle.isAttachedToWindow) {
                    forwardSynthetic(handle, MotionEvent.ACTION_CANCEL,
                        fedX, fedY, SystemClock.uptimeMillis())
                }
            }
            webView.evaluateJavascript(
                "window.__stSetHandleDragging && window.__stSetHandleDragging(false);", null
            )
        }
        dragging = false
        handleRef = null
        scrolledDuringGesture = false
    }

    // Called only after the current drag's queued JS scroll steps have finished.
    // A last MOVE without the oscillating jitter reconciles the selected DOM
    // endpoint with the final chat scroll position before Chromium's UP.
    private fun finishNativeRelease(handle: View, release: PendingRelease) {
        if (!dragging || handleRef?.get() !== handle) return
        if (!nativeDownDelivered) return
        if (release.action == MotionEvent.ACTION_UP && scrolledDuringGesture) {
            val finalFeed = clampAndVelocity(rawX, rawY)
            fedX = finalFeed.x
            fedY = finalFeed.y
            forwardSynthetic(handle, MotionEvent.ACTION_MOVE,
                fedX, fedY, release.eventTime)
        }
        forwardSynthetic(handle, release.action, fedX, fedY, release.eventTime)
        webView.evaluateJavascript(
            "window.__stSetHandleDragging && window.__stSetHandleDragging(false);", null
        )
        dragging = false
        nativeDownDelivered = false
        handleRef = null
        pendingRelease = null
        pendingScrollSteps = 0
        gestureGeneration++ // Ignore any late JavaScript completion callbacks.
        scrolledDuringGesture = false
        Log.d(TAG, "Native handle UP/CANCEL (scroll and endpoint synchronized)")
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
        // An unassisted drag is Chromium's alone: never buffer, clamp or rewrite
        // it. Only note when it ends so the page can tidy its endpoints.
        val passthrough = passthroughRef?.get()
        if (passthrough != null && event.actionMasked != MotionEvent.ACTION_DOWN) {
            if (passthrough === handle && (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL)) {
                endPassthrough()
            }
            return@OnTouchListener false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A very fast re-grab can occur while a previous JS callback
                // is still returning. Finish that old Chromium gesture first.
                if (dragging) {
                    val oldHandle = handleRef?.get()
                    val oldRelease = pendingRelease
                    if (oldHandle != null && oldRelease != null) {
                        finishNativeRelease(oldHandle, oldRelease)
                    } else {
                        cancelDrag()
                    }
                }
                endPassthrough()
                if (event.pointerCount != 1) return@OnTouchListener false
                // Always ask the page about the LIVE selection; a cached report
                // can be stale and would hand the drag to Chromium's own
                // (fast, unclamped) handling. Unassisted drags are handed back
                // unmodified once the page answers, a frame or so later.
                dragIsChat = regionIsChat
                dragChromiumSpeed = false
                gestureGeneration++
                waitingForAnchorSnapshot = false
                bufferedDown?.recycle()
                bufferedDown = null
                nativeDownDelivered = false
                bufferedMove?.recycle()
                bufferedMove = null
                bufferedRelease?.recycle()
                bufferedRelease = null
                pendingScrollSteps = 0
                pendingRelease = null
                scrolledDuringGesture = false
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
                // The drag state (and, for #chat only, hit-test shielding of
                // the composer, top bar and settings holder) is set by begin()
                // in JavaScript once it has confirmed the selection's region.
                Log.d(TAG, "Native handle DOWN held for region verification")
                // Chromium's TouchHandle::BeginDrag can re-hit-test the
                // *opposite* endpoint right on ACTION_DOWN. Do not forward DOWN
                // until the JS snapshot captures its original offscreen node.
                // MotionEvent.obtain(event) retains the genuine popup-local,
                // raw-screen, pointer and timing metadata (no synthetic DOWN).
                waitingForAnchorSnapshot = true
                bufferedDown = MotionEvent.obtain(event)
                val generation = gestureGeneration
                val handleCssX = screenToCssX(originX)
                val handleCssY = screenToCssY(originY)
                try {
                    webView.evaluateJavascript(
                        "window.__stSelectionAnchorLock && " +
                        "window.__stSelectionAnchorLock.begin($handleCssX,$handleCssY);"
                    ) { response ->
                        if (closed || generation != gestureGeneration ||
                            !dragging || handleRef?.get() !== handle) return@evaluateJavascript
                        Log.d(TAG, "Re-grab anchor snapshot: $response")
                        val role = parseRole(response)
                        if (role == null) {
                            // The cached hint was stale: this selection is in
                            // a text field or elsewhere. Hand the whole
                            // gesture, unmodified, back to Chromium.
                            switchToPassthrough(handle)
                            return@evaluateJavascript
                        }
                        dragIsChat = role.isChat
                        dragChromiumSpeed = role.chromiumSpeed
                        // The timeout below already delivered DOWN; keep going.
                        if (!waitingForAnchorSnapshot) return@evaluateJavascript
                        waitingForAnchorSnapshot = false
                        deliverBufferedDown(handle)
                        if (!nativeDownDelivered) {
                            cancelDrag()
                            return@evaluateJavascript
                        }
                        replayBuffered(handle)
                    }
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Anchor snapshot unavailable; using native handling", error)
                    switchToPassthrough(handle)
                    return@OnTouchListener true
                }
                // Avoid losing the DOWN forever if renderer JavaScript stalls
                // (e.g. while a reply is streaming). The cached hint said this
                // selection is in a region, so keep its clamp; a late reply
                // from begin() that says otherwise still switches to native.
                webView.postDelayed({
                    if (dragging && generation == gestureGeneration &&
                        waitingForAnchorSnapshot && handleRef?.get() === handle) {
                        if (!hasRegionSelection) {
                            Log.w(TAG, "Region check timeout; leaving drag native")
                            switchToPassthrough(handle)
                            return@postDelayed
                        }
                        Log.w(TAG, "Anchor snapshot timeout; forwarding real DOWN")
                        waitingForAnchorSnapshot = false
                        deliverBufferedDown(handle)
                        if (!nativeDownDelivered) {
                            cancelDrag()
                            return@postDelayed
                        }
                        replayBuffered(handle)
                    }
                }, 180L)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging || handleRef?.get() !== handle)
                    return@OnTouchListener false
                if (waitingForAnchorSnapshot) {
                    bufferedMove?.recycle()
                    bufferedMove = MotionEvent.obtain(event)
                    return@OnTouchListener true
                }
                processHandleMove(handle, event)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging || handleRef?.get() !== handle)
                    return@OnTouchListener false
                if (waitingForAnchorSnapshot) {
                    bufferedRelease?.recycle()
                    bufferedRelease = MotionEvent.obtain(event)
                    return@OnTouchListener true
                }
                processHandleRelease(handle, event)
                true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-finger gestures are not supported by this experimental
                // bridge. Cancel the intercepted handle sequence safely.
                if (dragging && handleRef?.get() === handle) {
                    cancelDrag()
                    return@OnTouchListener true
                }
                false
            }
            else -> false
        }
    }

    private class Role(val isChat: Boolean, val chromiumSpeed: Boolean = false)

    /**
     * "upper"/"lower" = #chat; "upper:panel"/"lower:panel" = a settings panel;
     * "lower:field" = a selection inside a text field.
     */
    private fun parseRole(response: String?): Role? {
        val parts = (response?.trim()?.removeSurrounding("\"") ?: return null).split(':')
        if (parts[0] != "upper" && parts[0] != "lower") return null
        return when (parts.getOrNull(1)) {
            null -> Role(isChat = true)
            "panel", "dropdown" -> Role(isChat = false)
            "field", "popup" -> Role(isChat = false, chromiumSpeed = true)
            else -> null
        }
    }

    private fun replayBuffered(handle: View) {
        val move = bufferedMove
        bufferedMove = null
        if (move != null) {
            try { processHandleMove(handle, move) }
            finally { move.recycle() }
        }
        val release = bufferedRelease
        bufferedRelease = null
        if (release != null) {
            try { processHandleRelease(handle, release) }
            finally { release.recycle() }
        }
    }

    private fun notifyNativeHandleActive(active: Boolean) {
        try {
            webView.evaluateJavascript(
                "window.__stNativeHandleActive && window.__stNativeHandleActive($active);", null
            )
        } catch (_: RuntimeException) { }
    }

    private fun beginPassthrough(handle: View) {
        passthroughRef = WeakReference(handle)
        notifyNativeHandleActive(true)
    }

    private fun endPassthrough() {
        if (passthroughRef == null) return
        passthroughRef = null
        notifyNativeHandleActive(false)
    }

    /**
     * Abandons region interception for this gesture and gives Chromium the
     * genuine events: the held DOWN plus anything buffered, then every later
     * event directly (the listener returns false while passthrough is set).
     */
    private fun switchToPassthrough(handle: View) {
        val down = bufferedDown
        val move = bufferedMove
        val release = bufferedRelease
        bufferedDown = null
        bufferedMove = null
        bufferedRelease = null
        val downWasDelivered = nativeDownDelivered
        val deferredRelease = pendingRelease
        stopFrames()
        waitingForAnchorSnapshot = false
        dragging = false
        handleRef = null
        nativeDownDelivered = false
        pendingScrollSteps = 0
        pendingRelease = null
        scrolledDuringGesture = false
        gestureGeneration++
        webView.evaluateJavascript(
            "window.__stSetHandleDragging && window.__stSetHandleDragging(false);", null
        )
        beginPassthrough(handle)
        var ended = false
        try {
            if (handle.isAttachedToWindow) {
                if (down != null) handle.onTouchEvent(down)
                if (move != null) handle.onTouchEvent(move)
                if (release != null) {
                    handle.onTouchEvent(release)
                    ended = true
                } else if (deferredRelease != null && downWasDelivered) {
                    forwardSynthetic(handle, deferredRelease.action, fedX, fedY,
                        deferredRelease.eventTime)
                    ended = true
                }
            } else {
                ended = true
            }
        } finally {
            down?.recycle()
            move?.recycle()
            release?.recycle()
        }
        if (ended) endPassthrough()
    }

    private fun deliverBufferedDown(handle: View) {
        val realDown = bufferedDown ?: return
        bufferedDown = null
        try {
            if (handle.isAttachedToWindow) {
                nativeDownDelivered = true
                handle.onTouchEvent(realDown)
            }
        } finally {
            realDown.recycle()
        }
    }

    private fun screenToCssX(screenX: Float): Float {
        webView.getLocationOnScreen(screenLocation)
        return (screenX - screenLocation[0]) * viewportWidthCss /
            webView.width.coerceAtLeast(1)
    }

    private fun screenToCssY(screenY: Float): Float {
        webView.getLocationOnScreen(screenLocation)
        return (screenY - screenLocation[1]) * viewportHeightCss /
            webView.height.coerceAtLeast(1)
    }

    // The clamped handle tip, not the physical finger, is where Chromium
    // positions the caret. Supply a point slightly above that tip to the
    // guarded DOM-anchor recovery helper.
    private fun reportMovingPoint(screenX: Float, screenY: Float) {
        val cssX = screenToCssX(screenX - fingerOffsetX)
        val cssY = screenToCssY(screenY - fingerOffsetY - dp(12f))
        webView.evaluateJavascript(
            "window.__stSelectionAnchorLock && " +
            "window.__stSelectionAnchorLock.point($cssX,$cssY);", null
        )
    }

    private fun processHandleMove(handle: View, event: MotionEvent) {
        if (pendingRelease != null && handleRef?.get() === handle) return
        if (!dragging || handleRef?.get() !== handle) return
        if (event.pointerCount != 1) {
            cancelDrag()
            return
        }
        rawX = event.rawX
        rawY = event.rawY
        val result = clampAndVelocity(rawX, rawY)
        fedX = result.x
        fedY = result.y
        reportMovingPoint(fedX, fedY)
        if (abs(result.velocity) > 0.01f) {
            acceleratedVelocity(result.velocity, SystemClock.uptimeMillis())
            if (!running) {
                Log.d(TAG, "Native handle edge auto-scroll started")
                startFrames()
            }
        } else {
            stopFrames()
        }
        forwardSynthetic(handle, MotionEvent.ACTION_MOVE, fedX, fedY,
            event.eventTime)
    }

    private fun processHandleRelease(handle: View, event: MotionEvent) {
        if (!dragging || handleRef?.get() !== handle) return
        if (!nativeDownDelivered) {
            cancelDrag()
            return
        }
        stopFrames()
        val release = PendingRelease(event.actionMasked, event.eventTime)
        if (pendingScrollSteps > 0) {
            pendingRelease = release
            Log.d(TAG, "Deferring handle UP until $pendingScrollSteps scroll steps finish")
            val releaseGeneration = gestureGeneration
            webView.postDelayed({
                if (dragging && gestureGeneration == releaseGeneration &&
                    pendingRelease === release) {
                    Log.w(TAG, "Scroll acknowledgement timeout; ending handle drag")
                    finishNativeRelease(handle, release)
                }
            }, 350L)
        } else {
            finishNativeRelease(handle, release)
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
        if (right - left < dp(60f) || bottom - top < dp(if (dragIsChat) 80f else 40f)) {
            return Feed(fingerX, fingerY, 0f)
        }
        val tipX = fingerX - fingerOffsetX
        val tipY = fingerY - fingerOffsetY
        // A text field can be short: scale the edge zone and insets to it so
        // the middle of the field still selects without scrolling. #chat
        // keeps its original, unscaled values.
        val scaleLimit = if (dragIsChat) Float.MAX_VALUE else bottom - top
        val zone = minOf(dp(EDGE_ZONE_DP), scaleLimit * 0.22f)
        val overshootMax = dp(MAX_OVERSHOOT_DP)
        val minSpeed = if (dragIsChat) MIN_SPEED_DP_S else PANEL_MIN_SPEED_DP_S
        val maxSpeed = if (dragIsChat) MAX_SPEED_DP_S else PANEL_MAX_SPEED_DP_S
        var v = 0f
        if (dragChromiumSpeed) {
            // Chromium's drag-selection autoscroll (LayoutBox::
            // CalculateAutoscrollDirection + Autoscroll): a 20 CSS px belt
            // inside each edge; every frame the box scrolls by how far the
            // point is into the belt plus any distance past the edge. No
            // acceleration over time. Speeds here are CSS px per second.
            val belt = CHROMIUM_BELT_CSS * scaleY
            if (tipY < top + belt && canScrollUp) {
                v = -minOf((top + belt - tipY) / scaleY, CHROMIUM_MAX_STEP_CSS) * CHROMIUM_FRAMES_PER_S
            } else if (tipY > bottom - belt && canScrollDown) {
                v = minOf((tipY - (bottom - belt)) / scaleY, CHROMIUM_MAX_STEP_CSS) * CHROMIUM_FRAMES_PER_S
            }
        } else if (tipY < top + zone && canScrollUp) {
            val t = ((top + zone - tipY) / overshootMax).coerceIn(0f, 1f)
            v = -(minSpeed + (maxSpeed - minSpeed) * t * t)
        } else if (tipY > bottom - zone && canScrollDown) {
            val t = ((tipY - (bottom - zone)) / overshootMax).coerceIn(0f, 1f)
            v = minSpeed + (maxSpeed - minSpeed) * t * t
        }
        val topInset = minOf(dp(if (canScrollUp) TOP_SAFE_DP else 14f), scaleLimit * 0.18f)
        val bottomInset = minOf(dp(if (canScrollDown) BOTTOM_SAFE_DP else 4f), scaleLimit * 0.18f)
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
        // Only the long chat accelerates; settings panels keep a steady speed.
        if (!dragIsChat) return baseVelocity
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
            // Let the JS side scroll the region without rewriting Range/Selection.
            val delta = accelerated * deltaSeconds
            if (delta.isFinite()) {
                // The JavaScript scroll and Chromium's MOVE are on different
                // queues. Track outstanding scroll work so that the *last*
                // MOVE and UP are never delivered against stale scroll bounds.
                val generation = gestureGeneration
                pendingScrollSteps++
                scrolledDuringGesture = true
                try {
                    webView.evaluateJavascript(
                        "window.__stAutoScrollStep && " +
                        "window.__stAutoScrollStep($delta,${screenToCssX(fedX - fingerOffsetX)}," +
                        "${screenToCssY(fedY - fingerOffsetY - dp(12f))});"
                    ) { _ ->
                        if (generation == gestureGeneration) {
                            pendingScrollSteps = (pendingScrollSteps - 1).coerceAtLeast(0)
                            if (pendingScrollSteps == 0) {
                                val release = pendingRelease
                                val activeHandle = handleRef?.get()
                                if (release != null && activeHandle != null) {
                                    finishNativeRelease(activeHandle, release)
                                }
                            }
                        }
                    }
                } catch (error: RuntimeException) {
                    pendingScrollSteps = (pendingScrollSteps - 1).coerceAtLeast(0)
                    Log.w(TAG, "Unable to queue native-handle scroll", error)
                }
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

    /**
     * Hooks Chromium's handle popups for ANY selection, not only chat ones, so
     * that the first chat drag is never missed and non-chat drags can be
     * observed (never modified). Popups can appear some time after the
     * selection, so scan a few times over ~1.2s; bursts are coalesced.
     */
    private fun requestScanBurst() {
        val now = SystemClock.uptimeMillis()
        val previous = lastScanEnqueuedMs.get()
        if (now - previous < 300L || !lastScanEnqueuedMs.compareAndSet(previous, now)) return
        webView.post {
            scanForHandles()
            for (delay in longArrayOf(120L, 300L, 650L, 1_200L)) {
                webView.postDelayed({ scanForHandles() }, delay)
            }
        }
    }

    private fun scanForHandles() {
        // Not gated on the reported selection state: text-field selections
        // may not report on every WebView version, and scans are cheap.
        if (closed || !pageReady || dragging) return
        val now = SystemClock.uptimeMillis()
        if (now - lastScanMs < 100) return
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

    // ---- Long-press drag ----------------------------------------------------
    // Long-press a word and, without lifting, drag: Chromium extends the
    // selection from the WebView's own touch stream, not from a handle popup,
    // so the handle assistance above never sees it. This path gives that
    // gesture the same clamp and edge auto-scroll. It runs only after the page
    // confirms the selection was made by THIS touch and sits in a scroll region
    // (#chat or a settings panel); every other WebView touch passes untouched.

    private enum class LongPressState { IDLE, CHECKING, ASSIST, NATIVE }

    // Uptime of the last report that carried a selection (pre-check only).
    @Volatile private var lastSelectionReportMs = 0L
    private var lpState = LongPressState.NATIVE
    private var lpDownTime = 0L
    private var lpNoReportChecked = false
    // A hold-and-drag from a dropdown's text. Chromium would keep that
    // selection trapped inside the dropdown, so its moves are not forwarded:
    // the page sets the selection (dropdown -> finger) itself instead.
    private var lpPageDriven = false
    // The last position Chromium itself was given for this touch.
    private var lpChromiumX = 0f
    private var lpChromiumY = 0f
    private var lpPointerId = 0
    private var lpDeviceId = 0
    private var lpSource = InputDevice.SOURCE_TOUCHSCREEN
    private var lpGeneration = 0L
    private var lpPendingSteps = 0
    private var lpScrolled = false
    private var lpPendingRelease: PendingRelease? = null
    private var lpRunning = false
    private var lpLastFrameNanos = 0L
    private var lpJitter = false
    private var lpRawX = 0f
    private var lpRawY = 0f
    private var lpFedX = 0f
    private var lpFedY = 0f

    /**
     * Every WebView touch, called from MainActivity's WebView touch listener.
     * Returns true only for events this class has itself forwarded (with a
     * clamped position) to the WebView during an assisted long-press drag.
     */
    fun onWebViewTouch(event: MotionEvent): Boolean {
        if (closed || !pageReady) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lpFlushPendingRelease()
                lpGeneration++
                lpState = if (event.pointerCount == 1 && !dragging)
                    LongPressState.IDLE else LongPressState.NATIVE
                lpDownTime = event.downTime
                lpNoReportChecked = false
                lpPointerId = event.getPointerId(0)
                lpDeviceId = event.deviceId
                lpSource = event.source
                lpRawX = event.rawX
                lpRawY = event.rawY
                lpChromiumX = event.rawX
                lpChromiumY = event.rawY
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                when (lpState) {
                    LongPressState.ASSIST -> {
                        if (event.pointerCount != 1) {
                            lpStopAssist()
                            return false
                        }
                        lpMove(event)
                        return true
                    }
                    LongPressState.IDLE -> {
                        lpRawX = event.rawX
                        lpRawY = event.rawY
                        lpChromiumX = event.rawX
                        lpChromiumY = event.rawY
                        lpMaybeBegin(event)
                    }
                    LongPressState.CHECKING -> {
                        lpRawX = event.rawX
                        lpRawY = event.rawY
                        lpChromiumX = event.rawX
                        lpChromiumY = event.rawY
                    }
                    LongPressState.NATIVE -> { }
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (lpState == LongPressState.ASSIST) {
                    lpRelease(event)
                    return true
                }
                lpState = LongPressState.NATIVE
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger: stop assisting and let Chromium have the rest.
                if (lpState == LongPressState.ASSIST) lpStopAssist()
                lpState = LongPressState.NATIVE
                return false
            }
        }
        return false
    }

    private fun lpMaybeBegin(event: MotionEvent) {
        if (event.pointerCount != 1 || dragging) {
            lpState = LongPressState.NATIVE
            return
        }
        // A long press must have happened, and a selection report must have
        // arrived since this finger went down. The page makes the real check.
        // A dropdown's text selection produces no report, so once a full
        // long-press time has passed the page is asked anyway (once per touch).
        val elapsed = event.eventTime - lpDownTime
        if (elapsed < 250L) return
        val reported = lastSelectionReportMs > lpDownTime
        if (!reported && (lpNoReportChecked ||
                elapsed < ViewConfiguration.getLongPressTimeout() + 100L)) return
        if (!reported) lpNoReportChecked = true
        lpState = LongPressState.CHECKING
        val generation = lpGeneration
        try {
            webView.evaluateJavascript(
                "window.__stLongPressDragBegin ? window.__stLongPressDragBegin() : 'none';"
            ) { response ->
                val kind = response?.trim()?.removeSurrounding("\"")
                val base = kind?.substringBefore(':')
                val assisted = base == "chat" || base == "panel" || base == "popup" ||
                    base == "dropdown"
                if (closed || generation != lpGeneration || lpState != LongPressState.CHECKING ||
                    dragging) {
                    // The finger lifted (or a handle took over) first.
                    if (assisted) endLongPressInPage()
                    if (generation == lpGeneration && lpState == LongPressState.CHECKING) {
                        lpState = LongPressState.NATIVE
                    }
                    return@evaluateJavascript
                }
                Log.d(TAG, "Long-press drag check: $response")
                if (!assisted) {
                    // Without a report yet, a late one may still bring a real
                    // long-press selection: keep watching for it.
                    lpState = if (reported) LongPressState.NATIVE else LongPressState.IDLE
                    return@evaluateJavascript
                }
                dragIsChat = base == "chat"
                dragChromiumSpeed = base == "popup" || kind?.endsWith(":popup") == true
                // A drag from a dropdown's text: the page drives it (see above).
                lpPageDriven = base == "dropdown"
                // Chromium extends from (about) the finger itself.
                fingerOffsetX = 0f
                fingerOffsetY = 0f
                resetAcceleration()
                lpPendingSteps = 0
                lpScrolled = false
                lpPendingRelease = null
                lpJitter = false
                lpFedX = lpRawX
                lpFedY = lpRawY
                lpState = LongPressState.ASSIST
                // Take the selection out of the dropdown right away.
                if (lpPageDriven) lpDriveSelection(clampAndVelocity(lpRawX, lpRawY))
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Long-press drag check unavailable", error)
            lpState = LongPressState.NATIVE
        }
    }

    private fun endLongPressInPage() {
        try {
            webView.evaluateJavascript(
                "window.__stLongPressDragEnd && window.__stLongPressDragEnd();", null
            )
        } catch (_: RuntimeException) { }
    }

    private fun lpMove(event: MotionEvent) {
        if (lpPendingRelease != null) return
        lpRawX = event.rawX
        lpRawY = event.rawY
        val result = clampAndVelocity(lpRawX, lpRawY)
        lpFedX = result.x
        lpFedY = result.y
        if (abs(result.velocity) > 0.01f) {
            acceleratedVelocity(result.velocity, SystemClock.uptimeMillis())
            lpStartFrames()
        } else {
            lpStopFrames()
        }
        if (lpPageDriven) {
            lpDriveSelection(result)
            return
        }
        forwardToWebView(MotionEvent.ACTION_MOVE, lpFedX, lpFedY, event.eventTime)
    }

    private fun lpDriveSelection(feed: Feed) {
        lpFedX = feed.x
        lpFedY = feed.y
        try {
            webView.evaluateJavascript(
                "window.__stDropdownDragTo && window.__stDropdownDragTo(" +
                "${screenToCssX(lpFedX)},${screenToCssY(lpFedY)});", null
            )
        } catch (_: RuntimeException) { }
    }

    private fun lpRelease(event: MotionEvent) {
        lpStopFrames()
        if (lpPageDriven) {
            // Chromium only gets the end of its touch, where it last saw the
            // finger: it ends its drag and shows the markers on the page's
            // selection, without moving it.
            forwardToWebView(event.actionMasked, lpChromiumX, lpChromiumY, event.eventTime)
            lpEnd()
            return
        }
        val release = PendingRelease(event.actionMasked, event.eventTime)
        if (lpPendingSteps > 0) {
            // Same rule as handles: Chromium must see the final position
            // only after the last queued scroll step has been applied.
            lpPendingRelease = release
            val generation = lpGeneration
            webView.postDelayed({
                if (generation == lpGeneration && lpPendingRelease === release) {
                    Log.w(TAG, "Long-press scroll acknowledgement timeout")
                    lpFinish(release)
                }
            }, 350L)
        } else {
            lpFinish(release)
        }
    }

    private fun lpFinish(release: PendingRelease) {
        if (lpState != LongPressState.ASSIST) return
        if (release.action == MotionEvent.ACTION_UP && lpScrolled) {
            val last = clampAndVelocity(lpRawX, lpRawY)
            lpFedX = last.x
            lpFedY = last.y
            forwardToWebView(MotionEvent.ACTION_MOVE, lpFedX, lpFedY, release.eventTime)
        }
        forwardToWebView(release.action, lpFedX, lpFedY, release.eventTime)
        lpEnd()
    }

    // A new touch began while an UP was still waiting for scroll steps.
    private fun lpFlushPendingRelease() {
        val release = lpPendingRelease ?: return
        lpFinish(release)
    }

    /** Stops assisting; the rest of the gesture goes to Chromium unchanged. */
    private fun lpStopAssist() {
        lpStopFrames()
        lpEnd()
    }

    private fun lpCancel() {
        when (lpState) {
            LongPressState.ASSIST -> {
                lpStopFrames()
                if (webView.isAttachedToWindow) {
                    forwardToWebView(MotionEvent.ACTION_CANCEL,
                        if (lpPageDriven) lpChromiumX else lpFedX,
                        if (lpPageDriven) lpChromiumY else lpFedY,
                        SystemClock.uptimeMillis())
                }
                lpEnd()
            }
            LongPressState.CHECKING -> {
                lpGeneration++ // a late page answer then ends its own drag state
                lpState = LongPressState.NATIVE
            }
            else -> lpState = LongPressState.NATIVE
        }
    }

    private fun lpEnd() {
        lpState = LongPressState.NATIVE
        lpPageDriven = false
        lpPendingRelease = null
        lpPendingSteps = 0
        lpScrolled = false
        lpGeneration++ // ignore late scroll acknowledgements
        resetAcceleration()
        endLongPressInPage()
    }

    private fun forwardToWebView(action: Int, screenX: Float, screenY: Float, whenMs: Long) {
        webView.getLocationOnScreen(screenLocation)
        val properties = MotionEvent.PointerProperties().apply {
            id = lpPointerId
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = screenX
            y = screenY
            pressure = 1f
            size = 1f
        }
        // Raw coordinates stay on screen; local ones are made WebView-relative,
        // exactly as a real dispatched event would be.
        val event = MotionEvent.obtain(lpDownTime, whenMs, action, 1,
            arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, lpDeviceId, 0, lpSource, 0)
        event.offsetLocation(-screenLocation[0].toFloat(), -screenLocation[1].toFloat())
        try {
            webView.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private val lpFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!lpRunning || lpState != LongPressState.ASSIST || closed) {
                lpRunning = false
                return
            }
            if (!pageReady || !webView.isAttachedToWindow) {
                lpCancel()
                return
            }
            if (lpLastFrameNanos != 0L && frameTimeNanos - lpLastFrameNanos < 15_000_000L) {
                Choreographer.getInstance().postFrameCallback(this)
                return
            }
            val deltaSeconds = if (lpLastFrameNanos == 0L) 0.016f else
                ((frameTimeNanos - lpLastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lpLastFrameNanos = frameTimeNanos
            val result = clampAndVelocity(lpRawX, lpRawY)
            lpFedX = result.x
            lpFedY = result.y
            if (abs(result.velocity) < 0.01f) {
                lpStopFrames()
                return
            }
            val delta = acceleratedVelocity(result.velocity, SystemClock.uptimeMillis()) *
                deltaSeconds
            if (delta.isFinite()) {
                val generation = lpGeneration
                lpPendingSteps++
                lpScrolled = true
                try {
                    webView.evaluateJavascript(
                        if (lpPageDriven) "window.__stAutoScrollStep && window.__stAutoScrollStep(" +
                            "$delta,${screenToCssX(lpFedX)},${screenToCssY(lpFedY)});"
                        else "window.__stAutoScrollStep && window.__stAutoScrollStep($delta,NaN,NaN);"
                    ) { _ ->
                        if (generation == lpGeneration) {
                            lpPendingSteps = (lpPendingSteps - 1).coerceAtLeast(0)
                            val release = lpPendingRelease
                            if (lpPendingSteps == 0 && release != null) lpFinish(release)
                        }
                    }
                } catch (error: RuntimeException) {
                    lpPendingSteps = (lpPendingSteps - 1).coerceAtLeast(0)
                    Log.w(TAG, "Unable to queue long-press scroll", error)
                }
            }
            // As for handles: a tiny alternating nudge makes Chromium re-hit-test
            // the moving end against the newly scrolled content.
            // Page-driven drags re-place the selection in the scroll step itself.
            if (!lpPageDriven) {
                lpJitter = !lpJitter
                val deltaJitter = if (lpJitter) -sign(result.velocity) * dp(1.1f) else 0f
                forwardToWebView(MotionEvent.ACTION_MOVE, lpFedX, lpFedY + deltaJitter,
                    SystemClock.uptimeMillis())
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun lpStartFrames() {
        if (lpRunning) return
        lpRunning = true
        lpLastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(lpFrameCallback)
    }

    private fun lpStopFrames() {
        resetAcceleration()
        if (!lpRunning) return
        lpRunning = false
        lpLastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(lpFrameCallback)
    }

    private inner class SelectionBridge {
        @JavascriptInterface
        fun onSelectionStateChanged(
            inside: Boolean, anySelection: Boolean, isChat: Boolean,
            left: Float, top: Float, right: Float, bottom: Float,
            cssWidth: Float, cssHeight: Float, scrollUp: Boolean, scrollDown: Boolean
        ) {
            if (closed || !pageReady) return
            if (!left.isFinite() || !top.isFinite() || !right.isFinite() ||
                !bottom.isFinite() || !cssWidth.isFinite() || !cssHeight.isFinite() ||
                cssWidth <= 0 || cssHeight <= 0 || right <= left || bottom <= top) {
                // Without usable region geometry there is nothing to clamp to.
                // Never leave a stale "region selection" flag behind.
                hasRegionSelection = false
            } else {
                rectCss = RectF(left, top, right, bottom)
                viewportWidthCss = cssWidth
                viewportHeightCss = cssHeight
                canScrollUp = scrollUp
                canScrollDown = scrollDown
                regionIsChat = isChat
                hasRegionSelection = inside
            }
            if (anySelection) lastSelectionReportMs = SystemClock.uptimeMillis()
            // selectionchange and chat scrolling can each fire dozens of
            // times a second; requestScanBurst() coalesces them.
            if (anySelection) requestScanBurst()
        }
    }
}
