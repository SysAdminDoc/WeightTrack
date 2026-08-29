package com.weighttrack.barcode

import androidx.camera.core.ImageProxy

/**
 * Reads a barcode out of a camera frame.
 *
 * Two implementations, one per flavour, because this is the one job where the proprietary
 * option is genuinely better and the free one is genuinely good. The Play build uses ML Kit;
 * the F-Droid build uses ZXing rather than going without a scanner, which is the feature people
 * complain loudest about losing.
 *
 * The frame is handed over and closed by the caller, so an implementation must not keep it.
 */
interface BarcodeReader {

    /** The barcode in this frame, or null when there is not one yet. */
    suspend fun read(image: ImageProxy): String?

    /** What to say about which reader this build has, on a screen that mentions it. */
    val name: String
}

/**
 * Whether a decoded string is a product barcode worth looking up.
 *
 * A camera pointed at the world finds all sorts of things. A QR code on a poster and the serial
 * number on the back of a router both decode perfectly and neither is a food.
 */
object Barcodes {

    /** The lengths the retail formats come in: EAN-8, UPC-A, EAN-13 and ITF-14. */
    private val PRODUCT_LENGTHS = setOf(8, 12, 13, 14)

    fun isProductCode(text: String?): Boolean {
        val code = text?.trim() ?: return false
        if (code.length !in PRODUCT_LENGTHS) return false
        if (!code.all { it.isDigit() }) return false
        return hasValidCheckDigit(code)
    }

    /**
     * The last digit of a retail barcode is a check digit over the rest.
     *
     * Checking it is what tells a misread apart from a real code. A scanner that hands a wrong
     * digit to a lookup gets a confident answer about a different product, which is worse than
     * no answer.
     */
    fun hasValidCheckDigit(code: String): Boolean {
        if (code.length < 8 || !code.all { it.isDigit() }) return false
        val digits = code.map { it - '0' }
        val checkDigit = digits.last()
        // Weights alternate three and one, running right to left from the digit before the
        // check digit, whatever the overall length is.
        val sum = digits.dropLast(1)
            .reversed()
            .mapIndexed { index, digit -> digit * if (index % 2 == 0) 3 else 1 }
            .sum()
        return (10 - sum % 10) % 10 == checkDigit
    }
}
