package com.example.flash.ui.theme

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { LIGHT, DARK, SYSTEM }

class ThemeRepository(private val dataStore: DataStore<Preferences>) {

    companion object {
        private val KEY_THEME   = stringPreferencesKey("theme_mode")
        private val KEY_ONBOARD = booleanPreferencesKey("onboarding_shown")
        private val KEY_LANG    = stringPreferencesKey("language")
    }

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        when (prefs[KEY_THEME]) {
            "LIGHT" -> ThemeMode.LIGHT
            "DARK"  -> ThemeMode.DARK
            else    -> ThemeMode.SYSTEM
        }
    }

    val onboardingShown: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ONBOARD] ?: false
    }

    val language: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_LANG] ?: ""
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun setOnboardingShown() {
        dataStore.edit { it[KEY_ONBOARD] = true }
    }

    suspend fun setLanguage(lang: String) {
        dataStore.edit { it[KEY_LANG] = lang }
    }
}
