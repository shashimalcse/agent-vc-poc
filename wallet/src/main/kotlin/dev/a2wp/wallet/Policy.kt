package dev.a2wp.wallet

/**
 * Default-deny policy decisions. Inputs are always verified values (the issuer from the resolved offer and the
 * signed credential, the verifier client_id authenticated from the signed request), never agent-supplied text.
 */
object PolicyEngine {

    sealed interface Decision {
        data object Allow : Decision
        data class Deny(val reason: String) : Decision
    }

    fun authorizeIssuer(policy: PolicyConfig, credentialIssuer: String): Decision =
        if (policy.allowedIssuers.any { it.issuer == credentialIssuer }) Decision.Allow
        else Decision.Deny("Issuer is not allowed by wallet policy.")

    fun authorizeCredentialType(policy: PolicyConfig, credentialIssuer: String, vct: String): Decision {
        val rule = policy.allowedIssuers.firstOrNull { it.issuer == credentialIssuer }
            ?: return Decision.Deny("Issuer is not allowed by wallet policy.")
        return if (rule.vcts.isEmpty() || vct in rule.vcts) Decision.Allow
        else Decision.Deny("Credential type is not allowed from this issuer.")
    }

    fun verifierRule(policy: PolicyConfig, verifiedClientId: String): PolicyConfig.AllowedVerifier? =
        policy.allowedVerifiers.firstOrNull { it.clientId == verifiedClientId }

    /**
     * Minimal disclosure: of the requested top-level claims, disclose those the verifier may receive.
     * Returns the claims to disclose and the requested claims that are withheld.
     */
    fun selectClaims(rule: PolicyConfig.AllowedVerifier, requested: List<String>): Pair<List<String>, List<String>> {
        val allowed = rule.claims.toSet()
        val distinct = requested.distinct()
        return distinct.filter { it in allowed } to distinct.filterNot { it in allowed }
    }

    /**
     * Whether a presentation needs user approval. With `first_presentation_to_verifier`, approval is needed unless
     * the user already approved this agent presenting to this verifier at least these claims.
     */
    fun approvalRequired(
        mode: ApprovalMode,
        previouslyApprovedClaims: Set<String>?,
        claimsToDisclose: List<String>,
    ): Boolean = when (mode) {
        ApprovalMode.ALWAYS -> true
        ApprovalMode.NEVER -> false
        ApprovalMode.FIRST_PRESENTATION_TO_VERIFIER ->
            previouslyApprovedClaims == null || !previouslyApprovedClaims.containsAll(claimsToDisclose)
    }
}
