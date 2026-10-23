/* Lightweight selection boundaries for SillyTavern in Android WebView.
 * Does not draw handles, intercept touch events, change Selection/Range,
 * manage the Android toolbar, or control scrolling. Chromium owns all of that.
 */
(function () {
    'use strict';
    if (!document.getElementById('chat') || !document.getElementById('send_textarea')) {
        return 'not a SillyTavern chat';
    }
    var id = '__kiTavernNativeSelectionBoundaries';
    if (document.getElementById(id)) return 'already installed';
    var style = document.createElement('style');
    style.id = id;
    style.textContent = [
        // Keep document selection inside the chat, not the settings/header/composer.
        // Controls elsewhere retain their own separate, native editing selection.
        'html, html body, html body * {',
        '  -webkit-user-select: none !important;',
        '  user-select: none !important;',
        '}',
        'html body #chat, html body #chat * {',
        '  -webkit-user-select: text !important;',
        '  user-select: text !important;',
        '}',
        // Retain selection inside any present or future HTML editing control,
        // including dynamically created message editors and maximized textareas.
        'html body textarea, html body input:not([type]),',
        'html body input[type="text"], html body input[type="search"],',
        'html body input[type="email"], html body input[type="url"],',
        'html body input[type="tel"], html body input[type="number"],',
        'html body input[type="password"],',
        'html body [contenteditable="true"], html body [contenteditable=""],',
        'html body [role="textbox"] {',
        '  -webkit-user-select: text !important;',
        '  user-select: text !important;',
        '}',
        // When selecting at the edge, only #chat should overscroll.
        // Selection scrolling is still Chromium's native WebView behavior.
        '#chat { overscroll-behavior-y: contain; }'
    ].join('\n');
    (document.head || document.documentElement).appendChild(style);
    return 'native-selection-boundaries-v1';
})();
