/* kiTavern: EXPERIMENTAL Chromium native selection-handle scroll bridge.
 * Wrapper-only runtime injection. SillyTavern assets are never modified.
 * Native Android selection handles remain owned by Chromium.
 * Native range updates are preserved; a guarded re-grab recovery repairs
 * the fixed DOM endpoint only if Chromium moves it incorrectly.
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
        // Temporary hit-test shielding only while dragging a chat handle.
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
    function inChat(node) {
        return !!(node && (node === chat || chat.contains(node)));
    }
    function report(force) {
        if (!document.getElementById('chat') || !window.STSelectionBridge) return;
        var now = performance.now();
        if (!force && now - lastReportTime < 24) return;
        lastReportTime = now;
        var s = window.getSelection();
        var inChatSelection = !!(s && s.rangeCount === 1 && !s.isCollapsed &&
            inChat(s.anchorNode) && inChat(s.focusNode));
        var r = chat.getBoundingClientRect();
        // Convert CSS viewport positions into Android WebView coordinates on
        // the native side using innerWidth/innerHeight (not fixed dp scales).
        window.STSelectionBridge.onSelectionStateChanged(
            inChatSelection, r.left, r.top, r.right, r.bottom,
            Math.max(1, window.innerWidth), Math.max(1, window.innerHeight),
            chat.scrollTop > 0.5,
            chat.scrollTop + chat.clientHeight < chat.scrollHeight - 0.5
        );
    }
    window.__kiTavernSelectionReport = function () { report(true); };
    window.__stSetHandleDragging = function (isDragging) {
        subpixelRemainder = 0;
        if (!isDragging && window.__stSelectionAnchorLock) {
            window.__stSelectionAnchorLock.end();
        }
        document.body && document.body.classList.toggle('st-handle-dragging', !!isDragging);
        report(true);
    };
    window.__stAutoScrollStep = function (deltaCssPixels, pointX, pointY) {
        if (window.__stSelectionAnchorLock && Number.isFinite(pointX) && Number.isFinite(pointY)) {
            window.__stSelectionAnchorLock.point(pointX, pointY);
        }
        if (!Number.isFinite(deltaCssPixels) || Math.abs(deltaCssPixels) > 100) return;
        subpixelRemainder += deltaCssPixels;
        var step = Math.trunc(subpixelRemainder);
        if (step) {
            subpixelRemainder -= step;
            chat.scrollTop += step;
        }
        report(true);
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
        function positionCmp(a, ao, b, bo) {
            try {
                var x = document.createRange(), y = document.createRange();
                x.setStart(a, ao); x.collapse(true);
                y.setStart(b, bo); y.collapse(true);
                return x.compareBoundaryPoints(Range.START_TO_START, y);
            } catch (_) { return null; }
        }
        function stop() {
            lockedNode = null; lockedOffset = 0; role = null;
            repairing = false; repairs = 0;
            lastPointX = NaN; lastPointY = NaN;
        }
        function repairIfNeeded() {
            if (!role || repairing || !lockedNode || !lockedNode.isConnected ||
                !inChat(lockedNode)) return;
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
                var rect = chat.getBoundingClientRect();
                var x = Math.max(rect.left + 3, Math.min(rect.right - 3, lastPointX));
                var y = Math.max(rect.top + 3, Math.min(rect.bottom - 3, lastPointY));
                var caret = document.caretRangeFromPoint && document.caretRangeFromPoint(x, y);
                if (caret && inChat(caret.startContainer)) {
                    movingNode = caret.startContainer;
                    movingOffset = caret.startOffset;
                }
            }
            if (!movingNode) {
                movingNode = role === 'upper' ? range.startContainer : range.endContainer;
                movingOffset = role === 'upper' ? range.startOffset : range.endOffset;
            }
            if (!inChat(movingNode)) return;
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
            begin: function (x, y) {
                stop();
                var sel = window.getSelection();
                if (!sel || sel.isCollapsed || sel.rangeCount !== 1) return 'none';
                var r = sel.getRangeAt(0);
                if (!inChat(r.startContainer) || !inChat(r.endContainer)) return 'outside';
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
                return role;
            },
            point: function (x, y) {
                if (!role) return;
                if (Number.isFinite(x)) lastPointX = x;
                if (Number.isFinite(y)) lastPointY = y;
            },
            check: repairIfNeeded,
            end: stop
        };
    })();
    document.addEventListener('selectionchange', function () {
        window.__stSelectionAnchorLock.check();
        report(true);
    }, {passive:true});
    chat.addEventListener('scroll', function () { report(false); }, {passive:true});
    window.addEventListener('resize', function () { report(true); }, {passive:true});
    window.addEventListener('pageshow', function () { report(true); }, {passive:true});
    report(true);
    return 'native-popup-handle-bridge-v1';
})();
