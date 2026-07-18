package com.portalpad.app.qr

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Pure-Java ZXing decode over a CameraX YUV frame. QR + common barcode formats.
 * Stateless; a single reusable reader is fine because analysis is serialized on
 * one executor. Returns the decoded text or null.
 */
object QrDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    com.google.zxing.BarcodeFormat.QR_CODE,
                    com.google.zxing.BarcodeFormat.EAN_13,
                    com.google.zxing.BarcodeFormat.EAN_8,
                    com.google.zxing.BarcodeFormat.UPC_A,
                    com.google.zxing.BarcodeFormat.UPC_E,
                    com.google.zxing.BarcodeFormat.CODE_128,
                    com.google.zxing.BarcodeFormat.CODE_39,
                    com.google.zxing.BarcodeFormat.DATA_MATRIX,
                    com.google.zxing.BarcodeFormat.AZTEC,
                ),
            ),
        )
    }

    fun decode(image: ImageProxy): String? {
        if (image.format != ImageFormat.YUV_420_888 &&
            image.format != ImageFormat.YUV_422_888 &&
            image.format != ImageFormat.YUV_444_888
        ) {
            return null
        }
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val w = image.width
        val h = image.height
        val source = PlanarYUVLuminanceSource(
            data, plane.rowStride, h, 0, 0, w.coerceAtMost(plane.rowStride), h, false,
        )
        // Attempt order: as-delivered, ROTATED 90°, inverted. The rotation
        // matters for 1-D formats: the sensor buffer arrives landscape while
        // the phone is held portrait, so a horizontally-held barcode is
        // VERTICAL in the buffer — and unlike QR (rotation-invariant finder
        // patterns), 1-D readers only scan rows. That asymmetry was the field
        // report exactly: "QR works, barcodes do nothing".
        tryDecode(source)?.let { return it }
        tryDecode(rotated90(data, plane.rowStride, w.coerceAtMost(plane.rowStride), h))?.let { return it }
        return tryDecode(source.invert())
    }

    private fun tryDecode(source: com.google.zxing.LuminanceSource): String? =
        try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: Throwable) {
            null
        } finally {
            reader.reset()
        }

    /** Transpose the Y plane 90° clockwise so vertical 1-D barcodes become
     *  horizontal rows. ~300KB copy per attempted frame — analysis cadence
     *  only, and only reached when the upright pass found nothing. */
    private fun rotated90(
        data: ByteArray,
        rowStride: Int,
        w: Int,
        h: Int,
    ): PlanarYUVLuminanceSource {
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val rowBase = y * rowStride
            val destCol = h - 1 - y
            for (x in 0 until w) {
                out[x * h + destCol] = data[rowBase + x]
            }
        }
        return PlanarYUVLuminanceSource(out, h, w, 0, 0, h, w, false)
    }
}
