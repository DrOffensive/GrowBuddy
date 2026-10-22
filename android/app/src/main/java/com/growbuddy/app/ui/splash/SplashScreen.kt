package com.growbuddy.app.ui.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.growbuddy.app.R
import com.growbuddy.app.ui.common.appString

/** 0.1.6 splash: the GrowBuddy logo with "Grow better bud" on white. */
@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.logo_growbuddy),
            contentDescription = appString(R.string.splash_logo),
            modifier = Modifier.fillMaxWidth(0.82f).widthIn(max = 460.dp),
        )
    }
}
