/* Chat-first touch text selection for Android WebView.
 * SillyTavern's #chat is preferred when present. On other sites, use the nearest
 * scrollable text container, or the main/article area of the document.
 * Uses Android's floating ActionMode through a tiny Kotlin bridge; no extra engine.
 */
(function () {
    'use strict';
    if (window.__kiTavernChatHandlesInstalled) return 'already installed';
    window.__kiTavernChatHandlesInstalled = true;

    var STYLE = '__kiChatSelectionCSS';
    var TOUCH_LOCK = '__kiChatSelectionTouchLock';
    var HANDLE_CLASS = 'ki-chat-select-control';
    var style = document.createElement('style');
    style.id = STYLE;
    style.textContent =
        // Disable native selection only inside the current selection area,
        // and only during a long press or custom selection. Editable fields
        // retain their normal Android/WebView behaviour.
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
        '#__kiChatSelectOverlay{position:fixed;inset:0;z-index:2147483640;' +
        'pointer-events:none;font-family:Roboto,Arial,sans-serif;}' +
        '#__kiChatSelectOverlay .ki-chat-select-mark{position:fixed;' +
        'background:rgba(33,150,243,.30);pointer-events:none;border-radius:1px;}' +
        '#__kiChatSelectOverlay .ki-chat-select-handle{position:fixed;' +
        'width:34px;height:44px;margin-left:-17px;touch-action:none;' +
        'pointer-events:auto;-webkit-tap-highlight-color:transparent;}' +
        '#__kiChatSelectOverlay .ki-chat-select-handle::before{' +
        'content:"";position:absolute;left:16px;top:0;width:2px;height:23px;' +
        'background:#2196f3;border-radius:2px;}' +
        '#__kiChatSelectOverlay .ki-chat-select-handle::after{' +
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
    var toolbarRepositionTimer = null;
    var lastToolbarUpdate = 0;
    var correctedExternalScrolls = 0;

    // Start at 850 px/s, reaching 2048 px/s after 2 seconds.
    // Then double smoothly every 2 seconds without a fixed maximum.
    // Leaving the edge, reversing, reaching an end or releasing resets it.
    var SELECTION_SCROLL_START_PX_PER_SECOND = 850;
    var SELECTION_SCROLL_MAX_PX_PER_SECOND = 2048; // Speed at 2 seconds
    var SELECTION_SCROLL_RAMP_SECONDS = 2.0;
    var SELECTION_SCROLL_LONG_HOLD_MULTIPLIER = 2.0;
    var SELECTION_SCROLL_LONG_HOLD_INTERVAL_SECONDS = 2.0;

    var DOCUMENT_SCROLL = document.scrollingElement || document.documentElement;
    var EXCLUDE = 'a,button,input,textarea,select,option,label,summary,' +
        '[contenteditable],[role="button"],[role="textbox"],[role="link"],' +
        '[aria-hidden="true"],[inert],.mes_buttons,.mes_edit_buttons,' +
        '.code-copy,.ki-chat-select-control';

    function inside(root, node) { return !!(root && node && root.contains(node)); }
    function canScroll(el) {
        if (!el || el === document.body || el === document.documentElement) return false;
        var css = getComputedStyle(el);
        return (css.overflowY === 'auto' || css.overflowY === 'scroll') &&
            el.scrollHeight > el.clientHeight + 2 && el.clientHeight > 20;
    }
    function areaFor(target) {
        if (!target || !target.closest || target.closest(EXCLUDE)) return null;
        // Do not replace custom selection or editor widgets with kiosk handles.
        if (target.closest('nav,header,footer,[role="navigation"],svg,canvas')) return null;
        var chat = document.getElementById('chat');
        if (inside(chat, target)) return {root:chat, scroll:chat, chat:true};
        var scroll = null;
        for (var el = target; el && el !== document.body; el = el.parentElement) {
            if (canScroll(el)) { scroll = el; break; }
        }
        if (scroll) return {root:scroll, scroll:scroll, chat:false};
        // On regular pages, prefer main/article text over the entire webpage;
        // don't let selection wander into navigation or a fixed composer.
        var root = target.closest('main,[role="main"]') ||
            target.closest('article,[role="article"],[itemprop="articleBody"]') ||
            document.body;
        if (!root || root === document.documentElement) return null;
        return {root:root, scroll:DOCUMENT_SCROLL, chat:false};
    }
    function viewportBox(area) {
        var vv = window.visualViewport;
        var left = vv ? vv.offsetLeft : 0;
        var top = vv ? vv.offsetTop : 0;
        var right = vv ? left + vv.width : window.innerWidth;
        var bottom = vv ? top + vv.height : window.innerHeight;
        // Intersect with the selected container and every clipping ancestor.
        // Document scrolling has a viewport, not a usable document-element rect.
        var el = area.root;
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
    function nativeToolbar() {
        return window.KiTavernSelectionToolbar || null;
    }
    function hideToolbar() {
        if (toolbarTimer) { clearTimeout(toolbarTimer); toolbarTimer = null; }
        if (toolbarRepositionTimer) {
            clearTimeout(toolbarRepositionTimer);
            toolbarRepositionTimer = null;
        }
        if (session) session.nativeToolbarVisible = false;
        var bridge = nativeToolbar();
        if (bridge) bridge.hide();
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
        if (!session || drag || session.waitingForRelease !== null) return;
        var bridge = nativeToolbar();
        if (!bridge) return;
        var now = performance.now();
        if (!force && now - lastToolbarUpdate < 95) return;
        var r = selectionAnchor();
        if (!r) { hideToolbar(); return; }
        lastToolbarUpdate = now;
        session.nativeToolbarVisible = true;
        bridge.show(r.left, r.top, r.right, r.bottom,
            window.innerWidth, window.innerHeight);
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
        hideToolbar();
        stopFrame();
        restoreDragScrollLock(drag);
        drag = null;
        if (!session) return;
        var previous = session;
        previous.area.scroll.removeEventListener('scroll', onSelectionScroll);
        if (previous.overlay.parentNode) previous.overlay.remove();
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
    function fromXY(area, x, y) {
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
    function buildOverlay() {
        var overlay = document.createElement('div');
        overlay.id = '__kiChatSelectOverlay';
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
        document.documentElement.appendChild(overlay);
        startHandle.addEventListener('touchstart', beginHandle, {passive:false});
        endHandle.addEventListener('touchstart', beginHandle, {passive:false});
        startHandle.addEventListener('pointerdown', beginMouseHandle);
        endHandle.addEventListener('pointerdown', beginMouseHandle);
        return {overlay: overlay, marks: marks, handles: [startHandle, endHandle]};
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
    function probeAndroid(screenX, screenY, physicalWidth, physicalHeight) {
        var p = androidPoint(screenX, screenY, physicalWidth, physicalHeight);
        if (!p) return false;
        var target = document.elementFromPoint(p.x, p.y);
        return !!(target && eligible(target, p.x, p.y));
    }
    // Android's own long-click gesture takes precedence over a native
    // Chromium selection, but only if the ACTION_DOWN preflight succeeded.
    function androidLongPress(screenX, screenY, physicalWidth, physicalHeight) {
        if (!physicalWidth || !physicalHeight) return false;
        if (session) return true;
        var p = androidPoint(screenX, screenY, physicalWidth, physicalHeight);
        if (!p) return false;
        var x = p.x, y = p.y;
        var target = document.elementFromPoint(x, y);
        var area = target && eligible(target, x, y);
        if (!area) return false;
        stopPending();
        beginSelection(area, x, y, null);
        // The original finger may still be down. Show the toolbar after
        // release (or after a short fallback if WebView eats touchend).
        if (session) {
            session.waitingForRelease = 'android';
            return true;
        }
        return false;
    }

    // Android's own long-click can consume touchend before it reaches the page.
    // In that case MainActivity reports ACTION_UP explicitly. Never open the
    // native toolbar while the initiating finger is still touching the screen.
    function androidPressReleased() {
        if (!session || session.waitingForRelease !== 'android') return;
        session.waitingForRelease = null;
        scheduleToolbarFor(session, 140);
    }
    function androidPressCanceled() {
        if (session && session.waitingForRelease === 'android') {
            session.waitingForRelease = null;
        }
    }

    function beginSelection(area, x, y, touchId) {
        var pair = wordAt(area, x, y);
        if (!pair) return;
        closeSelection();
        tapCandidate = null;
        var ui = buildOverlay();
        session = Object.assign({area:area, start:pair.start, end:pair.end,
            waitingForRelease:touchId, nativeToolbarVisible:false}, ui);
        // Don't show the toolbar underneath the initiating long-press finger.
        // The native toolbar appears after the initiating press is released.
        area.scroll.addEventListener('scroll', onSelectionScroll, {passive:true});
        lock(area);
        scheduleDraw();
    }
    function showHandle(handle, point, box) {
        var rect = pointRect(point);
        if (!rect || rect.bottom < box.top || rect.top > box.bottom ||
            rect.right < box.left || rect.left > box.right) {
            handle.style.display = 'none';
            return;
        }
        handle.style.display = 'block';
        handle.style.left = Math.max(box.left + 17,
            Math.min(box.right - 17, rect.left)) + 'px';
        handle.style.top = Math.max(box.top,
            Math.min(box.bottom - 40, rect.bottom - 2)) + 'px';
    }
    function draw() {
        if (!session) return;
        var area = session.area;
        if (!area.root.isConnected || !area.scroll.isConnected ||
            !inside(area.root, session.start.node) ||
            !inside(area.root, session.end.node)) {
            closeSelection();
            return;
        }
        var r = selectedRange();
        if (!r) { closeSelection(); return; }
        var box = viewportBox(area);
        if (box.width < 20 || box.height < 20) { closeSelection(); return; }
        var rectangles = r.getClientRects();
        var frag = document.createDocumentFragment();
        var count = 0;
        for (var i = 0; i < rectangles.length && count < 220; i++) {
            var item = rectangles[i];
            if (!item.width || !item.height || item.bottom <= box.top ||
                item.top >= box.bottom || item.right <= box.left || item.left >= box.right) continue;
            var left = Math.max(box.left, item.left), top = Math.max(box.top, item.top);
            var right = Math.min(box.right, item.right);
            var bottom = Math.min(box.bottom, item.bottom);
            if (right <= left || bottom <= top) continue;
            var mark = make('div', 'ki-chat-select-mark');
            mark.style.left = left + 'px';
            mark.style.top = top + 'px';
            mark.style.width = (right - left) + 'px';
            mark.style.height = (bottom - top) + 'px';
            frag.appendChild(mark);
            count++;
        }
        session.marks.replaceChildren(frag);
        showHandle(session.handles[0], session.start, box);
        showHandle(session.handles[1], session.end, box);
        // The native toolbar stays anchored during normal scrolling; moving its
        // content rect every animation frame can disrupt WebView touch handling.
    }
    function scheduleDraw() {
        // The drag loop already draws every frame while scrolling.
        if (drag) return;
        if (session && !frame) frame = requestAnimationFrame(function () {
            frame = 0;
            draw();
        });
    }
    function onSelectionScroll() {
        if (drag) guardDragScroll();
        scheduleDraw();
        if (!session || drag || !session.nativeToolbarVisible) return;
        // Only reposition a native popup after a scroll gesture settles.
        // Updating ActionMode every frame was disrupting selection gestures.
        if (toolbarRepositionTimer) clearTimeout(toolbarRepositionTimer);
        toolbarRepositionTimer = setTimeout(function () {
            toolbarRepositionTimer = null;
            if (session && !drag && session.nativeToolbarVisible) showToolbar(true);
        }, 250);
    }
    function beginHandle(e) {
        if (!session || e.touches.length !== 1) return;
        e.preventDefault();
        e.stopPropagation();
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
        hideToolbar();
        session.lastMovedSide = e.currentTarget.dataset.side;
        drag = {side:e.currentTarget.dataset.side, id:e.pointerId,
            kind:'pointer', x:e.clientX, y:e.clientY};
        try { e.currentTarget.setPointerCapture(e.pointerId); } catch (_) {}
        stopFrame();
        beginDragScrollLock();
        frame = requestAnimationFrame(dragTick);
    }
    function dragTick(now) {
        frame = 0;
        if (!drag || !session) return;
        var area = session.area;
        guardDragScroll();
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
            // Stage 1: gentle acceleration to 2048 after 2 seconds.
            // Stage 2: smoothly double speed every additional 2 seconds.
            var elapsed = drag.edgeScrollElapsedSeconds;
            var speedMagnitude;
            if (elapsed <= SELECTION_SCROLL_RAMP_SECONDS) {
                var progress = Math.min(1, elapsed / SELECTION_SCROLL_RAMP_SECONDS);
                var eased = progress * progress * (3 - 2 * progress);
                speedMagnitude = SELECTION_SCROLL_START_PX_PER_SECOND +
                    (SELECTION_SCROLL_MAX_PX_PER_SECOND -
                        SELECTION_SCROLL_START_PX_PER_SECOND) * eased;
            } else {
                var holdIntervals = (elapsed - SELECTION_SCROLL_RAMP_SECONDS) /
                    SELECTION_SCROLL_LONG_HOLD_INTERVAL_SECONDS;
                speedMagnitude = SELECTION_SCROLL_MAX_PX_PER_SECOND *
                    Math.pow(SELECTION_SCROLL_LONG_HOLD_MULTIPLIER, holdIntervals);
            }
            var speed = direction * speedMagnitude;
            var before = area.scroll.scrollTop;
            var next = Math.max(0, Math.min(
                area.scroll.scrollHeight - area.scroll.clientHeight,
                before + speed * dt));
            drag.expectedTop = next;
            area.scroll.scrollTop = next;
            drag.expectedTop = area.scroll.scrollTop;
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
                if (drag.side === 'start' && compare(pt, session.end) < 0) {
                    pt = session.end;
                } else if (drag.side === 'end' && compare(session.start, pt) < 0) {
                    pt = session.start;
                }
                session[drag.side] = pt;
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
        selectAll:selectAll,
        dismiss:closeSelection,
        status:function () {
            return {installed:true, version:4, active:!!session,
                correctedExternalScrolls:correctedExternalScrolls,
                chat:!!document.getElementById('chat')};
        }
    };

    document.addEventListener('touchstart', function (e) {
        if (e.touches.length !== 1) { stopPending(); tapCandidate = null; return; }
        if (drag || (e.target.closest && e.target.closest('.' + HANDLE_CLASS))) return;
        stopPending();
        var t = e.changedTouches[0];
        var area = eligible(e.target, t.clientX, t.clientY);
        // Don't clear an existing selection on ACTION_DOWN: this gesture might
        // turn into scrolling. Only dismiss it after an actual tap (on release).
        tapCandidate = session ? {id:t.identifier, x:t.clientX, y:t.clientY} : null;
        if (!area) return;
        lock(area);
        pending = {area:area, id:t.identifier, x:t.clientX, y:t.clientY,
            timer:setTimeout(function () {
                if (!pending || !area.root.isConnected) return;
                var x = pending.x, y = pending.y, id = pending.id;
                stopPending();
                beginSelection(area, x, y, id);
            }, 390)};
    }, {capture:true, passive:true});
    document.addEventListener('touchmove', function (e) {
        if (drag) { updateDrag(e, 'touch'); return; }
        if (tapCandidate) {
            for (var k = 0; k < e.changedTouches.length; k++) {
                var movedTouch = e.changedTouches[k];
                if (movedTouch.identifier === tapCandidate.id &&
                    (Math.abs(movedTouch.clientX - tapCandidate.x) > 9 ||
                     Math.abs(movedTouch.clientY - tapCandidate.y) > 9)) {
                    tapCandidate = null;
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
    }, true);
    document.addEventListener('touchcancel', function (e) {
        endDrag(e, 'touch'); stopPending(); tapCandidate = null;
    }, true);
    document.addEventListener('pointermove', function (e) { updateDrag(e, 'pointer'); }, true);
    document.addEventListener('pointerup', function (e) { endDrag(e, 'pointer'); }, true);
    document.addEventListener('pointercancel', function (e) { endDrag(e, 'pointer'); }, true);
    document.addEventListener('contextmenu', function (e) {
        if (e.target.closest &&
            (e.target.closest('.' + TOUCH_LOCK) ||
             e.target.closest('.' + HANDLE_CLASS))) e.preventDefault();
    }, true);
    window.addEventListener('scroll', function () {
        if (drag) guardDragScroll();
        scheduleDraw();
    }, true);
    window.addEventListener('resize', scheduleDraw);
    if (window.visualViewport) {
        window.visualViewport.addEventListener('resize', scheduleDraw);
        window.visualViewport.addEventListener('scroll', scheduleDraw);
    }
    // A floating Android ActionMode can move window focus temporarily.
    // Losing focus must not discard the selected range or its handles.
    window.addEventListener('blur', stopPending);
    return 'selection-v3';
})();
