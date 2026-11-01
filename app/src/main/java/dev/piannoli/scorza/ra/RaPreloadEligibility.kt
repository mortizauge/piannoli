package dev.piannoli.scorza.ra

object RaPreloadEligibility {
    fun isEligible(
        platformTag: String?,
        externalIntegrationSupported: Boolean,
        raLoggedIn: Boolean,
    ): Boolean {
        if (!externalIntegrationSupported || !raLoggedIn) return false
        val tag = platformTag?.uppercase() ?: return false
        return RaConsoles.MAP.containsKey(tag)
    }
}
