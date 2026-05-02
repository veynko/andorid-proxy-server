package com.veynko.proxyserver.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.veynko.proxyserver.service.ProxyForegroundService
import com.veynko.proxyserver.util.AuthSettingsStore
import com.veynko.proxyserver.util.ConnectionLogBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds all UI state for the proxy configuration screen.
 */
data class ProxyUiState(
    val port: String = "1080",
    val authEnabled: Boolean = false,
    val username: String = "",
    val password: String = "",
    val isRunning: Boolean = false,
    val connectionLogs: List<String> = emptyList(),
    val errorMessage: String? = null
)

/**
 * ViewModel that bridges the UI and [ProxyForegroundService].
 */
class ProxyViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ProxyUiState())
    val uiState: StateFlow<ProxyUiState> = _uiState.asStateFlow()

    init {
        // Load persisted auth settings (authEnabled/username/password)
        viewModelScope.launch {
            AuthSettingsStore.flow(getApplication()).collect { settings ->
                _uiState.update {
                    it.copy(
                        authEnabled = settings.authEnabled,
                        username = settings.username,
                        password = settings.password
                    )
                }
            }
        }

        // Mirror the service running state into the UI state
        viewModelScope.launch {
            ProxyForegroundService.isRunning.collect { running ->
                _uiState.update { it.copy(isRunning = running) }
            }
        }

        // Collect connection logs emitted by the proxy server (service)
        viewModelScope.launch {
            ConnectionLogBus.events.collect { entry ->
                _uiState.update { current ->
                    val next = buildList {
                        add(entry)
                        addAll(current.connectionLogs)
                    }.take(200)
                    current.copy(connectionLogs = next)
                }
            }
        }
    }

    fun onPortChange(value: String) {
        _uiState.update { it.copy(port = value, errorMessage = null) }
    }

    fun onAuthEnabledChange(enabled: Boolean) {
        _uiState.update { it.copy(authEnabled = enabled) }

        viewModelScope.launch {
            AuthSettingsStore.setAuthEnabled(getApplication(), enabled)
        }
    }

    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value) }

        viewModelScope.launch {
            AuthSettingsStore.setUsername(getApplication(), value)
        }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value) }

        viewModelScope.launch {
            AuthSettingsStore.setPassword(getApplication(), value)
        }
    }

    /** Validates input and starts the proxy service. */
    fun startServer() {
        val state = _uiState.value
        val port = state.port.toIntOrNull()
        if (port == null || port !in 1..65535) {
            _uiState.update { it.copy(errorMessage = "Port must be between 1 and 65535") }
            return
        }
        if (state.authEnabled) {
            if (state.username.isBlank()) {
                _uiState.update { it.copy(errorMessage = "Username cannot be empty") }
                return
            }
            if (state.password.isBlank()) {
                _uiState.update { it.copy(errorMessage = "Password cannot be empty") }
                return
            }
        }

        _uiState.update { it.copy(errorMessage = null, connectionLogs = emptyList()) }
        ProxyForegroundService.start(
            context = getApplication(),
            port = port,
            authEnabled = state.authEnabled,
            username = state.username,
            password = state.password
        )
    }

    /** Stops the running proxy service. */
    fun stopServer() {
        ProxyForegroundService.stop(getApplication())
    }
}
