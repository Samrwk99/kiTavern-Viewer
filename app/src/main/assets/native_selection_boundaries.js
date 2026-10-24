/* kiTavern: EXPERIMENTAL Chromium native selection-handle scroll bridge.
 * Wrapper-only runtime injection. SillyTavern assets are never modified.
 * Native Android selection handles remain owned by Chromium.
 * No setBaseAndExtent, Range edits, or synthetic DOM touch events.
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
        document.body && document.body.classList.toggle('st-handle-dragging', !!isDragging);
        report(true);
    };
    window.__stAutoScrollStep = function (deltaCssPixels) {
        if (!Number.isFinite(deltaCssPixels) || Math.abs(deltaCssPixels) > 100) return;
        subpixelRemainder += deltaCssPixels;
        var step = Math.trunc(subpixelRemainder);
        if (step) {
            subpixelRemainder -= step;
            chat.scrollTop += step;
        }
        report(true);
    };
    document.addEventListener('selectionchange', function () { report(true); }, {passive:true});
    chat.addEventListener('scroll', function () { report(false); }, {passive:true});
    window.addEventListener('resize', function () { report(true); }, {passive:true});
    window.addEventListener('pageshow', function () { report(true); }, {passive:true});
    report(true);
    return 'native-popup-handle-bridge-v1';
})();
