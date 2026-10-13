package dev.a2wp.wallet

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import eu.europa.ec.eudi.openid4vp.AuthorizationRequestError
import eu.europa.ec.eudi.openid4vp.Client
import eu.europa.ec.eudi.openid4vp.Consensus
import eu.europa.ec.eudi.openid4vp.DispatchOutcome
import eu.europa.ec.eudi.openid4vp.ErrorDispatchDetails
import eu.europa.ec.eudi.openid4vp.HttpError
import eu.europa.ec.eudi.openid4vp.OpenId4VPConfig
import eu.europa.ec.eudi.openid4vp.OpenId4Vp
import eu.europa.ec.eudi.openid4vp.RequestValidationError
import eu.europa.ec.eudi.openid4vp.Resolution
import eu.europa.ec.eudi.openid4vp.ResolutionError
import eu.europa.ec.eudi.openid4vp.ResolvedRequestObject
import eu.europa.ec.eudi.openid4vp.ResponseEncryptionConfiguration
import eu.europa.ec.eudi.openid4vp.ResponseMode
import eu.europa.ec.eudi.openid4vp.SignedRequestConfiguration
import eu.europa.ec.eudi.openid4vp.SupportedClientIdPrefix
import eu.europa.ec.eudi.openid4vp.SupportedRequestUriMethods
import eu.europa.ec.eudi.openid4vp.VerifiablePresentation
import eu.europa.ec.eudi.openid4vp.VerifiablePresentations
import eu.europa.ec.eudi.openid4vp.VpFormatsSupported
import eu.europa.ec.eudi.openid4vp.X509CertificateTrust
import eu.europa.ec.eudi.openid4vp.dcql.ClaimPathElement
import eu.europa.ec.eudi.openid4vp.dcql.CredentialQuery
import eu.europa.ec.eudi.openid4vp.dcql.DCQL
import eu.europa.ec.eudi.openid4vp.dcql.QueryId
import eu.europa.ec.eudi.openid4vp.dcql.metaSdJwtVc
import eu.europa.ec.eudi.sdjwt.DefaultSdJwtOps
import eu.europa.ec.eudi.sdjwt.NimbusSdJwtOps
import eu.europa.ec.eudi.sdjwt.SdJwt
import eu.europa.ec.eudi.sdjwt.vc.ClaimPath as SdClaimPath
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Date

/** OpenID4VP 1.0 presentation (DCQL, signed request, direct_post) using the EUDI library. */
class PresentService(
    private val http: HttpClient,
    private val store: EncryptedStore,
    private val anchors: TrustAnchors,
) {
    private val log = LoggerFactory.getLogger(PresentService::class.java)

    private val vpConfig = OpenId4VPConfig(
        signedRequestConfiguration = SignedRequestConfiguration(
            supportedAlgorithms = listOf(JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512),
            // GET only: request_uri_method=post would make the wallet send its metadata and a wallet_nonce.
            supportedRequestUriMethods = SupportedRequestUriMethods.Get,
        ),
        responseEncryptionConfiguration = ResponseEncryptionConfiguration.NotSupported,
        vpFormatsSupported = VpFormatsSupported(sdJwtVc = VpFormatsSupported.SdJwtVc.HAIP),
        supportedClientIdPrefixes = listOf(
            SupportedClientIdPrefix.X509SanDns(X509CertificateTrust { chain -> anchors.validate(chain) }),
            SupportedClientIdPrefix.X509Hash(X509CertificateTrust { chain -> anchors.validate(chain) }),
        ),
    )

    private val openId4Vp = OpenId4Vp.overRedirects(vpConfig, http)

    suspend fun run(ctx: OperationContext, agent: AgentContext, req: ParsedPresentationRequest) {
        ctx.processing()

        // 1. Fetch and validate the signed request. The library authenticates client_id against the x5c chain.
        val request = when (val r = openId4Vp.resolveRequestUri(req.url)) {
            is Resolution.Success -> r.requestObject
            is Resolution.Invalid -> throw mapResolutionError(r.error)
        }

        // 2. Authorize the verified verifier identity, never the agent's text.
        val verifiedClientId = request.client.id.clientId
        val cert = when (val c = request.client) {
            is Client.X509SanDns -> c.cert
            is Client.X509Hash -> c.cert
            else -> throw OperationFailure(ErrorCodes.UNSUPPORTED_PROTOCOL, "Only x509_san_dns and x509_hash verifiers are supported.")
        }
        val rule = PolicyEngine.verifierRule(agent.policy, verifiedClientId)
            ?: throw OperationFailure(ErrorCodes.POLICY_DENIED, "Verifier $verifiedClientId is not allowed by wallet policy.")
        if (request.responseMode !is ResponseMode.DirectPost) {
            throw OperationFailure(ErrorCodes.UNSUPPORTED_PROTOCOL, "Only response_mode direct_post is supported.")
        }
        if (!request.transactionData.isNullOrEmpty()) {
            throw OperationFailure(ErrorCodes.UNSUPPORTED_PROTOCOL, "transaction_data is not supported.")
        }

        // 3. Match the DCQL query against this agent's credentials, with minimal disclosure.
        val plan = planPresentation(agent, request.query, rule)

        // 4. Approval (first presentation to this verifier, or more claims than approved before).
        val claimsAll = plan.items.flatMap { it.disclose }.distinct()
        val approved = store.approvedClaims(agent.agentId, verifiedClientId)
        if (PolicyEngine.approvalRequired(agent.policy.requireApproval, approved, claimsAll)) {
            val prompt = ApprovalPrompt.Present(
                verifierClientId = verifiedClientId,
                verifierCertSubject = cert.subjectX500Principal.name,
                verifierConfiguredName = rule.name,
                credentialVct = plan.items.joinToString(", ") { it.credential.vct },
                claims = plan.items.flatMap { it.shownValues },
                withheldClaims = plan.items.flatMap { it.withheld }.distinct(),
            )
            when (ctx.interact(prompt)) {
                is InteractionOutcome.Approved -> withContext(Dispatchers.IO) {
                    store.recordApproval(agent.agentId, verifiedClientId, claimsAll)
                }
                else -> throw OperationAborted()
            }
        }

        // 5. Build the presentations (selected disclosures + KB-JWT bound to nonce and client_id).
        val vps = plan.items.associate { item ->
            QueryId(item.queryId) to listOf(VerifiablePresentation.Generic(buildSdJwtPresentation(item, request)))
        }
        val consensus = Consensus.PositiveConsensus(VerifiablePresentations(vps))

        // 6. Commit point: after this the presentation may reach the verifier; cancellation is refused.
        ctx.commit()
        val outcome = try {
            openId4Vp.dispatch(request, consensus, null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("presentation post failed after commit: {}", e.javaClass.simpleName)
            throw OperationFailure(ErrorCodes.OUTCOME_UNKNOWN, "Sending the presentation failed; the verifier may or may not have received it.")
        }
        when (outcome) {
            is DispatchOutcome.VerifierResponse.Accepted -> {
                log.info("presented to {} for agent {}", verifiedClientId, agent.agentId)
                ctx.complete(buildJsonObject {
                    put("verifier", verifierDisplayName(verifiedClientId))
                    outcome.redirectURI?.let { put("redirect_uri", it.toString()) }
                })
            }
            is DispatchOutcome.VerifierResponse.Rejected ->
                throw OperationFailure(ErrorCodes.EXTERNAL_PROTOCOL_ERROR, "The verifier rejected the presentation.")
            is DispatchOutcome.RedirectURI ->
                throw OperationFailure(ErrorCodes.UNSUPPORTED_PROTOCOL, "Redirect-based response modes are not supported.")
        }
    }

    data class PlanItem(
        val queryId: String,
        val credential: StoredCredential,
        val disclose: List<String>,
        val withheld: List<String>,
        val shownValues: List<Pair<String, String>>,
    )

    data class Plan(val items: List<PlanItem>)

    /**
     * Matches a DCQL query against the credentials of THIS agent only (the wallet is the agent's wallet: a credential
     * belongs to the agent whose API key acquired it). If several match, the most recently acquired one is used.
     */
    internal fun planPresentation(agent: AgentContext, dcql: DCQL, rule: PolicyConfig.AllowedVerifier): Plan {
        val queries = dcql.credentials.value.associateBy { it.id.value }
        val stored = store.credentialsFor(agent.agentId)
        val now = Instant.now()

        fun planFor(q: CredentialQuery): PlanItem? {
            if (q.format.value != "dc+sd-jwt") return null
            val vcts = q.metaSdJwtVc?.vctValues
            val candidates = stored.filter { c ->
                c.format == "dc+sd-jwt" &&
                    (vcts == null || c.vct in vcts) &&
                    PolicyEngine.authorizeIssuer(agent.policy, c.issuer) == PolicyEngine.Decision.Allow &&
                    (c.expiresAt == null || Instant.parse(c.expiresAt).isAfter(now))
            }
            val cred = candidates.maxByOrNull { Instant.parse(it.storedAt) } ?: return null

            // Requested top-level claims. Nested or array paths are not disclosed (withheld).
            val claimNames = mutableListOf<String>()
            val unsupported = mutableListOf<String>()
            val claimsById = mutableMapOf<String, String>()
            q.claims.orEmpty().forEach { cq ->
                val p = cq.path.value
                val name = (p.singleOrNull() as? ClaimPathElement.Claim)?.name
                if (name == null) unsupported += p.joinToString(".") else {
                    claimNames += name
                    cq.id?.let { claimsById[it.value] = name }
                }
            }
            var requested: List<String> = claimNames
            val sets = q.claimSets
            if (sets != null) {
                // Choose the first claim set whose claims are all allowed for this verifier.
                val pick = sets.map { s -> s.value.mapNotNull { claimsById[it.value] } }
                    .firstOrNull { names -> names.all { it in rule.claims } } ?: return null
                requested = pick
            }
            val (disclose, withheld) = PolicyEngine.selectClaims(rule, requested)
            val (values, always) = claimValues(cred)
            val shown = values.filterKeys { it in disclose || it in always }.map { (k, v) -> k to v }
            return PlanItem(q.id.value, cred, disclose, withheld + unsupported, shown)
        }

        val selectedIds: List<String> = dcql.credentialSets?.value?.let { sets ->
            sets.filter { it.required ?: true }.map { set ->
                set.options.map { opt -> opt.value.map { it.value } }
                    .firstOrNull { ids -> ids.all { id -> queries[id]?.let(::planFor) != null } }
                    ?: throw OperationFailure(ErrorCodes.NO_MATCHING_CREDENTIAL, "No stored credential satisfies the request.")
            }.flatten().distinct()
        } ?: queries.keys.toList()

        val items = selectedIds.map { id ->
            planFor(queries.getValue(id))
                ?: throw OperationFailure(ErrorCodes.NO_MATCHING_CREDENTIAL, "No stored credential satisfies the request.")
        }
        return Plan(items)
    }


    private suspend fun buildSdJwtPresentation(item: PlanItem, request: ResolvedRequestObject): String {
        val sdJwt = DefaultSdJwtOps.unverifiedIssuanceFrom(item.credential.credential).getOrThrow()
        val (_, perClaim) = with(DefaultSdJwtOps) { sdJwt.recreateClaimsAndDisclosuresPerClaim() }
        val wanted = item.disclose.map { SdClaimPath.claim(it) }.toSet()
        val disclosures = perClaim.filterKeys { it in wanted }.values.flatten().distinct()
        val presentation = SdJwt(sdJwt.jwt, disclosures)
        val holderKey = JWK.parse(item.credential.holderKey) as ECKey
        val kb = NimbusSdJwtOps.kbJwtIssuer(ECDSASigner(holderKey), JWSAlgorithm.ES256, holderKey.toPublicJWK()) {
            audience(request.client.id.clientId)
            claim("nonce", request.nonce)
            issueTime(Date())
        }
        return with(DefaultSdJwtOps) { presentation.serializeWithKeyBinding(kb).getOrThrow() }
    }

    private fun mapResolutionError(e: AuthorizationRequestError): OperationFailure {
        val name = e.javaClass.simpleName
        return when (e) {
            is HttpError, is ResolutionError.UnableToFetchRequestObject -> {
                val cause = (e as? HttpError)?.cause ?: (e as ResolutionError.UnableToFetchRequestObject).cause
                if (generateSequence(cause) { it.cause }.any { it is java.net.UnknownHostException && it.message?.startsWith("blocked") == true }) {
                    OperationFailure(ErrorCodes.POLICY_DENIED, "The request URL is blocked by the wallet's network policy.")
                } else {
                    OperationFailure(ErrorCodes.EXTERNAL_PROTOCOL_ERROR, "Could not fetch the request object ($name).")
                }
            }
            is RequestValidationError.UnsupportedClientIdPrefix,
            is RequestValidationError.UnsupportedResponseMode,
            is RequestValidationError.UnsupportedRequestUriMethod,
            is RequestValidationError.UnsupportedResponseType,
            is RequestValidationError.UnsupportedQueryFormats,
            is ResolutionError.ClientVpFormatsNotSupportedFromWallet,
            -> OperationFailure(ErrorCodes.UNSUPPORTED_PROTOCOL, "The request uses an unsupported feature ($name).")
            is RequestValidationError.InvalidJarJwt,
            is RequestValidationError.InvalidClientId,
            -> OperationFailure(ErrorCodes.POLICY_DENIED, "The verifier could not be authenticated ($name).")
            else -> OperationFailure(ErrorCodes.EXTERNAL_PROTOCOL_ERROR, "The request is invalid ($name).")
        }
    }

    companion object {
        private val TECHNICAL_CLAIMS = setOf("iss", "iat", "nbf", "exp", "cnf", "vct", "status", "_sd_alg", "jti", "sub")

        fun verifierDisplayName(clientId: String): String = clientId.substringAfter(':', clientId)

        /**
         * Claims of a stored credential as display strings (for the approval and result pages only), and the names of the
         * claims that are always disclosed because they are not selectively disclosable.
         */
        fun claimValues(c: StoredCredential): Pair<Map<String, String>, Set<String>> {
            val sdJwt = DefaultSdJwtOps.unverifiedIssuanceFrom(c.credential).getOrThrow()
            val (claims, perClaim) = with(DefaultSdJwtOps) { sdJwt.recreateClaimsAndDisclosuresPerClaim() }
            val visible = claims.filterKeys { it !in TECHNICAL_CLAIMS }
            val always = visible.keys.filter { perClaim[SdClaimPath.claim(it)].isNullOrEmpty() }.toSet()
            return visible.mapValues { (_, v) -> if (v is JsonPrimitive) v.content else v.toString() } to always
        }
    }
}
