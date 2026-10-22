/* Chat-first touch text selection for Android WebView.
 * SillyTavern's #chat is preferred when present. Other ordinary page text,
 * including visible menu/dialog labels, uses the same handles and toolbar.
 * Text fields, including readonly fields, share the custom handles; passwords, special
 * controls and links retain native editing/long-press behaviour.
 * Uses Android's floating ActionMode through a tiny Kotlin bridge; no extra engine.
 */
(function () {
    'use strict';
    if (window.__kiTavernChatHandlesInstalled) return 'already installed';
    window.__kiTavernChatHandlesInstalled = true;

    var STYLE = '__kiChatSelectionCSS';
    var HIGHLIGHT_NAME = '__kiTavernSelectedText';
    // Let Chromium paint the exact text range once. The old rectangle overlay
    // can double-paint paragraphs with nested inline/block markup.
    var USE_NATIVE_HIGHLIGHT = !!(window.CSS && CSS.highlights &&
        typeof window.Highlight === 'function' &&
        (!CSS.supports || CSS.supports('selector(::highlight(' + HIGHLIGHT_NAME + '))')));
    var TOUCH_LOCK = '__kiChatSelectionTouchLock';
    var HANDLE_CLASS = 'ki-chat-select-control';
    var style = document.createElement('style');
    style.id = STYLE;
    style.textContent =
        // Disable native selection only during a pending/custom long press.
        // Supported inputs use the custom handles; normal typing remains native.
        '.' + TOUCH_LOCK + ', .' + TOUCH_LOCK + ' * {' +
        ' -webkit-user-select:none!important;user-select:none!important;}' +
        '.' + TOUCH_LOCK + ' input, .' + TOUCH_LOCK + ' textarea, ' +
        '.' + TOUCH_LOCK + ' [contenteditable] {' +
        ' -webkit-user-select:text!important;user-select:text!important;}' +
        '#chat {overscroll-behavior-y:contain;}' +
        // For chat only, native handles are deliberately unavailable: our
        // Android listener and DOM long-press both activate custom selection.
        '#chat :not(input):not(textarea):not([contenteditable]) {' +
        '-webkit-user-select:none!important;user-select:none!important;}' +
        '#chat input, #chat textarea, #chat [contenteditable], ' +
        '#chat [contenteditable] * {' +
        '-webkit-user-select:text!important;user-select:text!important;}' +
        '::highlight(' + HIGHLIGHT_NAME + '){background-color:rgba(33,150,243,.30);}' +
        '#__kiChatSelectOverlay{position:fixed;inset:0;z-index:2147483640;' +
        'pointer-events:none;font-family:Roboto,Arial,sans-serif;}' +
        '#__kiChatSelectOverlay .ki-chat-select-mark{position:absolute;' +
        'background:rgba(33,150,243,.30);pointer-events:none;border-radius:1px;}' +
        // Mirror native input/textarea text geometry only during field selection.
        // The mirror never receives touches and does not modify field values.
        '#__kiChatSelectOverlay .ki-field-mirror{position:absolute;' +
        'box-sizing:border-box;overflow:hidden;pointer-events:none;' +
        'color:transparent!important;-webkit-text-fill-color:transparent!important;}' +
        '.' + TOUCH_LOCK + ' input.__kiFieldSelectActive, ' +
        '.' + TOUCH_LOCK + ' textarea.__kiFieldSelectActive, ' +
        'input.__kiFieldSelectActive,textarea.__kiFieldSelectActive{' +
        '-webkit-user-select:none!important;user-select:none!important;}' +
        '#__kiChatSelectOverlay .ki-chat-select-handle,' +
        '.ki-chat-select-scroll-host .ki-chat-select-handle{' +
        'position:fixed;width:34px;height:44px;margin-left:-17px;touch-action:none;' +
        'pointer-events:auto;-webkit-tap-highlight-color:transparent;}' +
        '#__kiChatSelectOverlay .ki-chat-select-handle{position:absolute;}' +
        // For ordinary finger scrolling, place handles in the same scrolled
        // content layer as the selected text. Chromium composites them together
        // rather than waiting for our next main-thread scroll/animation frame.
        '.ki-chat-select-scroll-host{' +
        'position:relative!important;display:block!important;' +
        'width:0!important;height:0!important;min-height:0!important;' +
        'margin:0!important;padding:0!important;border:0!important;' +
        'flex:0 0 0!important;overflow:visible!important;' +
        'pointer-events:none!important;z-index:2147483640!important;}' +
        '.ki-chat-select-scroll-host .ki-chat-select-handle{position:absolute;}' +
        '#__kiChatSelectOverlay .ki-chat-select-handle::before,' +
        '.ki-chat-select-scroll-host .ki-chat-select-handle::before{' +
        'content:"";position:absolute;left:16px;top:0;width:2px;height:23px;' +
        'background:#2196f3;border-radius:2px;}' +
        '#__kiChatSelectOverlay .ki-chat-select-handle::after,' +
        '.ki-chat-select-scroll-host .ki-chat-select-handle::after{' +
        'content:"";position:absolute;left:9px;top:21px;width:16px;height:16px;' +
        'background:#2196f3;border-radius:50%;}' +
        '';
    (document.head || document.documentElement).appendChild(style);

    var pending = null;
    var tapCandidate = null;
    var session = null;
    var drag = null;
    var frame = 0;
    var lastFrameTime = 0;
    var toolbarTimer = null;
    // While any webpage scroll is moving, keep Android's floating toolbar
    // hidden without clearing the selection or the custom handle positions.
    var toolbarScrollIdleTimer = null;
    var toolbarPausedForScroll = false;
    // A grabbed scrollbar keeps the toolbar hidden until its finger releases.
    var toolbarScrollbarHeld = false;
    var toolbarTouchHeld = false;
    var lastToolbarUpdate = 0;
    var correctedExternalScrolls = 0;
    var recentFieldTouch = null;
    var fieldSelectReentry = false;
    var touchSequence = 0;
    var activePressToken = 0;
    var androidPress = null;
    // During an ordinary scroll, project the last *real caret* positions using
    // the selected container's movement; remeasure once the motion settles.
    // This avoids WebView compositor lag and edge-clamped handle drift.
    var motionFrame = 0;
    var motionSettleTimer = null;
    var motionActive = false;

    // Start at 850 px/s, reaching 2048 px/s after 2 seconds.
    // Continue to 4096 px/s at 4 seconds, then apply a configurable
    // multiplier every further 2 seconds. Values between these times
    // interpolate smoothly; leaving the edge, reversing, or stopping resets it.
    var SELECTION_SCROLL_START_PX_PER_SECOND = 850;
    var SELECTION_SCROLL_MAX_PX_PER_SECOND = 2048; // Speed at 2 seconds
    var SELECTION_SCROLL_RAMP_SECONDS = 2.0;
    var SELECTION_SCROLL_LONG_HOLD_MULTIPLIER = 2.0; // Fixed: 2s -> 4s
    var SELECTION_SCROLL_LONG_HOLD_INTERVAL_SECONDS = 2.0;
    var SELECTION_SCROLL_AFTER_4S_MULTIPLIER = 2.0; // Edit this: 2.1, 4, etc.

    var DOCUMENT_SCROLL = document.scrollingElement || document.documentElement;
    // Only elements requiring native editing, link actions, or non-text controls
    // are excluded. In particular, do not exclude nav/header/footer/labels or
    // button/menu-item *text*: those were causing an inconsistent mixture of
    // Chromium's selection handles and our own across normal page interfaces.
    // Actual selection still requires a visible text-node hit (see eligible()).
    var EXCLUDE = 'a[href],input,textarea,select,option,' +
        '[contenteditable],[role="textbox"],[role="searchbox"],' +
        '[role="link"],[role="combobox"],[role="slider"],' +
        '[role="spinbutton"],[role="switch"],[role="checkbox"],' +
        '[role="radio"],[aria-hidden="true"],[inert],[hidden],' +
        'script,style,noscript,template,svg,canvas,' +
        '.code-copy,.ki-chat-select-control';

    function inside(root, node) { return !!(root && node && root.contains(node)); }
    function canScroll(el) {
        if (!el || el === document.body || el === document.documentElement) return false;
        var css = getComputedStyle(el);
        return (css.overflowY === 'auto' || css.overflowY === 'scroll') &&
            el.scrollHeight > el.clientHeight + 2 && el.clientHeight > 20;
    }
    function selectableFieldFor(target) {
        if (!target || !target.closest) return null;
        var field = target.closest('input,textarea');
        if (!field || field.disabled ||
            field.closest('[inert],[hidden],[aria-hidden="true"]')) return null;
        if (field.tagName === 'INPUT' &&
            ['text','search','url','email','tel'].indexOf(field.type) < 0) return null;
        // No passwords, numeric inputs, selection boxes or custom editors.
        if (typeof field.selectionStart !== 'number' ||
            typeof field.setRangeText !== 'function') return null;
        var css = getComputedStyle(field);
        return css.display === 'none' || css.visibility === 'hidden' ? null : field;
    }
    function areaFor(target) {
        var field = selectableFieldFor(target);
        if (field) return {root:field, scroll:field, field:field, chat:false};
        if (!target || !target.closest || target.closest(EXCLUDE)) return null;
        // Labels tied to form controls should keep native focus/edit handling.
        var label = target.closest('label');
        if (label && label.control) return null;
        var chat = document.getElementById('chat');
        if (inside(chat, target)) return {root:chat, scroll:chat, chat:true};

        // Prefer the nearest independently scrollable area. This supports
        // scrollable settings panes, dialogs, and dropdowns on other websites.
        var scroll = null;
        for (var el = target; el && el !== document.body; el = el.parentElement) {
            if (canScroll(el)) { scroll = el; break; }
        }
        if (scroll) return {root:scroll, scroll:scroll, chat:false};

        // Fixed/popover menu text shouldn't inherit the whole document as its
        // selection range. Constrain it to the closest meaningful UI region.
        var region = target.closest(
            'dialog,[role="dialog"],[role="alertdialog"],' +
            '[role="menu"],[role="menubar"],[role="listbox"],' +
            '[role="tree"],[role="tablist"],[role="toolbar"],' +
            '[role="navigation"],nav,header,footer,aside,[popover]');
        var root = region || target.closest(
            'main,[role="main"],article,[role="article"],[itemprop="articleBody"]') ||
            document.body;
        if (!root || root === document.documentElement) return null;
        return {root:root, scroll:DOCUMENT_SCROLL, chat:false};
    }

    function viewportBox(area, outsideField) {
        var vv = window.visualViewport;
        var left = vv ? vv.offsetLeft : 0;
        var top = vv ? vv.offsetTop : 0;
        var right = vv ? left + vv.width : window.innerWidth;
        var bottom = vv ? top + vv.height : window.innerHeight;
        // Intersect with the selected container and every clipping ancestor.
        // Document scrolling has a viewport, not a usable document-element rect.
        var el = outsideField && area.field ? area.root.parentElement : area.root;
        while (el && el !== document.documentElement) {
            var css = getComputedStyle(el);
            var clips = el === area.root || el === area.scroll ||
                ['auto','scroll','hidden','clip'].indexOf(css.overflowY) !== -1 ||
                ['auto','scroll','hidden','clip'].indexOf(css.overflowX) !== -1;
            if (clips && el !== document.body) {
                var r = el.getBoundingClientRect();
                left = Math.max(left, r.left);
                top = Math.max(top, r.top);
                right = Math.min(right, r.right);
                bottom = Math.min(bottom, r.bottom);
            }
            el = el.parentElement;
        }
        return {left:left, top:top, right:right, bottom:bottom,
            width:Math.max(0, right-left), height:Math.max(0, bottom-top)};
    }
    function lock(area) { if (area) area.root.classList.add(TOUCH_LOCK); }
    function unlock(area) {
        if (area && (!pending || pending.area.root !== area.root) &&
            (!session || session.area.root !== area.root)) {
            area.root.classList.remove(TOUCH_LOCK);
        }
    }
    function stopPending() {
        if (!pending) return;
        var previous = pending;
        if (previous.timer) clearTimeout(previous.timer);
        pending = null;
        unlock(previous.area);
    }
    function stopFrame() {
        if (frame) cancelAnimationFrame(frame);
        frame = 0;
        lastFrameTime = 0;
    }
    // Save only the stationary handle origins. While the user scrolls with a
    // finger, the caret's live DOM rectangle (not an estimated scroll delta)
    // is the source of truth for where the visible handle should be painted.
    function recordHandlePositions(rects) {
        if (!session) return;
        session.handlePositionSnapshot = {
            handles:session.handles.map(function (handle) {
                return {left:parseFloat(handle.style.left) || 0,
                    top:parseFloat(handle.style.top) || 0};
            })
        };
    }
    function cancelMotion(keepVisualPosition) {
        if (motionFrame) cancelAnimationFrame(motionFrame);
        if (motionSettleTimer) clearTimeout(motionSettleTimer);
        motionFrame = 0;
        motionSettleTimer = null;
        motionActive = false;
        if (!session) return;
        // Keep the composited offset until the next draw; clearing it first
        // would flash the handle at its old, pre-scroll position for a frame.
        if (!keepVisualPosition)
            session.handles.forEach(function (handle) { handle.style.transform = ''; });
        session.marks.style.visibility = '';
    }
    function motionTick() {
        motionFrame = 0;
        if (!session || drag || !motionActive) return;
        var base = session.handlePositionSnapshot;
        if (!base) { scheduleDraw(); return; }

        // A finger-driven (composited/kinetic) scroll isn't guaranteed to move
        // by the same distance as root.scrollTop on the JavaScript thread. The
        // highlighted text is painted from the live DOM Range, so measure those
        // same caret endpoints and place the handles against those rectangles.
        // Unlike draw(), this changes only the two handle transforms and never
        // rebuilds the selection or its highlight while the page is moving.
        if (session.area.field) {
            syncFieldMirror(session);
            if (!session) return;
        }
        var box = viewportBox(session.area);
        [session.start, session.end].forEach(function (point, i) {
            var handle = session.handles[i];
            var r = pointRect(point);
            if (!r || r.bottom < box.top || r.top > box.bottom ||
                r.right < box.left || r.left > box.right) {
                handle.style.display = 'none';
                return;
            }
            var left = Math.max(box.left + 17,
                Math.min(box.right - 17, r.left));
            var top = Math.max(box.top,
                Math.min(box.bottom - 40, r.bottom - 2));
            if (session.area.field) {
                var handleBox = viewportBox(session.area, true);
                left = Math.max(handleBox.left + 17, Math.min(handleBox.right - 17, r.left));
                top = Math.max(handleBox.top, Math.min(handleBox.bottom - 40, r.bottom - 2));
            }
            var aligned = overlayCoordinates(session, left, top);
            handle.style.display = 'block';
            handle.style.transform = 'translate3d(' +
                (aligned.x - base.handles[i].left) + 'px,' +
                (aligned.y - base.handles[i].top) + 'px,0)';
        });
        // The old-WebView fallback draws its own highlight rectangles; those
        // shouldn't stay frozen in screen space during a kinetic scroll.
        if (!session.nativeHighlight) session.marks.style.visibility = 'hidden';
        // WebView can animate touch/kinetic scrolling on its compositor between
        // main-thread scroll events. Follow the live caret positions every
        // displayed frame until motion settles, not just when an event arrives.
        if (motionActive && session && !drag)
            motionFrame = requestAnimationFrame(motionTick);
    }
    function beginMotion() {
        if (!session || drag) return;
        if (session.handleHost) {
            // Both handles now scroll *with* the content. Do not overwrite
            // their stable content coordinates with asynchronous viewport
            // measurements during compositor-driven touch/momentum scrolling.
            if (!session.nativeHighlight) session.marks.style.visibility = 'hidden';
            if (motionSettleTimer) clearTimeout(motionSettleTimer);
            motionSettleTimer = setTimeout(function () {
                motionSettleTimer = null;
                if (!session || drag) return;
                session.marks.style.visibility = '';
                scheduleDraw();
            }, 140);
            return;
        }
        if (!session.handlePositionSnapshot) {
            scheduleDraw();
            return;
        }
        motionActive = true;
        if (!motionFrame) motionFrame = requestAnimationFrame(motionTick);
        if (motionSettleTimer) clearTimeout(motionSettleTimer);
        motionSettleTimer = setTimeout(function () {
            motionSettleTimer = null;
            if (!session || drag) { cancelMotion(); return; }
            cancelMotion(true);
            scheduleDraw();
        }, 110);
    }
    // While a custom handle is held, the WebView's built-in scroll handling,
    // scroll anchoring and a page's smooth-scroll CSS must not compete with
    // the incremental selection scroll. This lock only exists during a drag.
    function beginDragScrollLock() {
        if (!drag || !session) return;
        var el = session.area.scroll;
        var props = ['scroll-behavior', 'overflow-anchor', 'scroll-snap-type'];
        drag.savedScrollStyles = props.map(function (prop) {
            return {name:prop, value:el.style.getPropertyValue(prop),
                priority:el.style.getPropertyPriority(prop)};
        });
        el.style.setProperty('scroll-behavior', 'auto', 'important');
        el.style.setProperty('overflow-anchor', 'none', 'important');
        el.style.setProperty('scroll-snap-type', 'none', 'important');
        drag.expectedTop = el.scrollTop;
        drag.pageX = window.scrollX;
        drag.pageY = window.scrollY;
    }
    function restoreDragScrollLock(previous) {
        if (!previous || !previous.savedScrollStyles || !session) return;
        var el = session.area.scroll;
        previous.savedScrollStyles.forEach(function (old) {
            if (old.value) el.style.setProperty(old.name, old.value, old.priority);
            else el.style.removeProperty(old.name);
        });
    }
    function guardDragScroll() {
        if (!drag || !session) return;
        var el = session.area.scroll;
        var expected = Math.min(Math.max(0, drag.expectedTop),
            Math.max(0, el.scrollHeight - el.clientHeight));
        // Our dragTick stores each intended step before setting scrollTop.
        // Any other change while the handle is held is an unwanted jump.
        if (Math.abs(el.scrollTop - expected) > 1) {
            correctedExternalScrolls++;
            el.scrollTop = expected;
        }
        drag.expectedTop = el.scrollTop;
        // A nested chat must not accidentally scroll the surrounding page.
        if (el !== DOCUMENT_SCROLL &&
            (Math.abs(window.scrollY - drag.pageY) > 1 ||
             Math.abs(window.scrollX - drag.pageX) > 1)) {
            correctedExternalScrolls++;
            window.scrollTo(drag.pageX, drag.pageY);
        }
    }
    // Collapse Chromium's *internal* input selection once its offsets have been
    // copied into our independent mirror. This keeps the keyboard/caret in the
    // real field, but removes the second, native pair of selection markers.
    // It runs only for ordinary supported inputs, never passwords/editors.
    function collapseNativeFieldSelection(field) {
        if (!field || !field.isConnected ||
            field.selectionStart === field.selectionEnd || fieldSelectReentry) return;
        fieldSelectReentry = true;
        try { field.setSelectionRange(field.selectionEnd, field.selectionEnd); }
        catch (_) { /* Some input modes do not support setSelectionRange. */ }
        finally { fieldSelectReentry = false; }
    }
    function nativeToolbar() {
        return window.KiTavernSelectionToolbar || null;
    }
    function hideToolbar() {
        if (toolbarTimer) { clearTimeout(toolbarTimer); toolbarTimer = null; }
        if (session) session.nativeToolbarVisible = false;
        var bridge = nativeToolbar();
        if (bridge) bridge.hide();
    }
    function cancelToolbarScrollPause() {
        if (toolbarScrollIdleTimer) clearTimeout(toolbarScrollIdleTimer);
        toolbarScrollIdleTimer = null;
        toolbarPausedForScroll = false;
        toolbarTouchHeld = false;
    }
    function toolbarScrollMoved() {
        if (!session || drag) return;
        // Temporarily hide the *existing* floating ActionMode. Finishing and
        // recreating it on every swipe left its dismissal animation lingering
        // over moving text on some Android builds.
        if (!toolbarPausedForScroll) {
            toolbarPausedForScroll = true;
            if (toolbarTimer) { clearTimeout(toolbarTimer); toolbarTimer = null; }
            var bridge = nativeToolbar();
            if (bridge && bridge.pause) bridge.pause();
            else if (session.nativeToolbarVisible) hideToolbar();
        }
        if (toolbarScrollIdleTimer) clearTimeout(toolbarScrollIdleTimer);
        toolbarScrollIdleTimer = null;
        // Do not restore the popup while a scrollbar thumb is still held.
        if (toolbarScrollbarHeld || toolbarTouchHeld) return;
        var current = session;
        toolbarScrollIdleTimer = setTimeout(function () {
            toolbarScrollIdleTimer = null;
            if (session !== current || drag) return;
            toolbarPausedForScroll = false;
            // Re-measure the selected text after kinetic movement settles;
            // don't change the actual range or either selection handle.
            scheduleDraw();
            showToolbar(true);
        }, 280);
    }
    function toolbarTouchStarted() {
        if (!session || drag) return;
        toolbarTouchHeld = true;
        // A compositor-driven swipe may not send a touchmove until after the
        // scroll starts. Hide on finger-down rather than waiting for that event.
        toolbarScrollMoved();
    }
    function toolbarTouchFinished() {
        if (!toolbarTouchHeld) return;
        toolbarTouchHeld = false;
        // Wait for scrolling/momentum to settle before restoring the menu.
        toolbarScrollMoved();
    }
    function scrollbarTouchStarted() {
        toolbarScrollbarHeld = true;
        toolbarScrollMoved();
    }
    function scrollbarTouchFinished() {
        if (!toolbarScrollbarHeld) return;
        toolbarScrollbarHeld = false;
        // Start the normal idle delay only after releasing the thumb.
        toolbarScrollMoved();
    }
    function selectionAnchor() {
        if (!session) return null;
        var box = viewportBox(session.area);
        var range = selectedRange();
        if (!range) return null;
        var movedStart = session.lastMovedSide === 'start';
        var endpoint = pointRect(session[movedStart ? 'start' : 'end']);
        var r = endpoint && endpoint.bottom > box.top && endpoint.top < box.bottom ? endpoint : null;
        if (!r) {
            var rects = range.getClientRects();
            for (var i = 0; i < rects.length; i++) {
                var next = rects[i];
                if (next.height > 0 && next.bottom > box.top && next.top < box.bottom &&
                    next.right > box.left && next.left < box.right) {
                    r = next;
                    if (movedStart) break;
                }
            }
        }
        if (!r) return null;
        var left = Math.max(box.left, Math.min(box.right - 1, r.left));
        var right = Math.max(left + 1, Math.min(box.right, r.right));
        var top = Math.max(box.top, Math.min(box.bottom - 1, r.top));
        var bottom = Math.max(top + 1, Math.min(box.bottom, r.bottom));
        return {left:left, top:top, right:right, bottom:bottom};
    }
    function showToolbar(force) {
        if (!session || drag || toolbarPausedForScroll ||
            session.waitingForRelease !== null) return;
        var bridge = nativeToolbar();
        if (!bridge) return;
        var now = performance.now();
        if (!force && now - lastToolbarUpdate < 95) return;
        var r = selectionAnchor();
        if (!r) { hideToolbar(); return; }
        lastToolbarUpdate = now;
        session.nativeToolbarVisible = true;
        bridge.show(r.left, r.top, r.right, r.bottom,
            window.innerWidth, window.innerHeight,
            !!(session.area.field && !session.area.field.readOnly && !session.area.field.disabled));
    }
    function scheduleToolbarFor(current, delay) {
        if (toolbarTimer) clearTimeout(toolbarTimer);
        toolbarTimer = setTimeout(function () {
            toolbarTimer = null;
            if (session !== current || drag) return;
            current.waitingForRelease = null;
            showToolbar(true);
        }, delay);
    }
    function closeSelection() {
        cancelToolbarScrollPause();
        hideToolbar();
        cancelMotion();
        stopFrame();
        restoreDragScrollLock(drag);
        drag = null;
        if (!session) return;
        var previous = session;
        previous.area.scroll.removeEventListener('scroll', onSelectionScroll);
        if (previous.resizeObserver) previous.resizeObserver.disconnect();
        if (previous.layoutObserver) previous.layoutObserver.disconnect();
        if (previous.overlay.parentNode) previous.overlay.remove();
        if (previous.handleHost) previous.handleHost.remove();
        if (previous.nativeHighlight) CSS.highlights.delete(HIGHLIGHT_NAME);
        if (previous.area.field) previous.area.field.classList.remove('__kiFieldSelectActive');
        session = null;
        unlock(previous.area);
    }
    function usableText(root, node) {
        if (!node || node.nodeType !== 3 || !inside(root, node) || !node.data.trim()) return false;
        var parent = node.parentElement;
        if (!parent || parent.closest(EXCLUDE + ',script,style,noscript,template,[hidden]')) return false;
        var css = getComputedStyle(parent);
        return css.display !== 'none' && css.visibility !== 'hidden';
    }
    // A textarea's value is not a DOM text node. During selection, mirror its
    // *text layout* in a transparent, clipped element so DOM Ranges can locate
    // characters without changing the actual editor, selection or keyboard.
    function overlayCoordinates(current, x, y) {
        // Modal transforms change the containing block. All overlay children
        // use this one coordinate system, including during popup animations.
        var overlay = current.overlay;
        var bounds = overlay.getBoundingClientRect();
        var scaleX = overlay.offsetWidth ? bounds.width / overlay.offsetWidth : 1;
        var scaleY = overlay.offsetHeight ? bounds.height / overlay.offsetHeight : 1;
        return {x:(x - bounds.left) / (scaleX || 1),
            y:(y - bounds.top) / (scaleY || 1)};
    }
    function syncFieldMirror(current) {
        if (!current || !current.area.field || !current.mirror) return;
        var field = current.area.field;
        var mirror = current.mirror;
        if (selectableFieldFor(field) !== field || !field.isConnected ||
            mirror.firstChild !== current.mirrorText || field.value !== current.mirrorText.data) {
            closeSelection();
            return;
        }
        var rect = field.getBoundingClientRect(), css = getComputedStyle(field);
        var origin = overlayCoordinates(current, rect.left, rect.top);
        // offsetWidth/Height are untransformed border-box dimensions. The
        // mirror may be outside a scaled inner panel in the same dialog.
        var width = field.offsetWidth, height = field.offsetHeight;
        if (!width || !height || !rect.width || !rect.height) { closeSelection(); return; }
        var other = overlayCoordinates(current, rect.right, rect.bottom);
        mirror.style.left = origin.x + 'px';
        mirror.style.top = origin.y + 'px';
        mirror.style.width = width + 'px';
        mirror.style.height = height + 'px';
        mirror.style.transformOrigin = '0 0';
        mirror.style.transform = 'scale(' + ((other.x - origin.x) / width) + ',' +
            ((other.y - origin.y) / height) + ')';
        ['font','letterSpacing','wordSpacing','lineHeight','textAlign','direction',
         'textIndent','tabSize','paddingTop','paddingRight','paddingBottom','paddingLeft',
         'borderTopWidth','borderRightWidth','borderBottomWidth','borderLeftWidth',
         'textTransform','writingMode','wordBreak','fontKerning',
         'fontFeatureSettings','fontVariantCaps','fontVariationSettings'].forEach(function(prop) {
            mirror.style[prop] = css[prop];
        });
        mirror.style.boxSizing = 'border-box';
        mirror.style.borderStyle = 'solid';
        mirror.style.borderColor = 'transparent';
        var wraps = field.tagName === 'TEXTAREA' && field.wrap !== 'off';
        mirror.style.whiteSpace = wraps ? 'pre-wrap' : 'pre';
        mirror.style.overflowWrap = wraps ? 'break-word' : 'normal';
        mirror.style.overflow = 'hidden';
        mirror.scrollTop = field.scrollTop;
        mirror.scrollLeft = field.scrollLeft;
    }
    function fieldPoint(area, x, y) {
        var mirror = session && session.mirror;
        var text = session && session.mirrorText;
        if (!mirror || !text || session.area.field !== area.field) return null;
        syncFieldMirror(session);
        if (!session || !text.length) return null;
        var box = viewportBox(area);
        x = Math.max(box.left + 1, Math.min(box.right - 1, x));
        y = Math.max(box.top + 1, Math.min(box.bottom - 1, y));
        var range = document.createRange();
        // Only visible character rectangles are candidates. This also handles
        // wrapped textarea lines and horizontal scrolling in single-line inputs.
        var best = null, bestDistance = Infinity;
        var length = text.length;
        // Avoid measuring thousands of individual glyphs on every drag frame.
        // Check a coarse sample first, then examine its surrounding characters.
        var step = length > 400 ? Math.ceil(length / 200) : 1;
        function inspect(i) {
            range.setStart(text, i); range.setEnd(text, i + 1);
            var r = range.getBoundingClientRect();
            if (!r.height || r.bottom < box.top || r.top > box.bottom) return;
            var dy = y < r.top ? r.top - y : y > r.bottom ? y - r.bottom : 0;
            var dx = x < r.left ? r.left - x : x > r.right ? x - r.right : 0;
            var score = dy * 10 + dx;
            if (score < bestDistance) {
                bestDistance = score;
                best = x > (r.left + r.right) / 2 ? i + 1 : i;
            }
        }
        var coarseBest = 0, coarseDistance = Infinity;
        if (step > 1) {
            for (var n = 0; n < length; n += step) {
                range.setStart(text, n); range.setEnd(text, n + 1);
                var cr = range.getBoundingClientRect();
                if (!cr.height) continue;
                var ddy = y < cr.top ? cr.top - y : y > cr.bottom ? y - cr.bottom : 0;
                var ddx = x < cr.left ? cr.left - x : x > cr.right ? x - cr.right : 0;
                var distance = ddy * 10 + ddx;
                if (distance < coarseDistance) { coarseDistance = distance; coarseBest = n; }
            }
        }
        var from = step > 1 ? Math.max(0, coarseBest - step * 2) : 0;
        var to = step > 1 ? Math.min(length, coarseBest + step * 2 + 1) : length;
        for (var i = from; i < to; i++) inspect(i);
        return best === null ? null : {node:text, offset:best};
    }
    function fromXY(area, x, y) {
        if (area.field) return fieldPoint(area, x, y);
        var box = viewportBox(area);
        if (box.width < 20 || box.height < 20) return null;
        var inX = Math.min(8, box.width / 5), inY = Math.min(6, box.height / 5);
        x = Math.max(box.left + inX, Math.min(box.right - inX, x));
        y = Math.max(box.top + inY, Math.min(box.bottom - inY, y));
        var xs = [x, x - 10, x + 10, x - 22, x + 22];
        var ys = [y, y - 5, y + 5];
        for (var a = 0; a < ys.length; a++) {
            for (var b = 0; b < xs.length; b++) {
                var px = Math.max(box.left + inX, Math.min(box.right - inX, xs[b]));
                var py = Math.max(box.top + inY, Math.min(box.bottom - inY, ys[a]));
                var range = document.caretRangeFromPoint ? document.caretRangeFromPoint(px, py) : null;
                if (!range && document.caretPositionFromPoint) {
                    var pos = document.caretPositionFromPoint(px, py);
                    if (pos && pos.offsetNode) {
                        range = document.createRange();
                        try { range.setStart(pos.offsetNode, pos.offset); } catch (_) { range = null; }
                    }
                }
                if (!range || !usableText(area.root, range.startContainer)) continue;
                // Chromium may snap caretRangeFromPoint to the first/last text
                // node when the finger is over empty space or a fixed input.
                // Only accept text actually near the current visible finger row.
                var caret = range.getBoundingClientRect();
                if (!caret || !caret.height ||
                    caret.bottom < py - Math.max(26, caret.height * 1.5) ||
                    caret.top > py + Math.max(26, caret.height * 1.5) ||
                    caret.bottom < box.top || caret.top > box.bottom) continue;
                return {node:range.startContainer, offset:range.startOffset};
            }
        }
        return null;
    }
    function eligible(target, x, y) {
        var area = areaFor(target);
        if (!area) return null;
        if (area.field) {
            var fieldRect = area.field.getBoundingClientRect();
            if (!area.field.value || fieldRect.width < 30 || fieldRect.height < 18 ||
                x < fieldRect.left + 3 || x > fieldRect.right - 3 ||
                y < fieldRect.top + 3 || y > fieldRect.bottom - 3) return null;
            return area;
        }
        var box = viewportBox(area);
        if (box.height < 40 || box.width < 40 || y < box.top || y > box.bottom ||
            x > box.right - Math.min(32, box.width / 5)) return null;
        // A long press in empty space must not trigger a random text selection.
        var point = fromXY(area, x, y);
        if (!point) return null;
        var range = document.createRange();
        range.setStart(point.node, point.offset);
        range.collapse(true);
        var rect = range.getBoundingClientRect();
        if (!rect || Math.abs(x - rect.left) > 38 || y < rect.top - 20 ||
            y > rect.bottom + 20) return null;
        return area;
    }
    function compare(a, b) {
        var r = document.createRange();
        r.setStart(a.node, a.offset);
        r.collapse(true);
        // -1 if b before a, +1 if b after a, 0 if identical.
        return r.comparePoint(b.node, b.offset);
    }
    function selectedRange() {
        if (!session) return null;
        var r = document.createRange();
        try {
            r.setStart(session.start.node, session.start.offset);
            r.setEnd(session.end.node, session.end.offset);
        } catch (_) { return null; }
        return r;
    }
    function wordAt(area, x, y) {
        var p = fromXY(area, x, y);
        if (!p) return null;
        var value = p.node.data;
        if (!value || !value.length) return null;
        var i = Math.max(0, Math.min(value.length - 1, p.offset));
        // Unicode whitespace + common punctuation; still allows CJK text.
        var word = /[^\s.,!?;:()\[\]{}<>"'“”‘’\/\\|…]/u;
        if (!word.test(value[i]) && i > 0 && word.test(value[i - 1])) i--;
        if (!word.test(value[i])) return null;
        var from = i, to = i + 1;
        while (from > 0 && word.test(value[from - 1])) from--;
        while (to < value.length && word.test(value[to])) to++;
        return {
            start: {node: p.node, offset: from},
            end: {node: p.node, offset: to}
        };
    }
    function pointRect(point) {
        var range = document.createRange();
        try {
            range.setStart(point.node, point.offset);
            range.collapse(true);
            var rect = range.getBoundingClientRect();
            return rect && rect.height ? rect : null;
        } catch (_) { return null; }
    }
    function make(tag, cls, text) {
        var el = document.createElement(tag);
        el.className = cls;
        if (text !== undefined) el.textContent = text;
        return el;
    }
    function buildOverlay(area) {
        var overlay = document.createElement('div');
        overlay.id = '__kiChatSelectOverlay';
        overlay.setAttribute('aria-hidden', 'true');
        var marks = make('div', 'ki-chat-select-marks');
        var startHandle = make('div', HANDLE_CLASS + ' ki-chat-select-handle');
        var endHandle = make('div', HANDLE_CLASS + ' ki-chat-select-handle');
        startHandle.setAttribute('aria-label', 'Selection start');
        endHandle.setAttribute('aria-label', 'Selection end');
        startHandle.dataset.side = 'start';
        endHandle.dataset.side = 'end';
        overlay.appendChild(marks);
        overlay.appendChild(startHandle);
        overlay.appendChild(endHandle);
        // A z-index cannot escape a modal's top layer or its inert backdrop.
        // Use the nearest active host, including popovers nested in dialogs.
        var host = area.root;
        while (host && host !== document.documentElement) {
            var topLayer = host.matches('dialog[open]') || host === document.fullscreenElement;
            try { topLayer = topLayer || host.matches(':popover-open'); } catch (_) {}
            if (topLayer) break;
            host = host.parentElement;
        }
        (host || document.documentElement).appendChild(overlay);
        startHandle.addEventListener('touchstart', beginHandle, {passive:false});
        endHandle.addEventListener('touchstart', beginHandle, {passive:false});
        startHandle.addEventListener('pointerdown', beginMouseHandle);
        endHandle.addEventListener('pointerdown', beginMouseHandle);
        return {overlay: overlay, marks: marks, handles: [startHandle, endHandle]};
    }
    function attachScrollAnchoredHandles(current) {
        var scroller = current.area.scroll;
        if (current.area.field || scroller === DOCUMENT_SCROLL ||
            scroller === document.body || scroller === document.documentElement)
            return;
        // Table/grid and editable scroll hosts may have special child-layout
        // rules. Keep the existing fixed-position path for those elements.
        var scrollStyle = getComputedStyle(scroller);
        var display = scrollStyle.display;
        if (display !== 'block' && display !== 'flex' &&
            display !== 'flow-root') return;
        if (display === 'flex' && scrollStyle.flexDirection !== 'column') return;
        // Sticky/fixed text does not travel with ordinary scroll content.
        // Keep the original viewport-based positioning for that special case.
        for (var el = current.start.node.parentElement; el && el !== scroller;
             el = el.parentElement) {
            var position = getComputedStyle(el).position;
            if (position === 'sticky' || position === 'fixed') return;
        }
        var host = make('div', 'ki-chat-select-scroll-host');
        host.setAttribute('role', 'presentation');
        scroller.insertBefore(host, scroller.firstChild);
        current.handles.forEach(function (handle) { host.appendChild(handle); });
        current.handleHost = host;
    }
    // Probe at ACTION_DOWN so Android can synchronously consume the matching
    // long-click later. The probe is intentionally read-only: no selection,
    // styles, timers, or DOM edits are performed by this call.
    function androidPoint(screenX, screenY, physicalWidth, physicalHeight) {
        if (!physicalWidth || !physicalHeight) return null;
        // WebView coordinates are device pixels while page events are CSS px.
        return {x: screenX * window.innerWidth / physicalWidth,
            y: screenY * window.innerHeight / physicalHeight};
    }
    function probeAndroid(screenX, screenY, physicalWidth, physicalHeight, gestureId) {
        var p = androidPoint(screenX, screenY, physicalWidth, physicalHeight);
        var target = p && document.elementFromPoint(p.x, p.y);
        var area = target && eligible(target, p.x, p.y);
        androidPress = area ? {area:area, x:p.x, y:p.y, gestureId:gestureId,
            fieldRect:area.field ? area.field.getBoundingClientRect() : null} : null;
        return !!area;
    }
    function pressCoordinates(press) {
        var rect = press.area.field && press.area.field.getBoundingClientRect();
        return {x:press.x + (rect && press.fieldRect ? rect.left - press.fieldRect.left : 0),
            y:press.y + (rect && press.fieldRect ? rect.top - press.fieldRect.top : 0)};
    }
    // Android's own long-click gesture takes precedence over a native
    // Chromium selection, but only if the ACTION_DOWN preflight succeeded.
    function androidLongPress(screenX, screenY, physicalWidth, physicalHeight, gestureId) {
        if (!androidPress || androidPress.gestureId !== gestureId) return false;
        // The DOM timer may already own this exact physical press. Do not
        // re-hit-test against its new overlay or rebuild its selected range.
        if (session && activePressToken && session.initiatingPressToken === activePressToken) {
            session.androidGestureId = gestureId;
            session.waitingForRelease = 'android';
            return true;
        }
        var press = pending || androidPress;
        var area = press.area;
        if (!area.root.isConnected ||
            (area.field && selectableFieldFor(area.field) !== area.field)) return false;
        var p = pressCoordinates(press);
        stopPending();
        beginSelection(area, p.x, p.y, null);
        // The original finger may still be down. Show the toolbar after
        // release (or after a short fallback if WebView eats touchend).
        if (session && session.area.root === area.root) {
            session.androidGestureId = gestureId;
            session.waitingForRelease = 'android';
            return true;
        }
        return false;
    }

    // Android's own long-click can consume touchend before it reaches the page.
    // In that case MainActivity reports ACTION_UP explicitly. Never open the
    // native toolbar while the initiating finger is still touching the screen.
    function androidPressReleased(gestureId) {
        if (!androidPress || androidPress.gestureId !== gestureId) return;
        androidPress = null;
        stopPending();
        activePressToken = 0;
        if (recentFieldTouch) recentFieldTouch.releasedAt = performance.now();
        if (session && session.androidGestureId === gestureId && session.waitingForRelease !== null) {
            session.waitingForRelease = null;
            scheduleToolbarFor(session, 140);
        }
    }
    function androidPressCanceled(gestureId) {
        if (!androidPress || androidPress.gestureId !== gestureId) return;
        androidPress = null;
        stopPending();
        activePressToken = 0;
        recentFieldTouch = null;
        if (session && session.androidGestureId === gestureId && session.waitingForRelease !== null) {
            session.waitingForRelease = null;
            scheduleToolbarFor(session, 140);
        }
    }

    function beginSelection(area, x, y, touchId, fieldOffsets) {
        if (session && activePressToken && session.initiatingPressToken === activePressToken &&
            session.area.root === area.root) return;
        // Build the mirror before resolving a field's word location. Its text
        // node is independent from the actual input value and never edits it.
        if (!area.field) {
            var pair = wordAt(area, x, y);
            if (!pair) return;
        }
        closeSelection();
        tapCandidate = null;
        var ui = buildOverlay(area);
        session = Object.assign({area:area, start:null, end:null,
            initiatingPressToken:activePressToken,
            initiatingTouchId:area.field && recentFieldTouch && recentFieldTouch.field === area.field ?
                recentFieldTouch.id : touchId,
            androidGestureId:androidPress ? androidPress.gestureId : null,
            waitingForRelease:touchId, nativeToolbarVisible:false}, ui);
        if (area.field) {
            recentFieldTouch = null;
            session.mirror = make('div', 'ki-field-mirror');
            session.mirrorText = document.createTextNode(area.field.value);
            session.mirror.appendChild(session.mirrorText);
            session.overlay.insertBefore(session.mirror, session.handles[0]);
            area.field.classList.add('__kiFieldSelectActive');
            syncFieldMirror(session);
            if (!session) return;
            if (fieldOffsets && fieldOffsets.end > fieldOffsets.start) {
                // Chromium may have selected the word before our JS long-press
                // timer. Adopt those actual offsets and replace native handles.
                pair = {
                    start:{node:session.mirrorText,offset:Math.max(0,fieldOffsets.start)},
                    end:{node:session.mirrorText,offset:Math.min(session.mirrorText.length,fieldOffsets.end)}
                };
            } else {
                pair = wordAt(area, x, y);
            }
            if (!pair) { closeSelection(); return; }
            collapseNativeFieldSelection(area.field);
        }
        session.start = pair.start;
        session.end = pair.end;
        attachScrollAnchoredHandles(session);
        if (USE_NATIVE_HIGHLIGHT) {
            try {
                session.nativeHighlight = new Highlight();
                CSS.highlights.set(HIGHLIGHT_NAME, session.nativeHighlight);
            } catch (_) { session.nativeHighlight = null; }
        }
        // Don't show the toolbar underneath the initiating long-press finger.
        // The native toolbar appears after the initiating press is released.
        area.scroll.addEventListener('scroll', onSelectionScroll, {passive:true});
        lock(area);
        observeSelectionLayout(session);
        scheduleDraw();
    }
    function observeSelectionLayout(current) {
        // Selection endpoints can also move because images load, a message
        // streams in, or settings resize. Watch only the selected region while
        // selection exists, and realign once those layout changes settle.
        if (typeof ResizeObserver === 'function') {
            current.resizeObserver = new ResizeObserver(function () {
                if (session !== current || drag) return;
                if (current.handleHost) scheduleDraw();
                else beginMotion();
            });
            current.resizeObserver.observe(current.area.root);
            if (current.area.scroll !== current.area.root)
                current.resizeObserver.observe(current.area.scroll);
        }
        if (typeof MutationObserver === 'function') {
            current.layoutObserver = new MutationObserver(function (records) {
                if (session !== current || drag) return;
                // Handle positioning itself changes style attributes. Ignore
                // those mutations to avoid triggering a redraw feedback loop.
                if (records.every(function (record) {
                    return current.overlay.contains(record.target) ||
                        (current.handleHost && current.handleHost.contains(record.target));
                })) return;
                if (current.handleHost) scheduleDraw();
                else beginMotion();
            });
            current.layoutObserver.observe(current.area.root, {
                subtree:true, childList:true, characterData:true, attributes:true,
                attributeFilter:['class', 'style', 'hidden', 'open', 'inert', 'aria-hidden', 'disabled']
            });
            // Field size can stay unchanged while an enclosing options panel
            // moves, closes, or is removed. Observe ancestors, not their subtrees.
            for (var el = current.area.root.parentElement; el; el = el.parentElement) {
                current.layoutObserver.observe(el, {
                    childList:true, attributes:true,
                    attributeFilter:['class', 'style', 'hidden', 'open', 'inert', 'aria-hidden']
                });
            }
        }
    }
    function showHandle(handle, point, box) {
        handle.style.transform = '';
        var rect = pointRect(point);
        if (session && session.handleHost) {
            if (!rect) { handle.style.display = 'none'; return null; }
            // The host and text are in the same scroll coordinate system.
            // Their difference remains constant while the compositor scrolls,
            // avoiding the visible one-frame lag of fixed screen-space handles.
            var origin = session.handleHost.getBoundingClientRect();
            handle.style.display = 'block';
            handle.style.left = (rect.left - origin.left) + 'px';
            handle.style.top = (rect.bottom - 2 - origin.top) + 'px';
            return rect;
        }
        if (!rect || rect.bottom < box.top || rect.top > box.bottom ||
            rect.right < box.left || rect.left > box.right) {
            handle.style.display = 'none';
            return rect;
        }
        handle.style.display = 'block';
        var handleBox = session.area.field ? viewportBox(session.area, true) : box;
        var aligned = overlayCoordinates(session,
            Math.max(handleBox.left + 17, Math.min(handleBox.right - 17, rect.left)),
            Math.max(handleBox.top, Math.min(handleBox.bottom - 40, rect.bottom - 2)));
        handle.style.left = aligned.x + 'px';
        handle.style.top = aligned.y + 'px';
        return rect;
    }
    function draw() {
        if (!session) return;
        var area = session.area;
        if (area.field) syncFieldMirror(session);
        if (!session) return;
        if (!area.root.isConnected || !area.scroll.isConnected ||
            (area.field ? (session.start.node !== session.mirrorText ||
                             session.end.node !== session.mirrorText) :
                (!inside(area.root, session.start.node) ||
                 !inside(area.root, session.end.node)))) {
            closeSelection();
            return;
        }
        var r = selectedRange();
        if (!r) { closeSelection(); return; }
        var box = viewportBox(area);
        if (box.width < 20 || box.height < (area.field ? 18 : 20)) { closeSelection(); return; }
        if (session.nativeHighlight) {
            // Only update the highlight when the endpoints change. Scroll alone
            // moves the native highlight automatically; no per-frame DOM churn.
            var last = session.lastPaintedRange;
            if (!last || last.startNode !== session.start.node ||
                last.startOffset !== session.start.offset ||
                last.endNode !== session.end.node ||
                last.endOffset !== session.end.offset) {
                session.nativeHighlight.clear();
                session.nativeHighlight.add(r);
                session.lastPaintedRange = {
                    startNode:session.start.node, startOffset:session.start.offset,
                    endNode:session.end.node, endOffset:session.end.offset
                };
            }
        } else {
            // Old WebView fallback. Range.getClientRects() can include both a
            // paragraph's box and its nested text boxes: discard overlapping
            // parent/equal boxes rather than stacking translucent rectangles.
            var rectangles = r.getClientRects();
            var candidates = [];
            for (var i = 0; i < rectangles.length && candidates.length < 300; i++) {
                var item = rectangles[i];
                if (!item.width || !item.height || item.bottom <= box.top ||
                    item.top >= box.bottom || item.right <= box.left || item.left >= box.right)
                    continue;
                var left = Math.max(box.left, item.left), top = Math.max(box.top, item.top);
                var right = Math.min(box.right, item.right);
                var bottom = Math.min(box.bottom, item.bottom);
                if (right > left && bottom > top)
                    candidates.push({left:left, top:top, right:right, bottom:bottom});
            }
            var frag = document.createDocumentFragment();
            var count = 0;
            for (var a = 0; a < candidates.length && count < 220; a++) {
                var outer = candidates[a], redundant = false;
                for (var b = 0; b < candidates.length; b++) {
                    if (a === b) continue;
                    var inner = candidates[b];
                    if (inner.left >= outer.left - .5 && inner.right <= outer.right + .5 &&
                        inner.top >= outer.top - .5 && inner.bottom <= outer.bottom + .5 &&
                        (inner.left > outer.left + .5 || inner.right < outer.right - .5 ||
                         inner.top > outer.top + .5 || inner.bottom < outer.bottom - .5 ||
                         b < a)) { redundant = true; break; }
                }
                if (redundant) continue;
                var mark = make('div', 'ki-chat-select-mark');
                var origin = overlayCoordinates(session, outer.left, outer.top);
                var other = overlayCoordinates(session, outer.right, outer.bottom);
                mark.style.left = origin.x + 'px';
                mark.style.top = origin.y + 'px';
                mark.style.width = (other.x - origin.x) + 'px';
                mark.style.height = (other.y - origin.y) + 'px';
                frag.appendChild(mark);
                count++;
            }
            session.marks.replaceChildren(frag);
        }
        var startRect = showHandle(session.handles[0], session.start, box);
        var endRect = showHandle(session.handles[1], session.end, box);
        recordHandlePositions([startRect, endRect]);
        // The native toolbar stays anchored during normal scrolling; moving its
        // content rect every animation frame can disrupt WebView touch handling.
    }
    function scheduleDraw() {
        // Neither drag scrolling nor native kinetic scrolling should trigger
        // competing Range measurements on every frame.
        if (drag || motionActive) return;
        if (session && !frame) frame = requestAnimationFrame(function () {
            frame = 0;
            draw();
        });
    }
    function onSelectionScroll() {
        if (drag) guardDragScroll();
        else {
            beginMotion();
            toolbarScrollMoved();
        }
    }
    function beginHandle(e) {
        if (!session || e.touches.length !== 1) return;
        e.preventDefault();
        e.stopPropagation();
        cancelMotion(true);
        var t = e.changedTouches[0];
        hideToolbar();
        session.lastMovedSide = e.currentTarget.dataset.side;
        drag = {side: e.currentTarget.dataset.side, id: t.identifier,
            kind:'touch', x:t.clientX, y:t.clientY};
        lastFrameTime = 0;
        stopFrame();
        beginDragScrollLock();
        frame = requestAnimationFrame(dragTick);
    }
    function beginMouseHandle(e) {
        if (e.pointerType === 'touch' || e.button !== 0 || !session) return;
        e.preventDefault();
        e.stopPropagation();
        cancelMotion(true);
        hideToolbar();
        session.lastMovedSide = e.currentTarget.dataset.side;
        drag = {side:e.currentTarget.dataset.side, id:e.pointerId,
            kind:'pointer', x:e.clientX, y:e.clientY};
        try { e.currentTarget.setPointerCapture(e.pointerId); } catch (_) {}
        stopFrame();
        beginDragScrollLock();
        frame = requestAnimationFrame(dragTick);
    }
    function swapHandleSides(newSide) {
        if (!drag || !session || drag.side === newSide) return;
        // Reassign the DOM handles as well as the endpoints: the handle still
        // underneath the finger must keep moving after it crosses the other.
        var handles = session.handles;
        var oldStart = handles[0];
        handles[0] = handles[1];
        handles[1] = oldStart;
        handles[0].dataset.side = 'start';
        handles[1].dataset.side = 'end';
        handles[0].setAttribute('aria-label', 'Selection start');
        handles[1].setAttribute('aria-label', 'Selection end');
        drag.side = newSide;
        session.lastMovedSide = newSide;
    }
    function dragTick(now) {
        frame = 0;
        if (!drag || !session) return;
        var area = session.area;
        guardDragScroll();
        if (area.field) syncFieldMirror(session);
        if (!session) return;
        var rect = viewportBox(area);
        var edge = Math.min(56, rect.height / 4);
        var topZone = rect.top + edge;
        var bottomZone = rect.bottom - edge;
        var delta = drag.y < topZone ? drag.y - topZone :
            drag.y > bottomZone ? drag.y - bottomZone : 0;
        var dt = lastFrameTime ? Math.min(40, now - lastFrameTime) / 1000 : 1 / 60;
        lastFrameTime = now;
        var moved = false;
        if (delta) {
            var direction = Math.sign(delta);
            if (drag.edgeScrollDirection !== direction) {
                // A fresh edge scroll (or a reversal) starts unaccelerated.
                drag.edgeScrollDirection = direction;
                drag.edgeScrollElapsedSeconds = 0;
            }
            // 0-2s: 850 -> 2048; 2-4s: 2048 -> 4096 (unchanged).
            // Beyond 4s: smoothly multiply the 4096 speed by the adjustable
            // factor once per additional 2 seconds, with no speed discontinuity.
            var elapsed = drag.edgeScrollElapsedSeconds;
            var speedMagnitude;
            var fourSecondMark = SELECTION_SCROLL_RAMP_SECONDS +
                SELECTION_SCROLL_LONG_HOLD_INTERVAL_SECONDS;
            if (elapsed <= SELECTION_SCROLL_RAMP_SECONDS) {
                var progress = Math.min(1, elapsed / SELECTION_SCROLL_RAMP_SECONDS);
                var eased = progress * progress * (3 - 2 * progress);
                speedMagnitude = SELECTION_SCROLL_START_PX_PER_SECOND +
                    (SELECTION_SCROLL_MAX_PX_PER_SECOND -
                        SELECTION_SCROLL_START_PX_PER_SECOND) * eased;
            } else if (elapsed <= fourSecondMark) {
                var holdIntervals = (elapsed - SELECTION_SCROLL_RAMP_SECONDS) /
                    SELECTION_SCROLL_LONG_HOLD_INTERVAL_SECONDS;
                speedMagnitude = SELECTION_SCROLL_MAX_PX_PER_SECOND *
                    Math.pow(SELECTION_SCROLL_LONG_HOLD_MULTIPLIER, holdIntervals);
            } else {
                var laterIntervals = (elapsed - fourSecondMark) /
                    SELECTION_SCROLL_LONG_HOLD_INTERVAL_SECONDS;
                var speedAtFourSeconds = SELECTION_SCROLL_MAX_PX_PER_SECOND *
                    SELECTION_SCROLL_LONG_HOLD_MULTIPLIER;
                speedMagnitude = speedAtFourSeconds *
                    Math.pow(SELECTION_SCROLL_AFTER_4S_MULTIPLIER, laterIntervals);
            }
            var speed = direction * speedMagnitude;
            var before = area.scroll.scrollTop;
            var next = Math.max(0, Math.min(
                area.scroll.scrollHeight - area.scroll.clientHeight,
                before + speed * dt));
            drag.expectedTop = next;
            area.scroll.scrollTop = next;
            drag.expectedTop = area.scroll.scrollTop;
            if (area.field) syncFieldMirror(session);
            moved = Math.abs(area.scroll.scrollTop - before) > .01;
            if (moved) drag.edgeScrollElapsedSeconds += dt;
        }
        if (!delta || !moved) {
            // Once scrolling pauses, the next movement starts at base speed.
            drag.edgeScrollDirection = 0;
            drag.edgeScrollElapsedSeconds = 0;
        }
        var pt = fromXY(area, drag.x, drag.y);
        if (pt) {
            try {
                // Keep start <= end, but let the *finger-owned handle* pass the
                // stationary endpoint. It switches roles rather than collapsing
                // and disappearing when dragging a selection backwards.
                if (drag.side === 'start' && compare(pt, session.end) < 0) {
                    session.start = session.end;
                    session.end = pt;
                    swapHandleSides('end');
                } else if (drag.side === 'end' && compare(session.start, pt) < 0) {
                    session.end = session.start;
                    session.start = pt;
                    swapHandleSides('start');
                } else {
                    session[drag.side] = pt;
                }
            } catch (_) { /* Content re-rendered underneath us. */ }
        }
        draw();
        if (drag && delta && moved) frame = requestAnimationFrame(dragTick);
    }
    function updateDrag(e, kind) {
        if (!drag || drag.kind !== kind) return;
        if (kind === 'touch') {
            for (var i = 0; i < e.changedTouches.length; i++) {
                var t = e.changedTouches[i];
                if (t.identifier !== drag.id) continue;
                e.preventDefault();
                drag.x = t.clientX; drag.y = t.clientY;
                if (!frame) frame = requestAnimationFrame(dragTick);
                break;
            }
        } else if (e.pointerId === drag.id) {
            e.preventDefault();
            drag.x = e.clientX; drag.y = e.clientY;
            if (!frame) frame = requestAnimationFrame(dragTick);
        }
    }
    function endDrag(e, kind) {
        if (!drag || drag.kind !== kind) return;
        if (kind === 'touch') {
            var found = false;
            for (var i = 0; i < e.changedTouches.length; i++) {
                if (e.changedTouches[i].identifier === drag.id) found = true;
            }
            if (!found) return;
        } else if (e.pointerId !== drag.id) return;
        var previous = drag;
        drag = null;
        restoreDragScrollLock(previous);
        stopFrame();
        scheduleDraw();
        if (session) scheduleToolbarFor(session, 90);
    }
    function selectAll() {
        if (!session) return false;
        if (session.area.field) {
            session.start = {node:session.mirrorText,offset:0};
            session.end = {node:session.mirrorText,offset:session.mirrorText.length};
            session.lastMovedSide = null;
            draw(); showToolbar(true);
            return true;
        }
        var root = session.area.root;
        var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        var first = null, last = null, node;
        while ((node = walker.nextNode())) {
            if (!usableText(root, node)) continue;
            if (!first) first = {node:node, offset:0};
            last = {node:node, offset:node.length};
        }
        if (!first || !last) return false;
        session.start = first;
        session.end = last;
        session.lastMovedSide = null;
        draw();
        showToolbar(true);
        return true;
    }

    window.__kiTavernChatSelect = {
        probeAt:probeAndroid,
        longPressAt:androidLongPress,
        androidPressReleased:androidPressReleased,
        androidPressCanceled:androidPressCanceled,
        selectedText:function () {
            var range = selectedRange();
            return range ? range.toString() : '';
        },
        isEditableSelection:function () {
            return !!(session && session.area.field &&
                !session.area.field.readOnly && !session.area.field.disabled);
        },
        replaceSelection:function (value) {
            if (!session || !session.area.field || typeof value !== 'string') return false;
            var field = session.area.field;
            if (!field.isConnected || field.readOnly || field.disabled ||
                field.value !== session.mirrorText.data) return false;
            var start = session.start.offset, end = session.end.offset;
            // setRangeText changes the live field and retains a valid caret.
            // Dispatch input so SillyTavern/framework state receives the edit.
            closeSelection();
            field.setRangeText(value, start, end, 'end');
            try {
                field.dispatchEvent(new InputEvent('input', {bubbles:true,
                    inputType:value ? 'insertFromPaste' : 'deleteByCut', data:value}));
            } catch (_) { field.dispatchEvent(new Event('input', {bubbles:true})); }
            return true;
        },
        selectAll:selectAll,
        dismiss:closeSelection,
        scrollbarTouchStarted:scrollbarTouchStarted,
        scrollbarTouchFinished:scrollbarTouchFinished,
        status:function () {
            return {installed:true, version:5, active:!!session,
                correctedExternalScrolls:correctedExternalScrolls,
                chat:!!document.getElementById('chat')};
        }
    };

    document.addEventListener('touchstart', function (e) {
        if (e.touches.length !== 1) {
            activePressToken = 0; recentFieldTouch = null;
            stopPending(); tapCandidate = null; return;
        }
        activePressToken = ++touchSequence;
        if (drag || window.__kiTavernScrollbarDragging ||
            (e.target.closest && e.target.closest('.' + HANDLE_CLASS))) {
            recentFieldTouch = null;
            return;
        }
        stopPending();
        toolbarTouchStarted();
        var t = e.changedTouches[0];
        var area = eligible(e.target, t.clientX, t.clientY);
        // Only allow native-to-custom input handoff for a recent real touch;
        // programmatic select() calls made by SillyTavern must stay untouched.
        recentFieldTouch = area && area.field ?
            {field:area.field, id:t.identifier, time:performance.now(),
                x:t.clientX, y:t.clientY, releasedAt:null} : null;
        // Don't clear an existing selection on ACTION_DOWN: this gesture might
        // turn into scrolling. Only dismiss it after an actual tap (on release).
        tapCandidate = session ? {id:t.identifier, x:t.clientX, y:t.clientY} : null;
        if (!area) return;
        lock(area);
        pending = {area:area, id:t.identifier, x:t.clientX, y:t.clientY,
            time:performance.now(),
            fieldRect:area.field ? area.field.getBoundingClientRect() : null,
            timer:setTimeout(function () {
                if (!pending || !area.root.isConnected) return;
                var p = pressCoordinates(pending), id = pending.id;
                stopPending();
                if (!area.field || selectableFieldFor(area.field) === area.field)
                    beginSelection(area, p.x, p.y, id);
            }, 390)};
    }, {capture:true, passive:true});
    document.addEventListener('touchmove', function (e) {
        if (drag) { updateDrag(e, 'touch'); return; }
        if (tapCandidate) {
            for (var k = 0; k < e.changedTouches.length; k++) {
                var movedTouch = e.changedTouches[k];
                if (movedTouch.identifier !== tapCandidate.id) continue;
                if (Math.abs(movedTouch.clientX - tapCandidate.x) > 9 ||
                    Math.abs(movedTouch.clientY - tapCandidate.y) > 9)
                    tapCandidate = null;
                break;
            }
        }
        if (recentFieldTouch) {
            for (var k = 0; k < e.changedTouches.length; k++) {
                var moved = e.changedTouches[k];
                if (moved.identifier === recentFieldTouch.id &&
                    (Math.abs(moved.clientX - recentFieldTouch.x) > 9 ||
                     Math.abs(moved.clientY - recentFieldTouch.y) > 9)) {
                    recentFieldTouch = null;
                    break;
                }
            }
        }
        if (!pending) return;
        for (var i = 0; i < e.changedTouches.length; i++) {
            var t = e.changedTouches[i];
            if (t.identifier !== pending.id) continue;
            if (Math.abs(t.clientX - pending.x) > 9 ||
                Math.abs(t.clientY - pending.y) > 9) stopPending();
            break;
        }
    }, {capture:true, passive:false});
    document.addEventListener('touchend', function (e) {
        if (session && session.area.field && session.initiatingPressToken === activePressToken &&
            activePressToken && e.cancelable) {
            for (var nativeIndex = 0; nativeIndex < e.changedTouches.length; nativeIndex++) {
                if (e.changedTouches[nativeIndex].identifier === session.initiatingTouchId) {
                    e.preventDefault();
                    break;
                }
            }
        }
        if (session && session.waitingForRelease !== null &&
            session.waitingForRelease !== undefined) {
            for (var i = 0; i < e.changedTouches.length; i++) {
                if (session.waitingForRelease !== 'android' &&
                    e.changedTouches[i].identifier !== session.waitingForRelease) continue;
                var current = session;
                current.waitingForRelease = null;
                setTimeout(function () {
                    if (session === current && !drag) showToolbar(true);
                }, 140);
                break;
            }
        }
        endDrag(e, 'touch');
        toolbarTouchFinished();
        if (recentFieldTouch) {
            for (var k = 0; k < e.changedTouches.length; k++) {
                if (e.changedTouches[k].identifier === recentFieldTouch.id) {
                    if (performance.now() - recentFieldTouch.time < 300) recentFieldTouch = null;
                    else recentFieldTouch.releasedAt = performance.now();
                    break;
                }
            }
        }
        if (tapCandidate) {
            for (var j = 0; j < e.changedTouches.length; j++) {
                if (e.changedTouches[j].identifier === tapCandidate.id) {
                    tapCandidate = null;
                    closeSelection();
                    break;
                }
            }
        }
        if (pending) {
            for (var i = 0; i < e.changedTouches.length; i++) {
                if (e.changedTouches[i].identifier === pending.id) {
                    stopPending(); break;
                }
            }
        }
        if (!e.touches.length) activePressToken = 0;
    }, {capture:true, passive:false});
    document.addEventListener('touchcancel', function (e) {
        endDrag(e, 'touch'); toolbarTouchFinished();
        stopPending(); tapCandidate = null;
        recentFieldTouch = null; activePressToken = 0;
        if (session && session.waitingForRelease !== null) {
            session.waitingForRelease = null;
            scheduleToolbarFor(session, 140);
        }
    }, true);
    document.addEventListener('selectstart', function (e) {
        var target = e.target && (e.target.nodeType === 3 ? e.target.parentElement : e.target);
        var field = selectableFieldFor(target);
        if (field && e.cancelable &&
            ((pending && pending.area.field === field && performance.now() - pending.time >= 300) ||
             (session && session.area.field === field &&
              (!activePressToken || session.initiatingPressToken === activePressToken))))
            e.preventDefault();
    }, true);
    document.addEventListener('selectionchange', function () {
        if (session && session.area.field && !fieldSelectReentry &&
            !(recentFieldTouch && recentFieldTouch.field === session.area.field))
            collapseNativeFieldSelection(session.area.field);
    }, true);
    // If Chromium consumes a field long press before our timer, its native
    // selection still produces a `select` event on ordinary input controls.
    // Convert that one range to our own handles and clear Chromium's range.
    document.addEventListener('select', function (e) {
        if (fieldSelectReentry) return;
        var field = selectableFieldFor(e.target);
        if (!field) return;
        var press = recentFieldTouch;
        var age = press ? performance.now() - press.time : Infinity;
        var start = field.selectionStart, end = field.selectionEnd;
        // A new genuine long press replaces any previous custom selection,
        // even another selection in this same field. Ignore programmatic
        // select() calls and ordinary taps made while focusing an input.
        if (!press || press.field !== field || age < 300 || age > 2600 ||
            (press.releasedAt !== null && performance.now() - press.releasedAt > 250) ||
            typeof start !== 'number' || typeof end !== 'number' || end <= start) {
            if (session && session.area.field === field)
                collapseNativeFieldSelection(field);
            return;
        }
        stopPending();
        beginSelection({root:field,scroll:field,field:field,chat:false},
            press.x, press.y, press.releasedAt === null ? press.id : null, {start:start,end:end});
        if (session && session.area.field === field && press.releasedAt !== null)
            scheduleToolbarFor(session, 130);
    }, true);
    // Only one selection may be active. Even unsupported native editors (such
    // as passwords and rich-text controls) should dismiss a prior chat range
    // when focused; their actual editing/selection stays with Android.
    document.addEventListener('focusin', function (e) {
        var target = e.target;
        if (!session || !target || !target.matches ||
            !target.matches('input,textarea,select,[contenteditable],'+
                '[role="textbox"],[role="searchbox"]')) return;
        if (session.area.root !== target) closeSelection();
    }, true);
    // Wheel/trackpad input can scroll before Chromium sends a scroll event.
    document.addEventListener('wheel', function () {
        if (session && !drag) toolbarScrollMoved();
    }, {capture:true, passive:true});
    document.addEventListener('pointermove', function (e) { updateDrag(e, 'pointer'); }, true);
    document.addEventListener('pointerup', function (e) { endDrag(e, 'pointer'); }, true);
    document.addEventListener('pointercancel', function (e) { endDrag(e, 'pointer'); }, true);
    document.addEventListener('contextmenu', function (e) {
        if (e.target.closest &&
            (e.target.closest('.' + TOUCH_LOCK) ||
             e.target.closest('.' + HANDLE_CLASS) ||
              (session && session.area.field === selectableFieldFor(e.target))))
            e.preventDefault();
    }, true);
    window.addEventListener('scroll', function (event) {
        // The selected scroller has its own listener; don't process twice.
        if (session && event.target === session.area.scroll) return;
        if (drag) guardDragScroll();
        else {
            beginMotion();
            toolbarScrollMoved();
        }
    }, true);
    // Keyboard and visual-viewport transitions move selected content too.
    // Treat them like scrolling so the popup returns beside its new position.
    function viewportMoved() {
        beginMotion();
        toolbarScrollMoved();
    }
    window.addEventListener('resize', viewportMoved);
    if (window.visualViewport) {
        window.visualViewport.addEventListener('resize', viewportMoved);
        window.visualViewport.addEventListener('scroll', viewportMoved);
    }
    // A floating Android ActionMode can move window focus temporarily.
    // Losing focus must not discard the selected range or its handles.
    window.addEventListener('blur', stopPending);
    return 'selection-v5';
})();
