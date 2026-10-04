package com.vega.sting.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the consent predicate. These guard the two ways consent could
 * silently go wrong: a half-recorded acceptance being treated as complete, and an
 * acceptance carrying over after a document version bump.
 */
class LegalConsentTest {

    private fun accepted(
        terms: Boolean = true,
        privacy: Boolean = true,
        termsVersion: String? = TERMS_VERSION,
        privacyVersion: String? = PRIVACY_POLICY_VERSION,
        acceptedAt: Long? = 1_700_000_000_000L
    ) = LegalConsentState(terms, privacy, termsVersion, privacyVersion, acceptedAt)

    // ---- complete acceptance ----

    @Test
    fun `both flags set against current versions is accepted`() {
        assertTrue(accepted().isComplete)
    }

    @Test
    fun `empty state is not accepted`() {
        assertFalse(LegalConsentState().isComplete)
    }

    @Test
    fun `acceptance timestamp is not required for completeness`() {
        assertTrue(accepted(acceptedAt = null).isComplete)
    }

    // ---- half acceptance must never pass ----

    @Test
    fun `terms only is not accepted`() {
        assertFalse(accepted(privacy = false).isComplete)
    }

    @Test
    fun `privacy only is not accepted`() {
        assertFalse(accepted(terms = false).isComplete)
    }

    // ---- version mismatch forces re-consent ----

    @Test
    fun `older accepted terms version is rejected`() {
        assertFalse(accepted(termsVersion = "0.9").isComplete)
    }

    @Test
    fun `older accepted privacy version is rejected`() {
        assertFalse(accepted(privacyVersion = "0.9").isComplete)
    }

    @Test
    fun `missing terms version is rejected`() {
        assertFalse(accepted(termsVersion = null).isComplete)
    }

    @Test
    fun `missing privacy version is rejected`() {
        assertFalse(accepted(privacyVersion = null).isComplete)
    }

    @Test
    fun `newer accepted version is rejected because the bundled copy is older`() {
        assertFalse(accepted(termsVersion = "1.2").isComplete)
    }

    @Test
    fun `whitespace in stored version is rejected`() {
        assertFalse(accepted(termsVersion = " 1.0 ").isComplete)
    }

    // ---- shipped versions ----

    @Test
    fun `shipped document versions are 1_1`() {
        assertEquals("1.1", TERMS_VERSION)
        assertEquals("1.1", PRIVACY_POLICY_VERSION)
    }

    // ---- predicate is independent of the state wrapper ----

    @Test
    fun `isAccepted matches the state property`() {
        assertTrue(isAccepted(true, true, TERMS_VERSION, PRIVACY_POLICY_VERSION))
        assertFalse(isAccepted(false, true, TERMS_VERSION, PRIVACY_POLICY_VERSION))
        assertFalse(isAccepted(true, true, "0.9", PRIVACY_POLICY_VERSION))
        assertFalse(isAccepted(true, true, null, null))
    }

    // ---- document metadata ----

    @Test
    fun `each document carries a distinct raw copy and canonical url`() {
        assertTrue(LegalDocument.TERMS.rawRes != LegalDocument.PRIVACY_POLICY.rawRes)
        assertTrue(LegalDocument.TERMS.url.contains("TERMS_AND_CONDITIONS.md"))
        assertTrue(LegalDocument.PRIVACY_POLICY.url.contains("PRIVACY_POLICY.md"))
        assertTrue(LegalDocument.PRIVACY_POLICY.url.startsWith("https://"))
    }

    @Test
    fun `urls point at the documented repository`() {
        assertTrue(TERMS_URL.startsWith("https://github.com/Kvijay199428/VEGA-STING/"))
        assertTrue(PRIVACY_POLICY_URL.startsWith("https://github.com/Kvijay199428/VEGA-STING/"))
    }

    @Test
    fun `state defaults to an unaccepted reading`() {
        val state = LegalConsentState()
        assertFalse(state.termsAccepted)
        assertFalse(state.privacyPolicyAcknowledged)
        assertNull(state.termsVersion)
        assertNull(state.privacyPolicyVersion)
        assertNull(state.acceptedAt)
    }
}
