package app.cmuxpocket

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test

class InvertedQrCodeTest {

    @Test
    fun testInvertedQrCodeDecoding() {
        val testContent = "cmux-pocket://import?v=1&host=jimdemacbook-pro.taildb6be2.ts.net&port=443&tls=true&token=6c28eb933040d68ac766ce870e8156339e1f3fe5df47b8b154c223eadcdff3bb"
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(testContent, BarcodeFormat.QR_CODE, 300, 300)

        val width = bitMatrix.width
        val height = bitMatrix.height

        // Standard: black modules (0xFF000000) on white background (0xFFFFFFFF)
        val standardPixels = IntArray(width * height)
        // Inverted: white modules (0xFFFFFFFF) on black background (0xFF000000)
        val invertedPixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val isDark = bitMatrix.get(x, y)
                standardPixels[y * width + x] = if (isDark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                invertedPixels[y * width + x] = if (isDark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            }
        }

        val reader = MultiFormatReader().apply {
            setHints(mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            ))
        }

        // Standard pixels: standard binarizer works
        val standardSource = RGBLuminanceSource(width, height, standardPixels)
        val standardResult = reader.decodeWithState(BinaryBitmap(HybridBinarizer(standardSource)))
        assertEquals(testContent, standardResult.text)
        reader.reset()

        // Inverted pixels: standard binarizer fails
        val invertedSource = RGBLuminanceSource(width, height, invertedPixels)
        try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(invertedSource)))
            fail("Expected NotFoundException for inverted QR code without luminance inversion")
        } catch (_: NotFoundException) {
            // Expected
        }
        reader.reset()

        // Inverted pixels with source.invert(): SUCCEEDS!
        val invertedResult = reader.decodeWithState(BinaryBitmap(HybridBinarizer(invertedSource.invert())))
        assertEquals(testContent, invertedResult.text)
    }
}
