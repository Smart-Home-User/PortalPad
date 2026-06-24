package com.portalpad.app.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs

/**
 * Converts phone tilt into cursor motion. Uses [TYPE_GAME_ROTATION_VECTOR] which
 * provides drift-free relative orientation without needing the magnetometer
 * (so it works indoors without compass calibration).
 *
 * Approach:
 *  - On each sensor event, compute yaw + pitch deltas from the previous frame
 *  - Apply a deadzone to ignore tiny tremors
 *  - Scale by sensitivity, send as [InputInjector.pointerMove]
 *
 * Honest caveats:
 *  - First version, not yet feel-tuned. Sensitivity may feel too high or low.
 *  - Orientation handling is basic — assumes phone held in landscape with screen
 *    facing user. Tilting/rotating phone changes mapping.
 *  - No drift compensation beyond what the sensor provides (game rotation
 *    vector is the best for this).
 *
 * Usage: [start] when the user enters AIR_MOUSE mode in trackpad activity,
 * [stop] when leaving. Calibration is automatic — yaw/pitch reset on each start.
 */
class AirMouseController(
    private val context: Context,
    private val injector: InputInjector,
) : SensorEventListener {

    private val sensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    private val rotationSensor by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    }

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private var lastYaw = 0f
    private var lastPitch = 0f
    private var initialized = false

    /** Sensitivity multiplier — higher = cursor moves more per degree of tilt.
     *  Default 20f matches the settings slider default + TrackpadActivity fallback. */
    @Volatile
    var sensitivity: Float = 20f

    /** Deadzone in radians — sub-tremor motion below this threshold is ignored. */
    @Volatile
    var deadzone: Float = 0.003f

    /**
     * Horizontal-axis sign. Some users perceive the default mapping as
     * reversed (swing left → cursor right). +1 = default, -1 = inverted.
     */
    @Volatile
    var invertX: Boolean = false

    /** Same as invertX but for the vertical axis. +1 = default, -1 = inverted. */
    @Volatile
    var invertY: Boolean = false

    /**
     * Smoothing factor in [0,1). Output cursor delta is an exponential moving
     * average of recent raw deltas: higher = smoother but laggier (more cursor
     * "trail"), lower = snappier but jitterier. 0 disables smoothing. ~0.3 is a
     * good low-trail middle. Overwritten at runtime from the user pref.
     */
    @Volatile
    var smoothing: Float = 0.3f

    /**
     * Pointer-acceleration strength. 1.0 = tuned default, 0 = off (constant
     * speed). Scales the base accel constant in [process]. Its own field
     * (separate from the trackpad's) because air-mouse deltas are ~100× larger.
     */
    @Volatile
    var accelStrength: Float = 1.0f

    private var smoothDx = 0f
    private var smoothDy = 0f

    fun start() {
        val sensor = rotationSensor ?: run {
            Log.w(TAG, "No game rotation vector sensor available")
            return
        }
        initialized = false
        smoothDx = 0f
        smoothDy = 0f
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        Log.d(TAG, "Air mouse started")
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        initialized = false
        Log.d(TAG, "Air mouse stopped")
    }

    /** Force a recalibration — useful if drift accumulates or user repositions. */
    fun recalibrate() {
        initialized = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)

        val yaw = orientation[0]    // azimuth (left/right rotation)
        val pitch = orientation[1]  // pitch (up/down)

        if (!initialized) {
            lastYaw = yaw
            lastPitch = pitch
            initialized = true
            return
        }

        var dYaw = yaw - lastYaw
        var dPitch = pitch - lastPitch

        // Unwrap angular wraparound near ±π
        if (dYaw > Math.PI) dYaw -= (2 * Math.PI).toFloat()
        if (dYaw < -Math.PI) dYaw += (2 * Math.PI).toFloat()

        // Deadzone WITH residual carry. A per-frame delta below the sensor-
        // noise floor isn't emitted — but we also DON'T advance last* for that
        // axis (see the conditional updates below), so a slow, steady rotation
        // keeps accumulating across frames until it crosses the threshold.
        // The old code zeroed the delta AND advanced last* unconditionally,
        // discarding that residual every frame: slow precise aiming produced
        // nothing until one frame finally cleared the floor, then jumped a full
        // deadzone's worth at once — the cursor appeared to snap to a grid.
        // (Fast motion cleared the floor each frame, so it never showed there.)
        if (abs(dYaw) < deadzone) dYaw = 0f else lastYaw = yaw
        if (abs(dPitch) < deadzone) dPitch = 0f else lastPitch = pitch

        // Convert radians to cursor pixels. Phone held landscape:
        //  yaw (left/right) -> cursor x
        //  pitch (up/down)  -> cursor y (tilt up = cursor up = -y)
        //
        // Horizontal sign: users reported the previous mapping felt reversed
        // (swing left → cursor moved right), so the default is now +dYaw.
        // invertX flips it back for anyone who prefers the old direction.
        val xSign = if (invertX) -1f else 1f
        val ySign = if (invertY) -1f else 1f
        val rawDx = xSign * dYaw * sensitivity * 100f
        val rawDy = ySign * dPitch * sensitivity * 100f

        // Exponential smoothing to take the jitter out of raw sensor deltas.
        // smoothDx = a*raw + (1-a)*prev, where a = 1 - smoothing.
        val a = (1f - smoothing).coerceIn(0.05f, 1f)
        smoothDx = a * rawDx + (1f - a) * smoothDx
        smoothDy = a * rawDy + (1f - a) * smoothDy

        // Snap tiny residual to zero so the cursor fully settles.
        val outX = if (abs(smoothDx) < 0.01f) 0f else smoothDx
        val outY = if (abs(smoothDy) < 0.01f) 0f else smoothDy

        if (outX != 0f || outY != 0f) {
            // Pointer acceleration — its own scale, since the air-mouse output
            // (gyro × sensitivity × 100) is far larger than trackpad px deltas.
            // Slow aim stays precise; fast sweeps cover more screen. The base
            // constant is scaled by [accelStrength] (user slider): 1.0 = tuned
            // default, 0 = off (gain pinned to 1).
            val accel = 0.006f * accelStrength
            val accelMax = 2.0f
            val sp = kotlin.math.hypot(outX.toDouble(), outY.toDouble()).toFloat()
            val gain = (1f + accel * sp).coerceIn(1f, accelMax)
            injector.pointerMove(outX * gain, outY * gain)
        }
        // NOTE: last* is advanced per-axis in the deadzone block above (only
        // when the delta was actually consumed). Do NOT advance it again here —
        // that's the residual-discard that caused slow-motion grid snapping.
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // We don't depend on calibrated absolute orientation, so accuracy
        // changes don't require action.
    }

    companion object { private const val TAG = "AirMouseController" }
}
