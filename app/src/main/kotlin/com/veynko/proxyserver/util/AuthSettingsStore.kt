package com.veynko.proxyserver.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.authSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auth_settings"
)

data class AuthSettings(
    val authEnabled: Boolean,
    val username: String,
    val password: String
)

object AuthSettingsStore {

    private val KEY_AUTH_ENABLED = booleanPreferencesKey("auth_enabled")
    private val KEY_USERNAME = stringPreferencesKey("username")
    private val KEY_PASSWORD = stringPreferencesKey("password")

    fun flow(context: Context): Flow<AuthSettings> {
        return context.authSettingsDataStore.data.map { prefs ->
            AuthSettings(
                authEnabled = prefs[KEY_AUTH_ENABLED] ?: false,
                username = prefs[KEY_USERNAME] ?: "",
                password = prefs[KEY_PASSWORD] ?: ""
            )
        }
    }

    suspend fun setAuthEnabled(context: Context, enabled: Boolean) {
        context.authSettingsDataStore.edit { it[KEY_AUTH_ENABLED] = enabled }
    }

    suspend fun setUsername(context: Context, username: String) {
        context.authSettingsDataStore.edit { it[KEY_USERNAME] = username }
    }

    suspend fun setPassword(context: Context, password: String) {
        context.authSettingsDataStore.edit { it[KEY_PASSWORD] = password }
    }
}
