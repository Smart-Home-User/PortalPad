package com.portalpad.app.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

/**
 * Cosmetic appearance for a navigation button (Back / Home). Purely visual —
 * the icon and colors are independent of what the button *does* (its assigned
 * action). Stored as JSON in prefs.
 *
 * @param iconId    key into [ButtonIcons.ALL]; falls back to the button default
 * @param bgColor   pill background as an ARGB Int (e.g. 0xFF5B4A8F.toInt())
 * @param iconColor glyph tint as an ARGB Int
 * @param customIconPath absolute path to a user-picked image in app storage. When
 *   non-null and decodable it OVERRIDES [iconId] and is drawn untinted (full
 *   color), so [iconColor] is ignored. Null = use the vector [iconId]. This is the
 *   foundation external-bitmap path that the icon-pack browser (Build 2) builds on.
 */
@Serializable
data class ButtonAppearance(
    val iconId: String,
    val bgColor: Int,
    val iconColor: Int,
    val customIconPath: String? = null,
)

/**
 * Per-color-key visual style for the program keys (Red/Green/Yellow/Blue).
 * All colors are ARGB Ints (same convention as [ButtonAppearance]); background
 * is OPAQUE once customized. [borderWidthDp] 0 = no border. ABSENT (no stored
 * style for a key) means the built-in look: bg = key color @85% alpha, a 1dp
 * muted border, white text — so unedited keys are unchanged.
 */
@Serializable
data class ProgKeyAppearance(
    val bgColor: Int,
    val borderColor: Int,
    val textColor: Int,
    val borderWidthDp: Float,
)

/** The default muted-purple pill background used across the nav cluster. */
const val DEFAULT_NAV_BG: Int = 0xFF5B4A8F.toInt()
/** Default near-white glyph tint. */
const val DEFAULT_ICON_TINT: Int = 0xFFEDEAF6.toInt()

object ButtonIcons {
    /** Stable id → vector. Ids are persisted, so never rename an existing key. */
    val ALL: Map<String, ImageVector> = linkedMapOf(
        "arrow_back" to Icons.AutoMirrored.Filled.ArrowBack,
        "arrow_forward" to Icons.AutoMirrored.Filled.ArrowForward,
        "chevron_left" to Icons.Filled.ChevronLeft,
        "chevron_right" to Icons.Filled.ChevronRight,
        "arrow_up" to Icons.Filled.ArrowUpward,
        "arrow_down" to Icons.Filled.ArrowDownward,
        "kb_arrow_up" to Icons.Filled.KeyboardArrowUp,
        "kb_arrow_down" to Icons.Filled.KeyboardArrowDown,
        "kb_arrow_left" to Icons.Filled.KeyboardArrowLeft,
        "kb_arrow_right" to Icons.Filled.KeyboardArrowRight,
        "undo" to Icons.AutoMirrored.Filled.Undo,
        "redo" to Icons.AutoMirrored.Filled.Redo,
        "return" to Icons.AutoMirrored.Filled.KeyboardReturn,
        "exit" to Icons.AutoMirrored.Filled.ExitToApp,
        "home" to Icons.Filled.Home,
        "apps" to Icons.Filled.Apps,
        "auto_awesome" to Icons.Filled.AutoAwesome,
        "keyboard" to Icons.Filled.Keyboard,
        "grid" to Icons.Filled.GridView,
        "dashboard" to Icons.Filled.Dashboard,
        "menu" to Icons.Filled.Menu,
        "settings" to Icons.Filled.Settings,
        "search" to Icons.Filled.Search,
        "star" to Icons.Filled.Star,
        "favorite" to Icons.Filled.Favorite,
        "thumb_up" to Icons.Filled.ThumbUp,
        "bookmark" to Icons.Filled.Bookmark,
        "flag" to Icons.Filled.Flag,
        "rocket" to Icons.Filled.Rocket,
        "bolt" to Icons.Filled.Bolt,
        "flash" to Icons.Filled.FlashOn,
        "lightbulb" to Icons.Filled.Lightbulb,
        "power_new" to Icons.Filled.PowerSettingsNew,
        "lock" to Icons.Filled.Lock,
        "visibility" to Icons.Filled.Visibility,
        "notifications" to Icons.Filled.Notifications,
        "check" to Icons.Filled.Check,
        "done" to Icons.Filled.Done,
        "close" to Icons.Filled.Close,
        "add" to Icons.Filled.Add,
        "delete" to Icons.Filled.Delete,
        "edit" to Icons.Filled.Edit,
        "refresh" to Icons.Filled.Refresh,
        "replay" to Icons.Filled.Replay,
        "share" to Icons.Filled.Share,
        "play" to Icons.Filled.PlayArrow,
        "pause" to Icons.Filled.Pause,
        "stop" to Icons.Filled.Stop,
        "skip_next" to Icons.Filled.SkipNext,
        "skip_prev" to Icons.Filled.SkipPrevious,
        "fast_forward" to Icons.Filled.FastForward,
        "fast_rewind" to Icons.Filled.FastRewind,
        "volume_up" to Icons.Filled.VolumeUp,
        "volume_off" to Icons.Filled.VolumeOff,
        "music" to Icons.Filled.MusicNote,
        "mic" to Icons.Filled.Mic,
        "headphones" to Icons.Filled.Headphones,
        "photo_camera" to Icons.Filled.PhotoCamera,
        "image" to Icons.Filled.Image,
        "tv" to Icons.Filled.Tv,
        "cast" to Icons.Filled.Cast,
        "wifi" to Icons.Filled.Wifi,
        "bluetooth" to Icons.Filled.Bluetooth,
        "brightness" to Icons.Filled.Brightness6,
        "map" to Icons.Filled.Map,
        "public" to Icons.Filled.Public,
        "folder" to Icons.Filled.Folder,
        "call" to Icons.Filled.Call,
        "email" to Icons.Filled.Email,
        "layers" to Icons.Filled.Layers,
        "fullscreen" to Icons.Filled.Fullscreen,
    )

    /** Resolve an icon id to a vector, falling back to [fallback]. */
    fun resolve(id: String?, fallback: ImageVector): ImageVector =
        id?.let { ALL[it] } ?: fallback

    /** Human-friendly label for search (derived from the id). */
    fun label(id: String): String =
        id.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
