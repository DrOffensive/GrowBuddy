package com.growbuddy.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.growbuddy.app.data.widget.EnvironmentWidget
import com.growbuddy.app.ui.navigation.GrowBuddyRoot
import kotlinx.coroutines.launch
import com.growbuddy.app.ui.theme.GrowBuddyTheme

class MainActivity : ComponentActivity() {
    /** Set by a notification tap (growbuddy://alerts) or the home widget's quick log (growbuddy://log); cleared once that screen is open. */
    private var deepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) deepLink = intent.deepLink()
        val container = (application as GrowBuddyApp).container
        container.appPush.refresh()
        container.appScope.launch { EnvironmentWidget.refresh(this@MainActivity, container.apiProvider) }
        setContent {
            GrowBuddyTheme {
                // Surface provides the theme's content colour to screens outside a Scaffold (setup).
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GrowBuddyRoot(deepLink = deepLink, onDeepLinkOpened = { deepLink = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.deepLink()?.let { deepLink = it }
    }

    private fun Intent.deepLink() = data?.takeIf { it.scheme == "growbuddy" }?.host
}
