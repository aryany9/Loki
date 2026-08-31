package dev.loki.android.core.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "loki_settings")

class ThemeRepository(private val context: Context) {

    private val themeKey = stringPreferencesKey("theme_mode")
    private val firstRunKey = booleanPreferencesKey("first_run_complete")

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { preferences ->
        val raw = preferences[themeKey] ?: ThemeMode.SYSTEM.name
        try {
            ThemeMode.valueOf(raw)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[themeKey] = mode.name
        }
    }

    val isFirstRunComplete: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[firstRunKey] ?: false
    }

    suspend fun setFirstRunComplete(complete: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[firstRunKey] = complete
        }
    }
}
