package com.portalpad.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent

/**
 * Media + audio control that **does not** require Shizuku or root.
 *
 * Volume buttons → [AudioManager.adjustStreamVolume]: zero permissions, always
 * works, shows the system volume UI.
 *
 * Media keys (play/pause/next/prev/ff/rew) → tries three paths in order:
 *   1. [MediaSessionManager.dispatchMediaKeyEvent] on the active session
 *      (requires Notification Access — the user grants this once in system
 *      settings, no Shizuku needed)
 *   2. [Intent.ACTION_MEDIA_BUTTON] ordered broadcast — works without any
 *      permission but less reliable on Android 13+
 *   3. Fallback through [InputInjector.pressKey] (only useful if Shizuku/Root
 *      is set up anyway)
 *
 * D-pad and Enter still go through InputInjector because they need to target
 * the external display, which media-key paths can't do.
 */
class MediaController(private val context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent =
        ComponentName(context, PortalPadNotificationListenerService::class.java)

    // Serializes volume writes off the main thread. setStreamVolume can be slow
    // on some devices and the slider calls it rapidly during a drag; doing that
    // on the UI thread caused ANRs. Single-thread = ordered, last-write-wins.
    private val volumeExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "portalpad-volume").apply { isDaemon = true }
        }

    /** True when the user has granted Notification Access to PortalPad. */
    val hasNotificationAccess: Boolean
        get() {
            val enabled = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners",
            ) ?: return false
            return enabled.split(":").any {
                it.equals(listenerComponent.flattenToString(), ignoreCase = true)
            }
        }

    // ─────────────────────────── volume (no permission) ───────────────────────────

    fun volumeUp() {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI,
        )
    }

    fun volumeDown() {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI,
        )
    }

    /**
     * Set STREAM_MUSIC volume to an absolute percentage (0..100) WITHOUT
     * showing Android's system volume UI — used by the in-app volume slider so
     * dragging it doesn't pop the OS volume panel over the glasses display.
     * flags = 0 is the key: it changes the level silently.
     *
     * IMPORTANT: the AudioManager calls run on a dedicated background thread,
     * not the caller's (main/UI) thread. On some devices setStreamVolume is
     * slow (it walks the audio policy + fires ContentObservers), and the slider
     * calls this rapidly during a drag — doing it on the main thread jammed the
     * UI thread and triggered an ANR ("input dispatching timed out"). The
     * single-thread executor keeps writes ordered (last-write-wins) while never
     * blocking the UI.
     */
    fun setVolumePct(pct: Int) {
        volumeExecutor.execute {
            try {
                val stream = AudioManager.STREAM_MUSIC
                val max = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
                val level = (pct.coerceIn(0, 100) * max + 50) / 100   // round to nearest step
                // Unmute first if needed, so dragging up from a muted state
                // actually produces sound rather than raising a muted stream.
                if (level > 0 && audioManager.isStreamMute(stream)) {
                    runCatching { audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) }
                }
                audioManager.setStreamVolume(stream, level.coerceIn(0, max), 0)
            } catch (t: Throwable) {
                Log.w(TAG, "setVolumePct failed", t)
            }
        }
    }

    /**
     * Saved volume from before muting, used to restore on unmute. -1 = no
     * saved value (we're not currently in a mute-via-our-button state).
     * AudioManager's own ADJUST_TOGGLE_MUTE internally remembers, but
     * behavior varies across OEMs (some restore to 1, not previous level).
     * Tracking it ourselves guarantees the restore is correct on Samsung.
     */
    private var preMuteVolume: Int = -1

    /**
     * Toggle mute. If currently NOT muted: save current volume, then mute.
     * If currently muted: restore saved volume (or 50% as a safe fallback
     * if we don't have a saved value — e.g. the user muted via another app).
     */
    fun mute() {
        val stream = AudioManager.STREAM_MUSIC
        val isMuted = audioManager.isStreamMute(stream) || audioManager.getStreamVolume(stream) == 0
        if (isMuted) {
            // Unmute. Prefer restoring our saved level. If we don't have one,
            // pick a safe default (50% of max). Use setStreamVolume directly
            // so we explicitly set the target level — ADJUST_UNMUTE on its
            // own restores AudioManager's internal saved level, which on
            // Samsung sometimes ends up at volume 1.
            val max = audioManager.getStreamMaxVolume(stream)
            val target = if (preMuteVolume > 0) preMuteVolume else (max / 2).coerceAtLeast(1)
            audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            audioManager.setStreamVolume(stream, target.coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
            preMuteVolume = -1
        } else {
            // Save current level then mute. ADJUST_MUTE on STREAM_MUSIC sets
            // the stream's volume to 0 internally while preserving the
            // "saved" volume in AudioManager, but we keep our own copy for
            // reliable cross-OEM restore.
            preMuteVolume = audioManager.getStreamVolume(stream)
            audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
        }
    }

    /** Current muted state — for UI to reflect the toggle accurately. */
    fun isMuted(): Boolean {
        val stream = AudioManager.STREAM_MUSIC
        return audioManager.isStreamMute(stream) || audioManager.getStreamVolume(stream) == 0
    }

    // ─────────────────────────── media keys (no Shizuku needed) ───────────────────

    fun playPause() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun next() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun prev() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun fastForward() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
    fun rewind() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_REWIND)
    fun stop() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_STOP)

    /**
     * (currentMs, durationMs) for the active media session. Returns null when
     * no session is playing or notification access isn't granted.
     */
    fun getCurrentPlaybackPosition(): Pair<Long, Long>? {
        if (!hasNotificationAccess) return null
        return try {
            val session = mediaSessionManager.getActiveSessions(listenerComponent)
                .firstOrNull() ?: return null
            val state = session.playbackState ?: return null
            val duration = session.metadata?.getLong(
                android.media.MediaMetadata.METADATA_KEY_DURATION
            ) ?: -1L
            if (duration <= 0) return null
            state.position to duration
        } catch (t: Throwable) {
            Log.w(TAG, "getCurrentPlaybackPosition failed", t)
            null
        }
    }

    /**
     * True when a media session is actively PLAYING (not paused/stopped/buffering).
     * Used by the media cursor auto-hide. Returns false when notification access
     * isn't granted (we can't enumerate sessions) or no session is playing.
     */
    fun isPlaying(): Boolean {
        if (!hasNotificationAccess) return false
        return try {
            mediaSessionManager.getActiveSessions(listenerComponent).any { ctrl ->
                ctrl.playbackState?.state ==
                    android.media.session.PlaybackState.STATE_PLAYING
            }
        } catch (t: Throwable) {
            Log.w(TAG, "isPlaying check failed", t)
            false
        }
    }

    /** Seek the active media session to the given absolute position in ms. */
    fun seekTo(positionMs: Long) {
        if (!hasNotificationAccess) return
        try {
            val session = mediaSessionManager.getActiveSessions(listenerComponent)
                .firstOrNull() ?: return
            session.transportControls.seekTo(positionMs)
        } catch (t: Throwable) {
            Log.w(TAG, "seekTo failed", t)
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        if (sendViaMediaSession(keyCode)) return
        if (sendViaBroadcast(keyCode)) return
        Log.w(TAG, "No path worked for media key $keyCode")
    }

    private fun sendViaMediaSession(keyCode: Int): Boolean {
        if (!hasNotificationAccess) return false
        return try {
            val sessions = mediaSessionManager.getActiveSessions(listenerComponent)
            val active = sessions.firstOrNull() ?: return false
            val now = SystemClock.uptimeMillis()
            active.dispatchMediaButtonEvent(
                KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            )
            active.dispatchMediaButtonEvent(
                KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "MediaSession dispatch failed", t)
            false
        }
    }

    private fun sendViaBroadcast(keyCode: Int): Boolean {
        return try {
            val now = SystemClock.uptimeMillis()
            val down = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            }
            val up = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            }
            context.sendOrderedBroadcast(down, null)
            context.sendOrderedBroadcast(up, null)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Media broadcast failed", t)
            false
        }
    }

    companion object { private const val TAG = "MediaController" }
}
