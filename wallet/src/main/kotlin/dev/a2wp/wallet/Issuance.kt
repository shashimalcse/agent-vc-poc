package dev.a2wp.wallet

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.SignedJWT
import eu.europa.ec.eudi.openid4vci.BatchSignOperation
import eu.europa.ec.eudi.openid4vci.BatchSigner
import eu.europa.ec.eudi.openid4vci.ClientAuthentication
import eu.europa.ec.eudi.openid4vci.Credential
import eu.europa.ec.eudi.openid4vci.CredentialConfigurationIdentifier
import eu.europa.ec.eudi.openid4vci.CredentialOffer
import eu.europa.ec.eudi.openid4vci.CredentialResponseEncryptionPolicy
import eu.europa.ec.eudi.openid4vci.EcConfig
import eu.europa.ec.eudi.openid4vci.EncryptionSupportConfig
import eu.europa.ec.eudi.openid4vci.IssuanceRequestPayload
import eu.europa.ec.eudi.openid4vci.Issuer
import eu.europa.ec.eudi.openid4vci.JwtBindingKey
import eu.europa.ec.eudi.openid4vci.OpenId4VCIConfig
import eu.europa.ec.eudi.openid4vci.ParUsage
import eu.europa.ec.eudi.openid4vci.ProofSpecification
import eu.europa.ec.eudi.openid4vci.ProofsConfig
import eu.europa.ec.eudi.openid4vci.SdJwtVcCredential
import eu.europa.ec.eudi.openid4vci.SignFunction
import eu.europa.ec.eudi.openid4vci.SignOperation
import eu.europa.ec.eudi.openid4vci.SubmissionOutcome
import eu.europa.ec.eudi.openid4vci.SupportedGrants
import eu.europa.ec.eudi.sdjwt.NimbusSdJwtOps
import eu.europa.ec.eudi.sdjwt.vc.IssuerVerificationMethod
import eu.europa.ec.eudi.sdjwt.vc.TypeMetadataPolicy
import eu.europa.ec.eudi.sdjwt.vc.X509CertificateTrust
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.Signature
import java.security.cert.X509Certificate
import java.time.Instant

/** OpenID4VCI acquisition with the pre-authorized code grant, using the EUDI library. */
class AcquireService(
    private val http: HttpClient,
    private val store: EncryptedStore,
    private val anchors: TrustAnchors,
    publicBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(AcquireService::class.java)

    private val vciConfig = OpenId4VCIConfig(
        clientAuthentication = ClientAuthentication.None("a2wp-kt"),
        authFlowRedirectionURI = URI("${publicBaseUrl.trimEnd('/')}/oid4vci/callback"),
        encryptionSupportConfig = EncryptionSupportConfig(
            credentialResponseEncryptionPolicy = CredentialResponseEncryptionPolicy.SUPPORTED,
            ecConfig = EcConfig(Curve.P_256),
            rsaConfig = null,
        ),
        parUsage = ParUsage.Never,
        proofs = ProofsConfig(
            isNoProofSupported = false,
            jwtProofWithKeyAttestation = null,
            attestationProof = null,
            jwtProofsWithoutKeyAttestation = ProofsConfig.SupportedJwtProof(setOf(JWSAlgorithm.ES256)),
        ),
        grants = SupportedGrants.PreAuthorizedCode,
    )

    /** x5c trust for issued credentials: PKIX to the anchors, and the leaf's DNS SAN must be the host of `iss`. */
    private val issuerX5cTrust = X509CertificateTrust<List<X509Certificate>> { chain, claims ->
        val iss = claims["iss"]?.jsonPrimitive?.content ?: return@X509CertificateTrust false
        val host = runCatching { URI(iss).host }.getOrNull() ?: return@X509CertificateTrust false
        anchors.validate(chain) && TrustAnchors.dnsSans(chain.first()).any { it.equals(host, ignoreCase = true) }
    }

    private val sdJwtVcVerifier = NimbusSdJwtOps.SdJwtVcVerifier(
        IssuerVerificationMethod.usingX5cOrIssuerMetadata(issuerX5cTrust, http),
        TypeMetadataPolicy.NotUsed,
        null,
    )

    suspend fun run(ctx: OperationContext, agent: AgentContext, offer: ParsedOffer) {
        ctx.processing()

        // 1. Resolve the offer and the issuer metadata. These are reads; nothing is redeemed yet.
        val credentialOffer = CredentialOffer.resolve(httpClient = http, config = vciConfig, uri = offer.libraryUri)
            .getOrElse { throw mapVciError("Could not resolve the credential offer", it) }
        val issuerId = credentialOffer.credentialIssuerIdentifier.toString()

        // 2. Policy: the issuer and every offered credential type must be allowed.
        (PolicyEngine.authorizeIssuer(agent.policy, issuerId) as? PolicyEngine.Decision.Deny)?.let {
            throw OperationFailure(ErrorCodes.DENIED, it.reason)
        }
        val offered: List<Pair<CredentialConfigurationIdentifier, String>> =
            credentialOffer.credentialConfigurationIdentifiers.map { id ->
                val conf = credentialOffer.credentialIssuerMetadata.credentialConfigurationsSupported[id]
                if (conf !is SdJwtVcCredential) {
                    throw OperationFailure(ErrorCodes.UNSUPPORTED_REQUEST, "Only SD-JWT VC (dc+sd-jwt) credentials are supported.")
                }
                (PolicyEngine.authorizeCredentialType(agent.policy, issuerId, conf.type) as? PolicyEngine.Decision.Deny)?.let {
                    throw OperationFailure(ErrorCodes.DENIED, it.reason)
                }
                id to conf.type
            }
        val preAuth = credentialOffer.grants?.preAuthorizedCode()
            ?: throw OperationFailure(ErrorCodes.UNSUPPORTED_REQUEST, "Only the pre-authorized code grant is supported.")

        // 3. A transaction code is entered by the user on the wallet's page, never relayed by the agent.
        var txCode: String? = null
        preAuth.txCode?.let { tx ->
            val outcome = ctx.interact(
                ApprovalPrompt.Acquire(
                    issuer = issuerId,
                    credentialConfigurations = offered.map { it.first.value },
                    txCodeRequired = true,
                    txCodeLength = tx.length,
                    txCodeNumeric = tx.inputMode.name == "NUMERIC",
                    txCodeDescription = tx.description,
                ),
            )
            txCode = (outcome as InteractionOutcome.Approved).txCode
        }

        // 4. Commit point (A2WP -01): redeeming the pre-authorized code cannot be undone, so from here cancellation
        //    is refused and a resubmission returns this operation. Then request each credential with a fresh
        //    P-256 holder key.
        val issuer = Issuer.make(vciConfig, credentialOffer, http).getOrElse { throw mapVciError("Issuer negotiation failed", it) }.first
        ctx.commit()
        var authorized = with(issuer) {
            authorizeWithPreAuthorizationCode(txCode).getOrElse { throw mapVciError("Token request failed", it) }
        }
        ctx.processing()

        val toStore = mutableListOf<StoredCredential>()
        for ((configId, expectedVct) in offered) {
            val holderKey = ECKeyGenerator(Curve.P_256).keyID(OperationManager.randomId(12)).generate()
            val proof = ProofSpecification.JwtProofsWithoutKeyAttestation(singleKeySigner(holderKey))
            val (next, outcome) = with(issuer) {
                authorized.request(IssuanceRequestPayload.ConfigurationBased(configId), proof)
                    .getOrElse { throw mapVciError("Credential request failed", it) }
            }
            authorized = next
            val credentials = when (outcome) {
                is SubmissionOutcome.Success -> outcome.credentials
                is SubmissionOutcome.Deferred -> throw OperationFailure(ErrorCodes.UNSUPPORTED_REQUEST, "Deferred issuance is not supported.")
                is SubmissionOutcome.Failed -> throw mapVciError("The issuer refused the credential request", outcome.error)
            }
            for (c in credentials) {
                val sdJwt = (c.credential as? Credential.Str)?.value
                    ?: throw OperationFailure(ErrorCodes.PROCESSING_ERROR, "The issuer returned a credential in an unexpected form.")
                val checked = verifyIssued(sdJwt, issuerId, expectedVct, holderKey, agent)
                toStore += StoredCredential(
                    handle = "cred_" + OperationManager.randomId(),
                    agentId = agent.agentId,
                    principalId = agent.principalId,
                    format = "dc+sd-jwt",
                    vct = expectedVct,
                    issuer = issuerId,
                    credential = sdJwt,
                    holderKey = holderKey.toJSONString(),
                    storedAt = Instant.now().toString(),
                    expiresAt = checked.expiresAt?.toString(),
                )
            }
        }
        if (toStore.isEmpty()) throw OperationFailure(ErrorCodes.PROCESSING_ERROR, "The issuer returned no credentials.")

        // 5. Keep the credentials only now that every one of them was obtained and validated. No continuation.
        withContext(Dispatchers.IO) { store.addCredentials(toStore) }
        log.info("acquired {} credential(s) from {} for agent {}", toStore.size, issuerId, agent.agentId)
        ctx.complete()
    }

    private data class Checked(val expiresAt: Instant?)

    /** Verifies an issued SD-JWT VC before the commit point: issuer signature and trust, iss, vct, cnf. */
    private suspend fun verifyIssued(sdJwt: String, issuerId: String, expectedVct: String, holderKey: ECKey, agent: AgentContext): Checked {
        val verified = sdJwtVcVerifier.verify(sdJwt).getOrElse {
            log.warn("issued credential failed verification: {}", it.javaClass.simpleName)
            throw OperationFailure(ErrorCodes.PROCESSING_ERROR, "The issued credential failed signature or trust verification.")
        }
        val claims = verified.jwt.jwtClaimsSet
        if (claims.issuer != issuerId) {
            throw OperationFailure(ErrorCodes.PROCESSING_ERROR, "The credential's iss does not match the credential issuer.")
        }
        (PolicyEngine.authorizeIssuer(agent.policy, claims.issuer) as? PolicyEngine.Decision.Deny)?.let {
            throw OperationFailure(ErrorCodes.DENIED, it.reason)
        }
        val vct = claims.getStringClaim("vct")
        if (vct != expectedVct) throw OperationFailure(ErrorCodes.PROCESSING_ERROR, "The credential's vct is not the offered type.")
        val cnfJwk = runCatching {
            @Suppress("UNCHECKED_CAST")
            JWK.parse(claims.getJSONObjectClaim("cnf")["jwk"] as Map<String, Any>)
        }.getOrNull() ?: throw OperationFailure(ErrorCodes.PROCESSING_ERROR, "The credential is not bound to a holder key.")
        if (cnfJwk.computeThumbprint() != holderKey.toPublicJWK().computeThumbprint()) {
            throw OperationFailure(ErrorCodes.PROCESSING_ERROR, "The credential is bound to a different key.")
        }
        val exp = claims.expirationTime?.toInstant()
        if (exp != null && exp.isBefore(Instant.now())) {
            throw OperationFailure(ErrorCodes.PROCESSING_ERROR, "The issued credential is already expired.")
        }
        return Checked(exp)
    }

    companion object {
        /** A one-key JWT proof signer. Returns DER signatures; the library transcodes them to JWS form. */
        fun singleKeySigner(key: ECKey): BatchSigner<JwtBindingKey> = object : BatchSigner<JwtBindingKey> {
            override val javaAlgorithm: String = "SHA256withECDSA"
            override suspend fun authenticate(): BatchSignOperation<JwtBindingKey> {
                val priv = key.toECPrivateKey()
                val fn = SignFunction { input ->
                    Signature.getInstance("SHA256withECDSA").run {
                        initSign(priv)
                        update(input)
                        sign()
                    }
                }
                return BatchSignOperation(listOf(SignOperation(fn, JwtBindingKey.Jwk(key.toPublicJWK()))))
            }

            override suspend fun release(signOps: BatchSignOperation<JwtBindingKey>?) = Unit
        }

        /** Maps library failures to fixed, secret-free details. */
        fun mapVciError(what: String, e: Throwable): OperationFailure {
            if (e is OperationFailure) return e
            if (generateSequence(e) { it.cause }.any { it is java.net.UnknownHostException && it.message?.startsWith("blocked") == true }) {
                return OperationFailure(ErrorCodes.DENIED, "$what (address blocked by the wallet's network policy).")
            }
            val inner = (e as? eu.europa.ec.eudi.openid4vci.CredentialOfferRequestException)?.error
            val name = (inner ?: e).javaClass.simpleName.ifBlank { "Error" }
            val code = when {
                generateSequence(e) { it.cause }.any { it is ResponseTooLarge } -> ErrorCodes.PROCESSING_ERROR
                name.contains("UnsupportedGrants") || name.contains("Unsupported") -> ErrorCodes.UNSUPPORTED_REQUEST
                else -> ErrorCodes.PROCESSING_ERROR
            }
            return OperationFailure(code, "$what ($name).")
        }
    }
}
