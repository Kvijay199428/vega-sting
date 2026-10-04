package com.vega.sting.legal

/**
 * Pure, framework-free model of the user's legal consent.
 *
 * Acceptance is a pure predicate so it can be unit tested without Android or
 * DataStore, matching the approach already used by [com.vega.sting.updater.UpdateChecker].
 */

/** Document versions bundled with this build. */
const val TERMS_VERSION = "1.1"
const val PRIVACY_POLICY_VERSION = "1.1"

const val TERMS_URL =
    "https://github.com/Kvijay199428/VEGA-STING/blob/main/TERMS_AND_CONDITIONS.md"
const val PRIVACY_POLICY_URL =
    "https://github.com/Kvijay199428/VEGA-STING/blob/main/PRIVACY_POLICY.md"

data class LegalConsentState(
    val termsAccepted: Boolean = false,
    val privacyPolicyAcknowledged: Boolean = false,
    val termsVersion: String? = null,
    val privacyPolicyVersion: String? = null,
    val acceptedAt: Long? = null
) {
    val isComplete: Boolean
        get() = isAccepted(termsAccepted, privacyPolicyAcknowledged, termsVersion, privacyPolicyVersion)
}

/**
 * Consent holds only when both affirmative flags are set *and* both were given
 * against the versions shipped in this build. A version change therefore
 * re-collects consent instead of silently carrying an old acceptance forward.
 */
fun isAccepted(
    termsAccepted: Boolean,
    privacyPolicyAcknowledged: Boolean,
    termsVersion: String?,
    privacyPolicyVersion: String?
): Boolean = termsAccepted &&
    privacyPolicyAcknowledged &&
    termsVersion == TERMS_VERSION &&
    privacyPolicyVersion == PRIVACY_POLICY_VERSION
