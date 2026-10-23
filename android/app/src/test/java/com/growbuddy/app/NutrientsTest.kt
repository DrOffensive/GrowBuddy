package com.growbuddy.app

import com.growbuddy.app.data.remote.NutrientLine
import com.growbuddy.app.data.remote.NutrientProduct
import com.growbuddy.app.ui.common.bestLineFor
import com.growbuddy.app.ui.common.unitOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NutrientsTest {
    private fun line(brand: String, vararg products: Pair<String, String>) =
        NutrientLine(0, brand, products = products.mapIndexed { i, (name, unit) -> NutrientProduct(i.toLong(), name, unit = unit) })

    private val houseAndGarden = line("House & Garden", "Cocos A" to "ml/L", "Cocos B" to "ml/L", "Bud XL" to "ml/L")
    private val plagron = line("Plagron", "Cocos A" to "ml/L", "Cocos B" to "ml/L", "Power Roots" to "ml/L", "Green Sensation" to "ml/L")
    private val bioTabs = line("BioTabs", "BioTabs Tablets" to "tabs/plant", "Bactrex" to "g/L")
    private val lines = listOf(houseAndGarden, plagron, bioTabs)

    @Test
    fun picksTheLineWithTheMostProducts() {
        assertEquals("Plagron", lines.bestLineFor(listOf("CocosA", "Cocos B", "PowerRoots", "Green Sensation"))?.brand)
        assertEquals("House & Garden", lines.bestLineFor(listOf("Cocos A", "Cocos B"))?.brand) // tie: the first
        assertNull(lines.bestLineFor(listOf("Something else")))
    }

    @Test
    fun unitsComeFromTheProduct() {
        assertEquals("tabs/plant", bioTabs.unitOf("BioTabsTablets"))
        assertEquals("g/L", bioTabs.unitOf("bactrex"))
        assertEquals("ml/L", bioTabs.unitOf("Cal-Mag"))
        assertEquals("ml/L", null.unitOf("Bactrex"))
    }
}
