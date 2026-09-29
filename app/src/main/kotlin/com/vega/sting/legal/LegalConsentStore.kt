package com.vega.sting.legal

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vega.sting.settings.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Persists legal consent in the app's existing `Context.dataStore`.
 *
 * The delegate is owned by [com.vega.sting.settings.SettingsManager]; declaring a
 * second `preferencesDataStore("settings")` for the same file would throw at
 * runtime, so this store deliberately reuses it.
 */
class LegalConsentStore(private val context: Context) {

    companion object {
        val TERMS_ACCEPTED = booleanPreferencesKey("terms_accepted")
        val PRIVACY_ACKNOWLEDGED = booleanPreferencesKey("privacy_policy_acknowledged")
        val TERMS_VERSION_KEY = stringPreferencesKey("terms_version")
        val PRIVACY_VERSION_KEY = stringPreferencesKey("privacy_policy_version")
        val ACCEPTED_AT = longPreferencesKey("accepted_at")
    }

    /**
     * Emits the stored consent, or an empty state when the store cannot be read
     * so a read failure lands on the consent screen instead of an endless loader.
     */
    val consent: Flow<LegalConsentState> = context.dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw cause
        }
        .map { prefs ->
            LegalConsentState(
                termsAccepted = prefs[TERMS_ACCEPTED] ?: false,
                privacyPolicyAcknowledged = prefs[PRIVACY_ACKNOWLEDGED] ?: false,
                termsVersion = prefs[TERMS_VERSION_KEY],
                privacyPolicyVersion = prefs[PRIVACY_VERSION_KEY],
                acceptedAt = prefs[ACCEPTED_AT]
            )
        }

    /** Single atomic write: a partially recorded acceptance is never observable. */
    suspend fun accept(now: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs ->
            prefs[TERMS_ACCEPTED] = true
            prefs[PRIVACY_ACKNOWLEDGED] = true
            prefs[TERMS_VERSION_KEY] = TERMS_VERSION
            prefs[PRIVACY_VERSION_KEY] = PRIVACY_POLICY_VERSION
            prefs[ACCEPTED_AT] = now
        }
    }
}
