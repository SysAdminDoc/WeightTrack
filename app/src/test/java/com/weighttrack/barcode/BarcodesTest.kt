package com.weighttrack.barcode

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BarcodesTest {

    @Test
    fun `real product codes pass their own check digit`() {
        // Nutella, a tin of beans, and a short EAN-8, all real published codes.
        assertThat(Barcodes.isProductCode("3017624010701")).isTrue()
        assertThat(Barcodes.isProductCode("5000157024671")).isTrue()
        assertThat(Barcodes.isProductCode("96385074")).isTrue()
    }

    @Test
    fun `a misread digit is caught rather than looked up`() {
        // A scanner that hands a wrong digit to a lookup gets a confident answer about a
        // different product, which is worse than no answer at all.
        assertThat(Barcodes.isProductCode("3017624010702")).isFalse()
        assertThat(Barcodes.isProductCode("3017624010711")).isFalse()
    }

    @Test
    fun `things that are not products are not offered as food`() {
        // A camera pointed at the world finds all sorts of things.
        assertThat(Barcodes.isProductCode("https://example.com")).isFalse()
        assertThat(Barcodes.isProductCode("ABC123")).isFalse()
        assertThat(Barcodes.isProductCode("12345")).isFalse()
        assertThat(Barcodes.isProductCode("")).isFalse()
        assertThat(Barcodes.isProductCode(null)).isFalse()
    }

    @Test
    fun `the check digit is worked out the same way at every length`() {
        // UPC-A is twelve digits, EAN-13 thirteen, ITF-14 fourteen, and the weights alternate
        // from the right in all of them.
        assertThat(Barcodes.hasValidCheckDigit("036000291452")).isTrue()
        assertThat(Barcodes.hasValidCheckDigit("036000291453")).isFalse()
    }
}
