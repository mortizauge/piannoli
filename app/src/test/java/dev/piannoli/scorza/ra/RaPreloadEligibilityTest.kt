package dev.piannoli.scorza.ra

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaPreloadEligibilityTest {
    @Test fun eligible_whenAllConditionsMet() {
        assertTrue(RaPreloadEligibility.isEligible("SNES", externalIntegrationSupported = true, raLoggedIn = true))
    }
    @Test fun ineligible_whenNotEmbeddedCore() {
        assertFalse(RaPreloadEligibility.isEligible("SNES", externalIntegrationSupported = false, raLoggedIn = true))
    }
    @Test fun ineligible_whenNotLoggedIn() {
        assertFalse(RaPreloadEligibility.isEligible("SNES", externalIntegrationSupported = true, raLoggedIn = false))
    }
    @Test fun ineligible_whenPlatformNull() {
        assertFalse(RaPreloadEligibility.isEligible(null, externalIntegrationSupported = true, raLoggedIn = true))
    }
    @Test fun ineligible_whenPlatformNotRaMapped() {
        assertFalse(RaPreloadEligibility.isEligible("3DS", externalIntegrationSupported = true, raLoggedIn = true))
    }
    @Test fun platformTag_caseInsensitive() {
        assertTrue(RaPreloadEligibility.isEligible("snes", externalIntegrationSupported = true, raLoggedIn = true))
    }
}
