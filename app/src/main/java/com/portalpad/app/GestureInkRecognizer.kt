package com.portalpad.app

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink

/** One captured ink sample: position in the drawing canvas + capture time (ms). */
data class InkPoint(val x: Float, val y: Float, val t: Long)

/**
 * Thin wrapper around ML Kit Digital Ink Recognition for the relay's gesture-input
 * mode. Keeps the ML Kit API entirely out of the relay UI: the UI hands over raw
 * captured strokes and gets back candidate strings.
 *
 * Lifecycle: call [prepare] once when gesture mode is first entered (downloads the
 * en-US model on first run, then builds the recognizer); call [recognize] per drawn
 * letter; call [close] on teardown. ML Kit Task callbacks fire on the main thread.
 */
class GestureInkRecognizer {

    private var recognizer: DigitalInkRecognizer? = null

    /** True once the model is downloaded and the recognizer is built. */
    var ready: Boolean = false
        private set

    fun prepare(onReady: () -> Unit, onError: (String) -> Unit, onDownloading: () -> Unit = {}) {
        if (ready) {
            onReady()
            return
        }
        val identifier = runCatching {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
        }.getOrNull()
        if (identifier == null) {
            onError("Handwriting model unavailable")
            return
        }
        val model = DigitalInkRecognitionModel.builder(identifier).build()
        val manager = RemoteModelManager.getInstance()
        manager.isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (downloaded) {
                    buildRecognizer(model)
                    onReady()
                } else {
                    onDownloading()
                    manager.download(model, DownloadConditions.Builder().build())
                        .addOnSuccessListener {
                            buildRecognizer(model)
                            onReady()
                        }
                        .addOnFailureListener {
                            Log.w(TAG, "model download failed", it)
                            onError("Couldn't download handwriting model")
                        }
                }
            }
            .addOnFailureListener {
                Log.w(TAG, "model check failed", it)
                onError("Handwriting model check failed")
            }
    }

    private fun buildRecognizer(model: DigitalInkRecognitionModel) {
        recognizer = DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).build(),
        )
        ready = true
    }

    /**
     * Recognize the given strokes (each a list of timed points). Returns candidate
     * strings best-first via [onResult]; empty list on failure or if not ready.
     */
    fun recognize(strokes: List<List<InkPoint>>, onResult: (List<String>) -> Unit) {
        val r = recognizer
        if (r == null || strokes.isEmpty()) {
            onResult(emptyList())
            return
        }
        val inkBuilder = Ink.builder()
        for (stroke in strokes) {
            if (stroke.isEmpty()) continue
            val strokeBuilder = Ink.Stroke.builder()
            for (p in stroke) {
                strokeBuilder.addPoint(Ink.Point.create(p.x, p.y, p.t))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }
        r.recognize(inkBuilder.build())
            .addOnSuccessListener { result ->
                onResult(result.candidates.map { it.text })
            }
            .addOnFailureListener {
                Log.w(TAG, "recognize failed", it)
                onResult(emptyList())
            }
    }

    fun close() {
        runCatching { recognizer?.close() }
        recognizer = null
        ready = false
    }

    companion object {
        private const val TAG = "GestureInk"
    }
}
