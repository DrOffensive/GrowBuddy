package com.growbuddy.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.growbuddy.app.AppContainer
import com.growbuddy.app.R
import com.growbuddy.app.data.remote.apiCall
import com.growbuddy.app.data.settings.ServerConfig
import com.growbuddy.app.data.settings.SettingsStore
import com.growbuddy.app.ui.common.appContainer
import com.growbuddy.app.ui.theme.Palette
import kotlinx.coroutines.launch

class SetupViewModel(private val container: AppContainer) : ViewModel() {
    var url by mutableStateOf("")
    var token by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Checks /health and an authenticated /status before saving, so a wrong token is caught here. */
    fun connect() {
        val config = ServerConfig(SettingsStore.normalizeUrl(url), token.trim())
        busy = true
        error = null
        viewModelScope.launch {
            apiCall {
                val api = container.apiProvider.apiFor(config)
                api.health()
                api.status()
            }.onSuccess { container.settings.save(config) }
                .onFailure { error = it.message }
            busy = false
        }
    }
}

@Composable
fun SetupScreen() {
    val container = appContainer()
    val vm = viewModel { SetupViewModel(container) }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Icon(Icons.Outlined.Spa, contentDescription = null, tint = Palette.Green, modifier = Modifier.size(56.dp))
        Text("GrowBuddy", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = vm.url,
            onValueChange = { vm.url = it },
            label = { Text(stringResource(R.string.setup_url)) },
            placeholder = { Text("http://growbuddy:8000") },
            supportingText = { Text(stringResource(R.string.setup_url_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        var showToken by rememberSaveable { mutableStateOf(false) }
        OutlinedTextField(
            value = vm.token,
            onValueChange = { vm.token = it },
            label = { Text(stringResource(R.string.setup_token)) },
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        if (showToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (showToken) stringResource(R.string.setup_hide_token) else stringResource(R.string.setup_show_token),
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = vm::connect,
            enabled = !vm.busy && vm.url.isNotBlank() && vm.token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (vm.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.setup_connect))
        }
    }
}
