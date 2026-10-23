package com.growbuddy.app

import com.growbuddy.app.ui.strains.vendorMonogram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VendorMonogramTest {
    @Test
    fun catalogVendors() {
        assertEquals("GHS", vendorMonogram("Green House Seeds"))
        assertEquals("DP", vendorMonogram("Dutch Passion"))
        assertEquals("SS", vendorMonogram("Super Seeds"))
        assertEquals("CPH", vendorMonogram("CPH Seeds"))
        assertEquals("THS", vendorMonogram("T.H. Seeds"))
        assertEquals("DNA", vendorMonogram("DNA Genetics"))
        assertEquals("BF", vendorMonogram("Barney’s Farm"))
    }

    @Test
    fun oddInput() {
        assertEquals("S", vendorMonogram("seedsman"))
        assertEquals("SHS", vendorMonogram("Sweet-Home Seeds Co"))
        assertNull(vendorMonogram(null))
        assertNull(vendorMonogram("  "))
        assertNull(vendorMonogram("420"))
    }
}
