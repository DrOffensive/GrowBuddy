package com.growbuddy.app

import android.app.Application
import android.content.res.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.growbuddy.app.data.push.AlertNotifications
import com.growbuddy.app.data.widget.EnvironmentWidget
import com.growbuddy.app.ui.common.AppText

class GrowBuddyApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppText.init(this)
        container = AppContainer(this)
        AlertNotifications.createChannels(this)
        EnvironmentWidget.createChannel(this)
        if (container.outbox.state.value.pending.isNotEmpty()) container.scheduleSync()
    }

    /** Channel names follow the app language (Settings › Language or the system's). */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AlertNotifications.createChannels(this)
        EnvironmentWidget.createChannel(this)
    }

    /** Coil loads server images (snapshots, photos) through the authenticated client. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.apiProvider.imageClient })) }
            .build()
}
