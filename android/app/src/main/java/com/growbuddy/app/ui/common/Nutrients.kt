package com.growbuddy.app.ui.common

import com.growbuddy.app.data.remote.NutrientLine

// Schedules name their nutrients loosely ("BioGrow" for "Bio-Grow"), and some product names
// exist in more than one line ("Cocos A"), so names are matched on letters only and a schedule
// goes with the line that has the most of its products.

/** Unit of schedule amounts whose product isn't in a nutrient line. */
const val DEFAULT_NUTRIENT_UNIT = "ml/L"

/** Matching key of a product name: letters only, lower case. */
fun String.productKey() = lowercase().filter { it.isLetter() }

/** The line with the most products among [names] (ties: the first), or null when none matches. */
fun List<NutrientLine>.bestLineFor(names: Collection<String>): NutrientLine? {
    val keys = names.map { it.productKey() }.toSet()
    return map { line -> line to line.products.count { it.name.productKey() in keys } }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }
        ?.first
}

/** Unit of [name] in this line, or [DEFAULT_NUTRIENT_UNIT]. */
fun NutrientLine?.unitOf(name: String): String =
    this?.products?.firstOrNull { it.name.productKey() == name.productKey() }?.unit ?: DEFAULT_NUTRIENT_UNIT
