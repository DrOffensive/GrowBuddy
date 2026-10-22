package com.growbuddy.app.ui.common

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Translated strings for code outside composables: view models, the data layer, notifications
 * and widgets. Composables use stringResource() instead. The texts live in res/values/strings.xml and res/values-<code>/strings.xml
 * (docs/LOCALIZATION.md). Unit tests install a source that reads values/strings.xml.
 */
object AppText {
    interface Source {
        fun string(@StringRes id: Int, args: Array<out Any>): String
        fun plural(@PluralsRes id: Int, count: Int, args: Array<out Any>): String
    }

    @Volatile
    var source: Source? = null

    /** Reads through the application's current resources, so a language switch takes effect. */
    fun init(context: Context) {
        val app = context.applicationContext
        source = object : Source {
            override fun string(id: Int, args: Array<out Any>) = app.resources.getString(id, *args)
            override fun plural(id: Int, count: Int, args: Array<out Any>) = app.resources.getQuantityString(id, count, *args)
        }
    }
}

fun appString(@StringRes id: Int, vararg args: Any): String =
    checkNotNull(AppText.source) { "AppText.init() not called" }.string(id, args)

fun appPlural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    checkNotNull(AppText.source) { "AppText.init() not called" }.plural(id, count, args)
