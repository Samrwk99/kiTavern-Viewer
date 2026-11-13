/* kiTavern: EXPERIMENTAL Chromium native selection-handle scroll bridge.
 * Wrapper-only runtime injection. SillyTavern assets are never modified.
 * Native Android selection handles remain owned by Chromium.
 * Native range updates are preserved; a guarded re-grab recovery repairs
 * the fixed DOM endpoint only if Chromium moves it incorrectly.
 *
 * One selection system: Chromium's. The native side assists a handle drag
 * only when begin() below confirms, at grab time, that the live selection is
 * page text inside one scroll region: #chat, or a settings/drawer panel. It
 * then keeps the handle inside that region and scrolls it at the edges.
 * Selections inside text fields, and anything else, stay fully native.
 */
(function () {
    'use strict';
    var chat = document.getElementById('chat');
    if (!chat || !window.STSelectionBridge) return 'not SillyTavern chat';
    if (window.__kiTavernHandleBridgeInstalled) {
        window.__kiTavernSelectionReport && window.__kiTavernSelectionReport();
        return 'already installed';
    }
    window.__kiTavernHandleBridgeInstalled = true;
    var style = document.getElementById('__kiTavernNativeSelectionBoundaries');
    if (!style) {
        style = document.createElement('style');
        style.id = '__kiTavernNativeSelectionBoundaries';
        (document.head || document.documentElement).appendChild(style);
    }
    style.textContent = [
        // Make gap/hit-test targets inside messages selectable too. Do not
        // impose user-select on settings, extensions, or the composer.
        '#chat, #chat .mes, #chat .mes_block, #chat .mes_text,',
        '#chat .mes_text *, #chat .mes_reasoning {',
        ' -webkit-user-select:text!important; user-select:text!important; }',
        // Keep message controls out of the text-selection path.
        '#chat .mes_buttons, #chat .mes_buttons *,',
        '#chat .mesAvatarWrapper, #chat .mesAvatarWrapper *,',
        '#chat .mes_edit_buttons, #chat .mes_edit_buttons * {',
        ' -webkit-user-select:none!important; user-select:none!important; }',
        // Temporary hit-test shielding only while dragging a *chat* handle.
        'body.st-handle-dragging #form_sheld,',
        'body.st-handle-dragging #top-bar,',
        'body.st-handle-dragging #top-settings-holder,',
        'body.st-handle-dragging #chat .mes_buttons,',
        'body.st-handle-dragging #chat .mesAvatarWrapper,',
        'body.st-handle-dragging #chat .ch_name {',
        ' pointer-events:none!important; }'
    ].join('\n');

    var subpixelRemainder = 0;
    var lastReportTime = 0;
    // True while Chromium natively drags a handle of an unassisted selection.
    var nativeHandleActive = false;
    // The scroll region of the assisted drag in progress (#chat or a panel),
    // and the region of the current selection used for geometry reports.
    var activeRegion = null;
    var reportRegion = null;

    function inNode(root, node) {
        return !!(root && node && (node === root || root.contains(node)));
    }
    function inChat(node) { return inNode(chat, node); }
    function inRegion(node) { return inNode(activeRegion, node); }
    function scrollsY(el) {
        return el.scrollHeight > el.clientHeight + 1 &&
            /(auto|scroll|overlay)/.test(getComputedStyle(el).overflowY);
    }
    // Page text selected inside one scroll region. Selections in text fields
    // are reported collapsed by Chromium and are never assisted.
    function selectionRegion(s) {
        if (!s || s.rangeCount !== 1 || s.isCollapsed) return null;
        if (inChat(s.anchorNode) && inChat(s.focusNode)) return chat;
        var n = s.getRangeAt(0).commonAncestorContainer;
        if (n && n.nodeType !== 1) n = n.parentElement;
        var shell = document.getElementById('sheld');
        for (; n && n !== document.body && n !== document.documentElement; n = n.parentElement) {
            if (n.isContentEditable || n === shell) return null;
            if (scrollsY(n)) return n;
        }
        return null;
    }
    // The part of a region that is actually on screen.
    function visibleRect(el) {
        var r = el.getBoundingClientRect();
        var box = {left: Math.max(0, r.left), top: Math.max(0, r.top),
            right: Math.min(innerWidth, r.right), bottom: Math.min(innerHeight, r.bottom)};
        if (el !== chat) {
            for (var p = el.parentElement; p && p !== document.body; p = p.parentElement) {
                var cs = getComputedStyle(p);
                if (cs.overflowX === 'visible' && cs.overflowY === 'visible') continue;
                var pr = p.getBoundingClientRect();
                box.left = Math.max(box.left, pr.left); box.top = Math.max(box.top, pr.top);
                box.right = Math.min(box.right, pr.right); box.bottom = Math.min(box.bottom, pr.bottom);
            }
        }
        return box;
    }
    function chatDragging() {
        return !!(document.body && document.body.classList.contains('st-handle-dragging'));
    }
    function positionCmp(a, ao, b, bo) {
        try {
            var x = document.createRange(), y = document.createRange();
            x.setStart(a, ao); x.collapse(true);
            y.setStart(b, bo); y.collapse(true);
            return x.compareBoundaryPoints(Range.START_TO_START, y);
        } catch (_) { return null; }
    }
    function report(force) {
        if (!document.getElementById('chat') || !window.STSelectionBridge) return;
        var now = performance.now();
        if (!force && now - lastReportTime < 24) return;
        lastReportTime = now;
        var s = window.getSelection();
        var anySelection = !!(s && s.rangeCount === 1 && !s.isCollapsed);
        var region = activeRegion || (anySelection ? selectionRegion(s) : null);
        reportRegion = region;
        var geometry = region || chat;
        var r = visibleRect(geometry);
        // Convert CSS viewport positions into Android WebView coordinates on
        // the native side using innerWidth/innerHeight (not fixed dp scales).
        window.STSelectionBridge.onSelectionStateChanged(
            !!region, anySelection, geometry === chat, r.left, r.top, r.right, r.bottom,
            Math.max(1, window.innerWidth), Math.max(1, window.innerHeight),
            geometry.scrollTop > 0.5,
            geometry.scrollTop + geometry.clientHeight < geometry.scrollHeight - 0.5
        );
    }
    window.__kiTavernSelectionReport = function () { report(true); };

    function setHandleDragging(isDragging) {
        subpixelRemainder = 0;
        var endedRegion = activeRegion;
        if (!isDragging && window.__stSelectionAnchorLock) {
            window.__stSelectionAnchorLock.end(); // clears activeRegion
        }
        // Hit-test shielding is for chat drags only: in a settings panel it
        // would block the very text being selected.
        document.body && document.body.classList.toggle('st-handle-dragging',
            !!isDragging && activeRegion === chat);
        report(true);
        if (!isDragging) {
            // Chromium applies the final MOVE/UP asynchronously to this call.
            setTimeout(confineChatSelection, 90);
            setTimeout(confineChatSelection, 260);
            if (endedRegion && endedRegion !== chat) {
                setTimeout(fixFieldBoundaries, 90);
                setTimeout(fixFieldBoundaries, 260);
            }
        }
    }
    window.__stSetHandleDragging = setHandleDragging;

    window.__stAutoScrollStep = function (deltaCssPixels, pointX, pointY) {
        if (window.__stSelectionAnchorLock && Number.isFinite(pointX) && Number.isFinite(pointY)) {
            window.__stSelectionAnchorLock.point(pointX, pointY);
        }
        if (!activeRegion || !Number.isFinite(deltaCssPixels) ||
            Math.abs(deltaCssPixels) > 100) return;
        subpixelRemainder += deltaCssPixels;
        var step = Math.trunc(subpixelRemainder);
        if (step) {
            subpixelRemainder -= step;
            activeRegion.scrollTop += step;
        }
        report(true);
    };

    // ---- Keep a selection that starts in the chat inside the chat. ---------
    // The native clamp keeps the dragged handle over #chat, but its exact
    // hit-test point depends on the WebView version, and a long-press drag is
    // not clamped at all. This is the backstop: if a selection anchored in
    // #chat extends into the composer/top bar, pull its moving end back to
    // the visible edge of the chat.
    function edgeText(root, last) {
        var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
            acceptNode: function (n) {
                if (!/\S/.test(n.nodeValue)) return NodeFilter.FILTER_SKIP;
                var p = n.parentElement;
                if (!p || p.closest('.mes_buttons, .mesAvatarWrapper, .mes_edit_buttons, ' +
                    'textarea, script, style')) return NodeFilter.FILTER_SKIP;
                return NodeFilter.FILTER_ACCEPT;
            }
        });
        if (!last) return walker.nextNode();
        var found = null, n;
        while ((n = walker.nextNode())) found = n;
        return found;
    }
    // The message at the visible top/bottom edge of #chat, found by layout
    // alone (so an overlapping drawer or popup cannot redirect it).
    function edgeMessage(atBottom, r) {
        var pick = null;
        for (var el = chat.firstElementChild; el; el = el.nextElementSibling) {
            var mr = el.getBoundingClientRect();
            if (mr.height <= 0) continue;
            if (atBottom) {
                if (mr.top < r.bottom) pick = el; else break;
            } else if (mr.bottom > r.top) {
                return el;
            }
        }
        return pick;
    }
    function chatEdgeCaret(atBottom, preferredX) {
        var r = chat.getBoundingClientRect();
        if (r.height < 20 || r.width < 20) return null;
        var xs = [preferredX, r.left + r.width / 2, r.left + 32];
        if (document.caretRangeFromPoint) {
            for (var step = 0; step < 5; step++) {
                var y = atBottom ? r.bottom - 6 - step * 14 : r.top + 6 + step * 14;
                for (var i = 0; i < xs.length; i++) {
                    if (!Number.isFinite(xs[i])) continue;
                    var x = Math.max(r.left + 4, Math.min(r.right - 4, xs[i]));
                    var c = document.caretRangeFromPoint(x, y);
                    if (c && c.startContainer !== chat && inChat(c.startContainer)) {
                        return {node: c.startContainer, offset: c.startOffset};
                    }
                }
            }
        }
        var message = edgeMessage(atBottom, r);
        var t = (message && edgeText(message, atBottom)) || edgeText(chat, atBottom);
        if (!t) return null;
        return {node: t, offset: atBottom ? t.nodeValue.length : 0};
    }
    var lastConfine = -Infinity;
    var confineTimer = 0;
    var confineQueued = false;
    function confineChatSelection() {
        var s = window.getSelection();
        if (!s || s.rangeCount !== 1 || s.isCollapsed) return;
        // Only a selection whose fixed end (the anchor; Chromium always makes
        // the dragged handle the focus) is in #chat. Selections made in the
        // settings panels are never moved into or out of the chat.
        if (!inChat(s.anchorNode) || inChat(s.focusNode)) return;
        var now = performance.now();
        if (now - lastConfine < 45) {
            if (!confineTimer) confineTimer = setTimeout(function () {
                confineTimer = 0;
                confineChatSelection();
            }, 50);
            return;
        }
        var order = positionCmp(s.focusNode, s.focusOffset, chat, 0);
        if (order === null) return;
        var lock = window.__stSelectionAnchorLock;
        var target = chatEdgeCaret(order > 0, lock ? lock.lastX() : NaN);
        if (!target) return;
        if (positionCmp(s.anchorNode, s.anchorOffset, target.node, target.offset) === 0) return;
        lastConfine = now;
        try {
            s.setBaseAndExtent(s.anchorNode, s.anchorOffset, target.node, target.offset);
        } catch (_) { }
    }
    function scheduleConfine() {
        if (confineQueued) return;
        confineQueued = true;
        requestAnimationFrame(function () {
            confineQueued = false;
            confineChatSelection();
        });
    }

    // ---- Settings: endpoints that land beside a form field. ----------------
    // A selection endpoint placed right before/after a textarea, input or
    // select gets a caret as tall as the whole field, so Chromium draws its
    // handle at the field's bottom edge: the marker "snaps down" onto the
    // field and the next grab starts from there. After a native drag ends,
    // move such an endpoint onto the neighbouring text. It never adds or
    // removes any field or text from the selection, only whitespace.
    var FIELD_SELECTOR = 'input, textarea, select, button';
    var MEDIA_SELECTOR = 'img, iframe, video, canvas, [contenteditable=""], [contenteditable="true"]';
    function meaningful(n, forward) {
        while (n && ((n.nodeType === 3 && !/\S/.test(n.nodeValue)) || n.nodeType === 8)) {
            n = forward ? n.nextSibling : n.previousSibling;
        }
        return n;
    }
    function boundaryField(n, forward) {
        for (var depth = 0; depth < 5; depth++) {
            n = meaningful(n, forward);
            if (!n || n.nodeType !== 1) return null;
            if (n.matches(FIELD_SELECTOR)) {
                return n.getBoundingClientRect().height > 0 ? n : null;
            }
            if (n.matches(MEDIA_SELECTOR)) {
                return n.getBoundingClientRect().height > 36 ? n : null;
            }
            n = forward ? n.firstChild : n.lastChild;
        }
        return null;
    }
    function selectableText(n) {
        if (!/\S/.test(n.nodeValue)) return false;
        var p = n.parentElement;
        if (!p || p.closest('textarea, select, option, button, script, style, noscript')) return false;
        var cs = getComputedStyle(p);
        if (cs.visibility === 'hidden' || cs.userSelect === 'none' ||
            cs.webkitUserSelect === 'none') return false;
        var range = document.createRange();
        range.selectNodeContents(n);
        var rects = range.getClientRects();
        return rects.length > 0 && rects[0].height > 0;
    }
    function textBeside(field, forward) {
        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        walker.currentNode = field;
        for (var i = 0; i < 300; i++) {
            var n = forward ? walker.nextNode() : walker.previousNode();
            if (!n) return null;
            if (field.contains(n) || !selectableText(n)) continue;
            var v = n.nodeValue;
            return forward ? {node: n, offset: Math.max(0, v.search(/\S/))}
                           : {node: n, offset: v.replace(/\s+$/, '').length};
        }
        return null;
    }
    function adjustEndpoint(node, offset) {
        if (!node || node.nodeType !== 1) return null;
        var before = offset > 0 ? node.childNodes[offset - 1] : null;
        var after = node.childNodes[offset] || null;
        var field = before && boundaryField(before, false);
        if (field) return textBeside(field, true);
        field = after && boundaryField(after, true);
        if (field) return textBeside(field, false);
        return null;
    }
    function fixFieldBoundaries() {
        if (nativeHandleActive || chatDragging()) return;
        var s = window.getSelection();
        if (!s || s.rangeCount !== 1 || s.isCollapsed) return;
        if (inChat(s.anchorNode) && inChat(s.focusNode)) return;
        var a = adjustEndpoint(s.anchorNode, s.anchorOffset);
        var f = adjustEndpoint(s.focusNode, s.focusOffset);
        if (!a && !f) return;
        var na = a || {node: s.anchorNode, offset: s.anchorOffset};
        var nf = f || {node: s.focusNode, offset: s.focusOffset};
        var oldOrder = positionCmp(s.anchorNode, s.anchorOffset, s.focusNode, s.focusOffset);
        var newOrder = positionCmp(na.node, na.offset, nf.node, nf.offset);
        // Never collapse or reverse the user's selection.
        if (!oldOrder || !newOrder || (oldOrder < 0) !== (newOrder < 0)) return;
        try { s.setBaseAndExtent(na.node, na.offset, nf.node, nf.offset); } catch (_) { }
    }
    window.__stNativeHandleActive = function (active) {
        nativeHandleActive = !!active;
        if (!nativeHandleActive) {
            setTimeout(fixFieldBoundaries, 90);
            setTimeout(fixFieldBoundaries, 260);
        }
    };

    // The DOM endpoint stays valid even when its native handle is far outside
    // the clipped #chat viewport. Chromium re-hit-tests both viewport handle
    // coordinates on each new drag, which can dislodge this fixed endpoint.
    // Capture before first MOVE; restore only if that endpoint changes.
    window.__stSelectionAnchorLock = (function () {
        var lockedNode = null, lockedOffset = 0, role = null;
        var lastPointX = NaN, lastPointY = NaN;
        var repairs = 0, lastRepair = -Infinity, repairing = false;

        function endpointRect(node, offset) {
            try {
                var r = document.createRange();
                r.setStart(node, offset);
                r.collapse(true);
                return r.getBoundingClientRect();
            } catch (_) { return null; }
        }
        function same(node, offset) {
            return node === lockedNode && offset === lockedOffset;
        }
        function stop() {
            activeRegion = null;
            lockedNode = null; lockedOffset = 0; role = null;
            repairing = false; repairs = 0;
            lastPointX = NaN; lastPointY = NaN;
        }
        function repairIfNeeded() {
            if (!role || repairing || !lockedNode || !lockedNode.isConnected ||
                !inRegion(lockedNode)) return;
            var sel = window.getSelection();
            if (!sel || !sel.rangeCount) return;
            var range = sel.getRangeAt(0);
            var fixedNode = role === 'upper' ? range.endContainer : range.startContainer;
            var fixedOffset = role === 'upper' ? range.endOffset : range.startOffset;
            if (same(fixedNode, fixedOffset)) return; // No rewriting during normal dragging.
            // If Chromium swapped the other endpoint at re-grab, recover the
            // moving caret from the *clamped* in-chat point rather than its
            // possibly corrupted native selection coordinates.
            var movingNode = null, movingOffset = 0;
            if (Number.isFinite(lastPointX) && Number.isFinite(lastPointY)) {
                var rect = visibleRect(activeRegion);
                var x = Math.max(rect.left + 3, Math.min(rect.right - 3, lastPointX));
                var y = Math.max(rect.top + 3, Math.min(rect.bottom - 3, lastPointY));
                var caret = document.caretRangeFromPoint && document.caretRangeFromPoint(x, y);
                if (caret && inRegion(caret.startContainer)) {
                    movingNode = caret.startContainer;
                    movingOffset = caret.startOffset;
                }
            }
            if (!movingNode) {
                movingNode = role === 'upper' ? range.startContainer : range.endContainer;
                movingOffset = role === 'upper' ? range.startOffset : range.endOffset;
            }
            if (!inRegion(movingNode)) return;
            var order = positionCmp(movingNode, movingOffset, lockedNode, lockedOffset);
            if (order === null || (role === 'upper' && order >= 0) ||
                (role === 'lower' && order <= 0)) return;
            // Avoid an endless native/JS selection tug-of-war in an unsupported
            // Chromium version: apply at most 12 targeted repairs per drag.
            var now = performance.now();
            if (repairs >= 12 || now - lastRepair < 48) return;
            repairing = true;
            try {
                sel.setBaseAndExtent(lockedNode, lockedOffset, movingNode, movingOffset);
                repairs++;
                lastRepair = now;
            } catch (_) {
                stop();
            } finally {
                repairing = false;
            }
        }
        return {
            // Called by the native side when a handle is grabbed. Returns
            // 'upper'/'lower' for a selection in #chat, 'upper:panel'/
            // 'lower:panel' for one in a settings panel; any other answer
            // makes the native side leave the drag entirely to Chromium.
            begin: function (x, y) {
                stop();
                var sel = window.getSelection();
                if (!sel || sel.isCollapsed || sel.rangeCount !== 1) return 'none';
                var region = selectionRegion(sel);
                if (!region) return 'outside';
                var r = sel.getRangeAt(0);
                var a = endpointRect(r.startContainer, r.startOffset);
                var b = endpointRect(r.endContainer, r.endOffset);
                if (!a || !b || !Number.isFinite(y)) return 'no-rect';
                // Pick the DOM endpoint nearest the real popup handle tip.
                var upperDistance = Math.abs(a.bottom - y);
                var lowerDistance = Math.abs(b.bottom - y);
                role = upperDistance <= lowerDistance ? 'upper' : 'lower';
                lockedNode = role === 'upper' ? r.endContainer : r.startContainer;
                lockedOffset = role === 'upper' ? r.endOffset : r.startOffset;
                lastPointX = x; lastPointY = y - 12; // Caret is above the handle tip.
                activeRegion = region;
                setHandleDragging(true);
                return region === chat ? role : role + ':panel';
            },
            point: function (x, y) {
                if (!role) return;
                if (Number.isFinite(x)) lastPointX = x;
                if (Number.isFinite(y)) lastPointY = y;
            },
            lastX: function () { return lastPointX; },
            check: repairIfNeeded,
            end: stop
        };
    })();
    document.addEventListener('selectionchange', function () {
        window.__stSelectionAnchorLock.check();
        scheduleConfine();
        report(true);
    }, {passive:true});
    chat.addEventListener('scroll', function () { report(false); }, {passive:true});
    // Settings panels: report while the region of the selection scrolls.
    document.addEventListener('scroll', function (e) {
        if (e.target !== chat && (e.target === activeRegion || e.target === reportRegion)) {
            report(false);
        }
    }, {capture:true, passive:true});
    window.addEventListener('resize', function () { report(true); }, {passive:true});
    window.addEventListener('pageshow', function () { report(true); }, {passive:true});
    report(true);
    return 'native-popup-handle-bridge-v3';
})();
