package com.vega.sting.legal

import android.content.Context
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import com.vega.sting.R

/**
 * The two legal documents shipped with the app, each with an offline copy in
 * `res/raw` and a canonical GitHub URL.
 */
enum class LegalDocument(
    @StringRes val titleRes: Int,
    @RawRes val rawRes: Int,
    val url: String
) {
    TERMS(
        titleRes = R.string.legal_terms_title,
        rawRes = R.raw.terms_and_conditions,
        url = TERMS_URL
    ),
    PRIVACY_POLICY(
        titleRes = R.string.legal_privacy_title,
        rawRes = R.raw.privacy_policy,
        url = PRIVACY_POLICY_URL
    );

    fun read(context: Context): String =
        context.resources.openRawResource(rawRes).bufferedReader().use { it.readText() }
}
