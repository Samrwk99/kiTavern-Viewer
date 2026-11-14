/* kiTavern: EXPERIMENTAL Chromium native selection-handle scroll bridge.
 * Wrapper-only runtime injection. SillyTavern assets are never modified.
 * Native Android selection handles remain owned by Chromium.
 * Native range updates are preserved; a guarded re-grab recovery repairs
 * the fixed DOM endpoint only if Chromium moves it incorrectly.
 *
 * One selection system: Chromium's. The native side assists a handle drag
 * only when begin() below confirms, at grab time, that the live selection is
 * page text inside one scroll region (#chat, or a settings/drawer panel) or
 * text selected inside a text field. It then keeps the handle inside that
 * region (above the keyboard) and scrolls it at the edges: fast in #chat,
 * gently everywhere else. Anything else stays fully native.
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
    var activeIsField = false;
    // Long-press drag (press a word and drag without lifting): the selection
    // must have been made by the current touch, never an older one.
    var longPressActive = false;
    var lastTouchStart = -Infinity;
    var lastSelectionChange = -Infinity;
    var touchStartX = NaN, touchStartY = NaN, touchMovedEarly = false;
    // The dropdown whose text a drag started in, until the drag leaves it.
    var dropdownDrag = null;
    // Hold-and-drag from a dropdown: the page drives the selection itself.
    var pageDrivenDropdown = null;

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
    // Nearest scroll box holding a node (never the chat shell or an editor).
    function scrollerOf(node) {
        var n = node && (node.nodeType === 1 ? node : node.parentElement);
        var shell = document.getElementById('sheld');
        for (; n && n !== document.body && n !== document.documentElement; n = n.parentElement) {
            if (n.isContentEditable || n === shell) return null;
            if (scrollsY(n)) return n;
        }
        return null;
    }
    // movingEnd: the DOM end whose handle is being dragged, when known.
    function selectionRegion(s, movingEnd) {
        if (!s || s.rangeCount !== 1 || s.isCollapsed) return null;
        var anchorInChat = inChat(s.anchorNode), focusInChat = inChat(s.focusNode);
        if (anchorInChat && focusInChat) return chat;
        // Half in the chat: the chat backstop below handles it (unchanged).
        if (anchorInChat || focusInChat) return null;
        var range = s.getRangeAt(0);
        var shared = scrollerOf(range.commonAncestorContainer);
        if (shared) return shared;
        // The ends sit in different boxes, e.g. a panel's header or menu text
        // and its scrolling list: use the box of the end being moved.
        var moving = movingEnd || s.focusNode;
        var fixed = moving === range.startContainer ? range.endContainer : range.startContainer;
        return scrollerOf(moving) || scrollerOf(fixed);
    }
    // A text field (textarea/input) holding a non-empty selection. Chromium
    // reports those to getSelection() as collapsed, so ask the field.
    function activeFieldSelection() {
        var el = document.activeElement;
        if (!el || (el.tagName !== 'TEXTAREA' && el.tagName !== 'INPUT')) return null;
        try {
            if (typeof el.selectionStart !== 'number' ||
                el.selectionStart === el.selectionEnd) return null;
        } catch (_) { return null; }
        return el;
    }
    // Dropdowns only. The chosen option's text in a <select> is drawn inside
    // the control, and a selection of it is not reported to the page as one.
    // So when no ordinary selection exists, look at what lies at the point
    // itself (just above a grabbed marker, or where the finger long-pressed)
    // and treat a <select> there like a text field: clamp and scroll its panel.
    function dropdownAt(x, y, offsets) {
        if (!Number.isFinite(x) || !Number.isFinite(y)) return null;
        var el = document.activeElement;
        // Insertion handles belong to a focused text field, never a dropdown.
        if (el && (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT' || el.isContentEditable)) {
            return null;
        }
        for (var i = 0; i < offsets.length; i++) {
            var hit = document.elementFromPoint(x, y - offsets[i]);
            var dropdown = hit && hit.closest ? hit.closest('select') : null;
            if (dropdown) return dropdown;
        }
        return null;
    }
    // A selection inside a dropdown can never grow past the dropdown, and
    // Chromium scrolls it back into view whenever the panel scrolls it away:
    // the marker then jitters at the edge. So once the drag leaves the
    // dropdown, or the panel has to scroll, swap it for an ordinary page
    // selection that starts at the dropdown and runs to the point (keeping the
    // dropdown inside it). Chromium then extends that like any panel selection.
    function leaveDropdown(down, x, y) {
        var dropdown = dropdownDrag;
        dropdownDrag = null;
        selectFromDropdown(dropdown, down, x, y);
    }
    // down: true = from the dropdown downwards, false = upwards, null = just
    // the dropdown itself (the finger is still over it).
    function selectFromDropdown(dropdown, down, x, y) {
        if (!dropdown || !dropdown.isConnected || !dropdown.parentNode) return;
        var parent = dropdown.parentNode;
        var index = Array.prototype.indexOf.call(parent.childNodes, dropdown);
        if (down === null) {
            try { window.getSelection().setBaseAndExtent(parent, index, parent, index + 1); } catch (_) { }
            return;
        }
        var baseOffset = down ? index : index + 1;
        var extent = null;
        if (Number.isFinite(x) && Number.isFinite(y) && document.caretRangeFromPoint) {
            var caret = document.caretRangeFromPoint(x, y);
            if (caret && inRegion(caret.startContainer) && caret.startContainer !== dropdown &&
                    !dropdown.contains(caret.startContainer)) {
                extent = {node: caret.startContainer, offset: caret.startOffset};
            }
        }
        // The far end must lie beyond the dropdown, in the drag direction.
        if (!extent || (down ? positionCmp(extent.node, extent.offset, parent, index + 1) < 0
                             : positionCmp(extent.node, extent.offset, parent, index) > 0)) {
            extent = {node: parent, offset: down ? index + 1 : index};
        }
        try {
            window.getSelection().setBaseAndExtent(parent, baseOffset, extent.node, extent.offset);
        } catch (_) { }
    }
    // Called by the native side for every move of a page-driven drag (and
    // after each scroll step), with the clamped finger point.
    window.__stDropdownDragTo = function (x, y) {
        var dropdown = pageDrivenDropdown;
        if (!dropdown || !Number.isFinite(x) || !Number.isFinite(y)) return;
        var r = dropdown.getBoundingClientRect();
        selectFromDropdown(dropdown, y > r.bottom ? true : (y < r.top ? false : null), x, y);
    };
    function trackDropdownPoint(x, y) {
        if (!dropdownDrag || !Number.isFinite(y)) return;
        var r = dropdownDrag.getBoundingClientRect();
        if (y > r.bottom + 4) leaveDropdown(true, x, y);
        else if (y < r.top - 4) leaveDropdown(false, x, y);
    }
    // The field itself when its text scrolls, else the box that scrolls it.
    function fieldRegion(field) {
        if (field.tagName === 'TEXTAREA' && field.scrollHeight > field.clientHeight + 1) {
            return field;
        }
        for (var n = field.parentElement; n && n !== document.body &&
                n !== document.documentElement; n = n.parentElement) {
            if (scrollsY(n)) return n;
        }
        return null;
    }
    function hasPageSelection(s) {
        return !!(s && s.rangeCount === 1 && !s.isCollapsed);
    }
    // A real page selection always wins over a text field that merely kept
    // its old inner selection after the user long-pressed elsewhere.
    function currentRegion(s) {
        if (hasPageSelection(s)) return selectionRegion(s);
        var field = activeFieldSelection();
        return field ? fieldRegion(field) : null;
    }
    // Bottom of what the user can see: the keyboard covers the rest.
    function keyboardTop() {
        var lift = window.__kiTavernKeyboardLift;
        var top = lift && lift.visibleBottom ? lift.visibleBottom() : innerHeight;
        return Number.isFinite(top) ? Math.min(innerHeight, top) : innerHeight;
    }
    // The part of a region that is actually on screen.
    function visibleRect(el) {
        var r = el.getBoundingClientRect();
        var box = {left: Math.max(0, r.left), top: Math.max(0, r.top),
            right: Math.min(innerWidth, r.right), bottom: Math.min(keyboardTop(), r.bottom)};
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
        var fieldOnly = !hasPageSelection(s) && !!activeFieldSelection();
        var anySelection = hasPageSelection(s) || fieldOnly;
        var region = activeRegion || (anySelection ? currentRegion(s) : null);
        reportRegion = region;
        var geometry = region || chat;
        var r = visibleRect(geometry);
        // Convert CSS viewport positions into Android WebView coordinates on
        // the native side using innerWidth/innerHeight (not fixed dp scales).
        window.STSelectionBridge.onSelectionStateChanged(
            !!region, anySelection,
            geometry === chat && !(activeRegion ? activeIsField : fieldOnly), r.left, r.top, r.right, r.bottom,
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
            !!isDragging && activeRegion === chat && !activeIsField);
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
        if (dropdownDrag && deltaCssPixels) leaveDropdown(deltaCssPixels > 0, pointX, pointY);
        subpixelRemainder += deltaCssPixels;
        var step = Math.trunc(subpixelRemainder);
        if (step) {
            subpixelRemainder -= step;
            activeRegion.scrollTop += step;
            // New text has scrolled under the finger: extend to it.
            if (pageDrivenDropdown) window.__stDropdownDragTo(pointX, pointY);
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

        function emptyRect(r) {
            return !r || (!r.top && !r.bottom && !r.left && !r.right);
        }
        // #chat: the original measurement, unchanged (its repair depends on
        // it). Settings panels: where the handle for this DOM end is drawn.
        // Beside a field or block Chromium reports an empty rect for the
        // collapsed range, so use the neighbouring line (or field) instead.
        function endpointRect(node, offset, forChat) {
            try {
                var r = document.createRange();
                r.setStart(node, offset);
                r.collapse(true);
                var rect = r.getBoundingClientRect();
                if (forChat || !emptyRect(rect) || node.nodeType !== 1) return rect;
                var after = node.childNodes[offset], before = node.childNodes[offset - 1];
                var near = after || before;
                if (near) {
                    var lines = document.createRange();
                    lines.selectNodeContents(near);
                    var rects = lines.getClientRects();
                    if (rects.length) {
                        var line = after ? rects[0] : rects[rects.length - 1];
                        if (!emptyRect(line)) return line;
                    }
                    var nr = document.createRange();
                    nr.selectNode(near);
                    var nearRect = nr.getBoundingClientRect();
                    if (!emptyRect(nearRect)) return nearRect;
                }
                return node.getBoundingClientRect();
            } catch (_) { return null; }
        }
        function same(node, offset) {
            return node === lockedNode && offset === lockedOffset;
        }
        function stop() {
            dropdownDrag = null;
            pageDrivenDropdown = null;
            longPressActive = false;
            activeRegion = null;
            activeIsField = false;
            lockedNode = null; lockedOffset = 0; role = null;
            repairing = false; repairs = 0;
            lastPointX = NaN; lastPointY = NaN;
        }
        function repairIfNeeded() {
            // #chat only. In settings panels the point under the handle is
            // often a slider, input or unselectable label, and "repairing"
            // from it threw the selection to the top of the panel, which
            // Chromium then scrolled to at full speed.
            if (activeRegion !== chat || activeIsField) return;
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
                // Give up on repairs for this drag, but keep the region:
                // edge scrolling must carry on.
                role = null; lockedNode = null;
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
                var field = hasPageSelection(sel) ? null : activeFieldSelection();
                if (field) {
                    // Inside a text field Chromium keeps both ends in place;
                    // only the region is clamped and scrolled (no DOM repair).
                    var fieldBox = fieldRegion(field);
                    if (!fieldBox) return 'outside';
                    activeRegion = fieldBox;
                    activeIsField = true;
                    setHandleDragging(true);
                    return 'lower:field';
                }
                if (!hasPageSelection(sel)) {
                    // The marker tip sits just below the selected text.
                    var dropdown = dropdownAt(x, y, [3, 8, 14]);
                    if (!dropdown) return 'none';
                    var dropdownBox = fieldRegion(dropdown);
                    if (!dropdownBox) return 'outside';
                    activeRegion = dropdownBox;
                    activeIsField = true;
                    dropdownDrag = dropdown;
                    setHandleDragging(true);
                    return 'lower:field';
                }
                var r = sel.getRangeAt(0);
                var forChat = inChat(sel.anchorNode) && inChat(sel.focusNode);
                var a = endpointRect(r.startContainer, r.startOffset, forChat);
                var b = endpointRect(r.endContainer, r.endOffset, forChat);
                if (!a || !b || !Number.isFinite(y)) return 'no-rect';
                // Pick the DOM endpoint nearest the real popup handle tip; in a
                // settings panel a single line is decided horizontally.
                var upperDistance = Math.abs(a.bottom - y);
                var lowerDistance = Math.abs(b.bottom - y);
                if (!forChat && Math.abs(upperDistance - lowerDistance) < 6 &&
                    Number.isFinite(x)) {
                    upperDistance = Math.abs(a.left - x);
                    lowerDistance = Math.abs(b.right - x);
                }
                role = upperDistance <= lowerDistance ? 'upper' : 'lower';
                var region = selectionRegion(sel,
                    role === 'upper' ? r.startContainer : r.endContainer);
                if (!region) { role = null; return 'outside'; }
                lockedNode = role === 'upper' ? r.endContainer : r.startContainer;
                lockedOffset = role === 'upper' ? r.endOffset : r.startOffset;
                lastPointX = x; lastPointY = y - 12; // Caret is above the handle tip.
                activeRegion = region;
                setHandleDragging(true);
                return region === chat ? role : role + ':panel';
            },
            point: function (x, y) {
                trackDropdownPoint(x, y);
                if (!role) return;
                if (Number.isFinite(x)) lastPointX = x;
                if (Number.isFinite(y)) lastPointY = y;
            },
            lastX: function () { return lastPointX; },
            check: repairIfNeeded,
            end: stop
        };
    })();
    // ---- Long-press drag -------------------------------------------------
    // Called by the native side once a finger that long-pressed a word starts
    // dragging. Same regions and same scrolling as a handle drag; no DOM
    // endpoint repair (Chromium decides which end moves with the finger).
    window.__stLongPressDragBegin = function () {
        var s = window.getSelection();
        var region = null, isField = false, longPressDropdown = null;
        if (lastSelectionChange > lastTouchStart && hasPageSelection(s)) {
            region = selectionRegion(s);
        } else if (!hasPageSelection(s) && !touchMovedEarly &&
                performance.now() - lastTouchStart >= 300) {
            // A genuine long press (no early movement) on a dropdown's text.
            var dropdown = dropdownAt(touchStartX, touchStartY, [0]);
            if (!dropdown) return 'none';
            region = fieldRegion(dropdown);
            isField = true;
            longPressDropdown = dropdown;
        } else {
            return 'stale';
        }
        if (!region) return 'outside';
        window.__stSelectionAnchorLock.end();
        activeRegion = region;
        activeIsField = isField;
        // Hold-and-drag: page-driven from the start (see __stDropdownDragTo).
        pageDrivenDropdown = longPressDropdown;
        longPressActive = true;
        setHandleDragging(true);
        if (longPressDropdown) return 'dropdown';
        return region === chat && !isField ? 'chat' : 'panel';
    };
    // Ends only a long-press drag, never a handle drag begun meanwhile.
    window.__stLongPressDragEnd = function () {
        if (longPressActive) setHandleDragging(false);
    };
    window.addEventListener('touchstart', function (e) {
        lastTouchStart = performance.now();
        var t = e.touches && e.touches[0];
        touchStartX = t ? t.clientX : NaN;
        touchStartY = t ? t.clientY : NaN;
        touchMovedEarly = !!(e.touches && e.touches.length > 1);
    }, {capture:true, passive:true});
    // Movement before the long-press time means a scroll, not a long press.
    window.addEventListener('touchmove', function (e) {
        if (touchMovedEarly || performance.now() - lastTouchStart > 350) return;
        var t = e.touches && e.touches[0];
        if (!t || Math.abs(t.clientX - touchStartX) > 12 ||
            Math.abs(t.clientY - touchStartY) > 12) touchMovedEarly = true;
    }, {capture:true, passive:true});

    // Capture: text fields fire selectionchange/select at the field itself.
    document.addEventListener('selectionchange', function () {
        if (hasPageSelection(window.getSelection())) lastSelectionChange = performance.now();
        window.__stSelectionAnchorLock.check();
        scheduleConfine();
        report(true);
    }, {capture:true, passive:true});
    document.addEventListener('select', function () { report(true); },
        {capture:true, passive:true});
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
    return 'native-popup-handle-bridge-v9';
})();
