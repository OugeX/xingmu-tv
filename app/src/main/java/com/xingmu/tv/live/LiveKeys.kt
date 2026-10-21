package com.xingmu.tv.live

/**
 * 遥控器按键映射（Android `KeyEvent` 键码，避免 UI 层再维护一套常量）。
 *
 * DPAD_UP/DOWN 不在这里消费：频道列表的焦点移动本身就是“切台预览”，UI 在焦点变化时调用
 * [LiveController.preview]；若 UI 不使用列表焦点，则调用 [LiveController.onDpadSwitch]。
 * CH+/CH- 与数字键在这里直接消费（PLAYBACK_SPEC §2）。
 */
object LiveKeys {

    const val ACTION_DOWN = 0
    const val ACTION_UP = 1

    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val ENTER = 66
    const val BACK = 4
    const val MENU = 82
    const val INFO = 165
    const val CHANNEL_UP = 166
    const val CHANNEL_DOWN = 167
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25

    const val NUM_0 = 7
    const val NUM_9 = 16

    /** 数字键输入后 1.5s 自动确认（PLAYBACK_SPEC §2） */
    const val NUMBER_TIMEOUT_MS = 1_500L
    const val NUMBER_MAX_LENGTH = 3

    fun digitOf(keyCode: Int): Int? = if (keyCode in NUM_0..NUM_9) keyCode - NUM_0 else null
}
