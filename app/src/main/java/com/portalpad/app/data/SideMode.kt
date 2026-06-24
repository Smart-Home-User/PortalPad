package com.portalpad.app.data

import kotlinx.serialization.Serializable

/** Corner the screen docks to in Side Mode. */
@Serializable
enum class SideCorner { TL, TR, BL, BR }

/** The three input interfaces that each keep their own Side Mode settings. */
enum class SideInterface { AIR_MOUSE, TRACKPAD, REMOTE }

/**
 * Per-interface Side Mode state: whether the screen is docked to a corner, which
 * corner, and an independent size (40–90%) for each of the four corners. The
 * centered "Screen size" control is separate and untouched by this.
 */
@Serializable
data class SideModeInterface(
    val enabled: Boolean = false,
    val corner: SideCorner = SideCorner.BR,
    val sizeTL: Int = 60,
    val sizeTR: Int = 60,
    val sizeBL: Int = 60,
    val sizeBR: Int = 60,
) {
    fun sizeFor(c: SideCorner): Int = when (c) {
        SideCorner.TL -> sizeTL
        SideCorner.TR -> sizeTR
        SideCorner.BL -> sizeBL
        SideCorner.BR -> sizeBR
    }

    fun withSize(c: SideCorner, pct: Int): SideModeInterface = when (c) {
        SideCorner.TL -> copy(sizeTL = pct)
        SideCorner.TR -> copy(sizeTR = pct)
        SideCorner.BL -> copy(sizeBL = pct)
        SideCorner.BR -> copy(sizeBR = pct)
    }
}

/** Side Mode for all three interfaces. Stored as one JSON blob in prefs. */
@Serializable
data class SideModeConfig(
    val airMouse: SideModeInterface = SideModeInterface(),
    val trackpad: SideModeInterface = SideModeInterface(),
    val remote: SideModeInterface = SideModeInterface(),
) {
    fun forInterface(i: SideInterface): SideModeInterface = when (i) {
        SideInterface.AIR_MOUSE -> airMouse
        SideInterface.TRACKPAD -> trackpad
        SideInterface.REMOTE -> remote
    }

    fun withInterface(i: SideInterface, v: SideModeInterface): SideModeConfig = when (i) {
        SideInterface.AIR_MOUSE -> copy(airMouse = v)
        SideInterface.TRACKPAD -> copy(trackpad = v)
        SideInterface.REMOTE -> copy(remote = v)
    }
}
