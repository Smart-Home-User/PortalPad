package com.portalpad.app.service

import android.view.KeyEvent

/**
 * Maps Linux evdev key codes (from <linux/input-event-codes.h>), as emitted by
 * the grabbed combo keyboard+mouse device, to Android [KeyEvent] key codes and
 * modifier meta bits — for the physical-keyboard relay (Stage 2).
 *
 * These Linux codes are a stable kernel UAPI, identical across standard
 * keyboards, so the table is built from the canonical values rather than a
 * per-device probe. Unmapped codes (exotic media / Fn-layer keys) are dropped;
 * if a specific key on a given device comes through wrong, the raw code can be
 * read from the Stage-1 "KEY linuxCode=" logging and added here.
 */
internal object KeyboardRelayMap {

    // Linux KEY_* → Android KEYCODE_*.
    private val keyMap: Map<Int, Int> = buildMap {
        // Letters (Linux KEY_A=30, etc.)
        put(30, KeyEvent.KEYCODE_A); put(48, KeyEvent.KEYCODE_B); put(46, KeyEvent.KEYCODE_C)
        put(32, KeyEvent.KEYCODE_D); put(18, KeyEvent.KEYCODE_E); put(33, KeyEvent.KEYCODE_F)
        put(34, KeyEvent.KEYCODE_G); put(35, KeyEvent.KEYCODE_H); put(23, KeyEvent.KEYCODE_I)
        put(36, KeyEvent.KEYCODE_J); put(37, KeyEvent.KEYCODE_K); put(38, KeyEvent.KEYCODE_L)
        put(50, KeyEvent.KEYCODE_M); put(49, KeyEvent.KEYCODE_N); put(24, KeyEvent.KEYCODE_O)
        put(25, KeyEvent.KEYCODE_P); put(16, KeyEvent.KEYCODE_Q); put(19, KeyEvent.KEYCODE_R)
        put(31, KeyEvent.KEYCODE_S); put(20, KeyEvent.KEYCODE_T); put(22, KeyEvent.KEYCODE_U)
        put(47, KeyEvent.KEYCODE_V); put(17, KeyEvent.KEYCODE_W); put(45, KeyEvent.KEYCODE_X)
        put(21, KeyEvent.KEYCODE_Y); put(44, KeyEvent.KEYCODE_Z)
        // Number row (Linux KEY_1=2 .. KEY_0=11)
        put(2, KeyEvent.KEYCODE_1); put(3, KeyEvent.KEYCODE_2); put(4, KeyEvent.KEYCODE_3)
        put(5, KeyEvent.KEYCODE_4); put(6, KeyEvent.KEYCODE_5); put(7, KeyEvent.KEYCODE_6)
        put(8, KeyEvent.KEYCODE_7); put(9, KeyEvent.KEYCODE_8); put(10, KeyEvent.KEYCODE_9)
        put(11, KeyEvent.KEYCODE_0)
        // Whitespace / editing
        put(57, KeyEvent.KEYCODE_SPACE); put(28, KeyEvent.KEYCODE_ENTER)
        put(14, KeyEvent.KEYCODE_DEL); put(15, KeyEvent.KEYCODE_TAB)
        put(1, KeyEvent.KEYCODE_ESCAPE); put(111, KeyEvent.KEYCODE_FORWARD_DEL)
        // Punctuation
        put(12, KeyEvent.KEYCODE_MINUS); put(13, KeyEvent.KEYCODE_EQUALS)
        put(26, KeyEvent.KEYCODE_LEFT_BRACKET); put(27, KeyEvent.KEYCODE_RIGHT_BRACKET)
        put(43, KeyEvent.KEYCODE_BACKSLASH); put(39, KeyEvent.KEYCODE_SEMICOLON)
        put(40, KeyEvent.KEYCODE_APOSTROPHE); put(41, KeyEvent.KEYCODE_GRAVE)
        put(51, KeyEvent.KEYCODE_COMMA); put(52, KeyEvent.KEYCODE_PERIOD)
        put(53, KeyEvent.KEYCODE_SLASH)
        // Arrows / navigation
        put(103, KeyEvent.KEYCODE_DPAD_UP); put(108, KeyEvent.KEYCODE_DPAD_DOWN)
        put(105, KeyEvent.KEYCODE_DPAD_LEFT); put(106, KeyEvent.KEYCODE_DPAD_RIGHT)
        put(102, KeyEvent.KEYCODE_MOVE_HOME); put(107, KeyEvent.KEYCODE_MOVE_END)
        put(104, KeyEvent.KEYCODE_PAGE_UP); put(109, KeyEvent.KEYCODE_PAGE_DOWN)
        put(110, KeyEvent.KEYCODE_INSERT)
        // Modifiers (also injected as keys so framework-level chords register)
        put(42, KeyEvent.KEYCODE_SHIFT_LEFT); put(54, KeyEvent.KEYCODE_SHIFT_RIGHT)
        put(29, KeyEvent.KEYCODE_CTRL_LEFT); put(97, KeyEvent.KEYCODE_CTRL_RIGHT)
        put(56, KeyEvent.KEYCODE_ALT_LEFT); put(100, KeyEvent.KEYCODE_ALT_RIGHT)
        put(125, KeyEvent.KEYCODE_META_LEFT); put(126, KeyEvent.KEYCODE_META_RIGHT)
        put(58, KeyEvent.KEYCODE_CAPS_LOCK)
        // Function row
        put(59, KeyEvent.KEYCODE_F1); put(60, KeyEvent.KEYCODE_F2); put(61, KeyEvent.KEYCODE_F3)
        put(62, KeyEvent.KEYCODE_F4); put(63, KeyEvent.KEYCODE_F5); put(64, KeyEvent.KEYCODE_F6)
        put(65, KeyEvent.KEYCODE_F7); put(66, KeyEvent.KEYCODE_F8); put(67, KeyEvent.KEYCODE_F9)
        put(68, KeyEvent.KEYCODE_F10); put(87, KeyEvent.KEYCODE_F11); put(88, KeyEvent.KEYCODE_F12)
    }

    // Linux modifier KEY_* → Android meta bits (generic + side-specific), so a
    // held Shift/Ctrl/Alt/Meta is applied to the keys pressed while it's down.
    private val modMap: Map<Int, Int> = mapOf(
        42 to (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON),
        54 to (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON),
        29 to (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
        97 to (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON),
        56 to (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
        100 to (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON),
        125 to (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON),
        126 to (KeyEvent.META_META_ON or KeyEvent.META_META_RIGHT_ON),
    )

    /** Android keycode for a Linux evdev code, or null if unmapped (dropped). */
    fun toAndroid(linuxCode: Int): Int? = keyMap[linuxCode]

    /** Android meta bits if [linuxCode] is a modifier key, else null. */
    fun modifierMeta(linuxCode: Int): Int? = modMap[linuxCode]
}
