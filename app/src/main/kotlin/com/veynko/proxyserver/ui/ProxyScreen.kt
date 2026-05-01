package com.veynko.proxyserver.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.veynko.proxyserver.R
import com.veynko.proxyserver.ui.theme.GreenRunning
import com.veynko.proxyserver.ui.theme.RedStopped
import com.veynko.proxyserver.viewmodel.ProxyViewModel

/**
 * Main UI screen for controlling the SOCKS5 proxy server.
 */
@Composable
fun ProxyScreen(
    modifier: Modifier = Modifier,
    viewModel: ProxyViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()

    // Request POST_NOTIFICATIONS permission on Android 13+
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Title
        Text(
            text = "SOCKS5 Proxy Server",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Status indicator card
        StatusCard(isRunning = state.isRunning)

        Spacer(modifier = Modifier.height(24.dp))

        // Configuration card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {

                Text(
                    text = "Configuration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Port field
                OutlinedTextField(
                    value = state.port,
                    onValueChange = viewModel::onPortChange,
                    label = { Text("Port") },
                    placeholder = { Text("1080") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !state.isRunning,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Auth toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enable Authentication")
                    Switch(
                        checked = state.authEnabled,
                        onCheckedChange = viewModel::onAuthEnabledChange,
                        enabled = !state.isRunning
                    )
                }

                // Username / Password (visible only when auth is enabled)
                AnimatedVisibility(visible = state.authEnabled) {
                    Column {
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = state.username,
                            onValueChange = viewModel::onUsernameChange,
                            label = { Text("Username") },
                            singleLine = true,
                            enabled = !state.isRunning,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = state.password,
                            onValueChange = viewModel::onPasswordChange,
                            label = { Text("Password") },
                            singleLine = true,
                            enabled = !state.isRunning,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Error message
                if (state.errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Start / Stop button
        Button(
            onClick = {
                if (state.isRunning) viewModel.stopServer()
                else viewModel.startServer()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.isRunning) RedStopped else GreenRunning
            )
        ) {
            Text(
                text = if (state.isRunning) "Stop Server" else "Start Server",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Card that shows the current running status of the proxy server.
 */
@Composable
private fun StatusCard(isRunning: Boolean) {
    val statusText = if (isRunning) "Running" else "Stopped"
    val statusColor = if (isRunning) GreenRunning else RedStopped

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(
            containerColor = statusColor.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(
                    id = if (isRunning) R.drawable.ic_circle_filled else R.drawable.ic_circle_outline
                ),
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "Status: $statusText",
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
