package id.walt.issuer2.security

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKMatcher
import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.util.X509CertUtils
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Verifies OAuth 2.0 Attestation-Based Client Authentication JWTs sent by the EUDI wallet
 * ([OAuth-Client-Attestation], [OAuth-Client-Attestation-PoP], OID4VCI 1.0 Appendix E).
 *
 * issuer2 already advertises `attest_jwt_client_auth` in its authorization-server metadata
 * (see [id.walt.openid4vci.metadata.oauth.AuthorizationServerMetadata.fromBaseUrl]); this class
 * adds the missing verification of the two headers on the token endpoint. Ported from the
 * issuer-api v1 fork (the only issuer feature genuinely absent upstream). Multipaz sends these
 * headers for real, so this is required.
 *
 * **Attestation JWT:** If the header contains **x5c**, the leaf certificate is used as the signing
 * key source (no HTTP discovery). Otherwise discovery uses
 * `GET [walletProviderBaseUrl]/.well-known/oauth-authorization-server`, then
 * `/.well-known/openid-configuration`, then `jwks_uri` + JWKS lookup by `kid`.
 *
 * **PoP JWT:** Verified with the instance key in `cnf.jwk` inside the attestation payload.
 */
object WalletClientAttestationVerifier {

    const val HEADER_CLIENT_ATTESTATION: String = "OAuth-Client-Attestation"
    const val HEADER_CLIENT_ATTESTATION_POP: String = "OAuth-Client-Attestation-PoP"

    private val metadataJson = Json { ignoreUnknownKeys = true }

    suspend fun verifyIfPresent(
        http: HttpClient,
        walletProviderBaseUrl: String,
        attestationJwt: String?,
        popJwt: String?,
    ): Unit = withContext(Dispatchers.Default) {
        if (attestationJwt.isNullOrBlank() && popJwt.isNullOrBlank()) return@withContext
        require(!attestationJwt.isNullOrBlank() && !popJwt.isNullOrBlank()) {
            "Both OAuth-Client-Attestation and OAuth-Client-Attestation-PoP must be present when using attest_jwt_client_auth"
        }

        val signedAttestation = SignedJWT.parse(attestationJwt)
        val signerKey =
            resolveClientAttestationSignerKey(http, walletProviderBaseUrl.trimEnd('/'), signedAttestation)
        verifySignedJwtWithKey(attestationJwt, signerKey, "client attestation")
        verifyPopJwt(attestationJwt, popJwt)
    }

    private suspend fun resolveClientAttestationSignerKey(
        http: HttpClient,
        walletProviderBaseUrl: String,
        signedJwt: SignedJWT,
    ): JWK {
        val chain = signedJwt.header.x509CertChain
        if (!chain.isNullOrEmpty()) {
            val leafDer = chain[0].decode()
            val cert = X509CertUtils.parseWithException(leafDer)
            return try {
                jwkFromLeafCertificate(cert)
            } catch (e: JOSEException) {
                throw BadJOSEException("Invalid wallet attestation x5c leaf certificate: ${e.message}", e)
            }
        }

        val jwksUri = resolveJwksUri(http, walletProviderBaseUrl)
        val jwkSet = fetchJwkSet(http, jwksUri)
        return lookupSigningJwkFromJwks(signedJwt, jwkSet)
    }

    private fun jwkFromLeafCertificate(cert: java.security.cert.X509Certificate): JWK =
        when (cert.publicKey.algorithm) {
            "EC" -> ECKey.parse(cert)
            "RSA" -> RSAKey.parse(cert)
            else -> throw BadJOSEException(
                "Unsupported wallet attestation leaf certificate key algorithm: ${cert.publicKey.algorithm}"
            )
        }

    private fun lookupSigningJwkFromJwks(signedJwt: SignedJWT, jwkSet: JWKSet): JWK {
        val kid = signedJwt.header.keyID
            ?: throw BadJOSEException("JWT header missing kid (client attestation)")
        val matches = JWKSelector(JWKMatcher.Builder().keyID(kid).build()).select(jwkSet)
        return matches.firstOrNull()
            ?: throw BadJOSEException("No JWK for kid=$kid in wallet provider JWKS (client attestation)")
    }

    private fun verifyPopJwt(attestationJwt: String, popJwt: String) {
        @Suppress("UNCHECKED_CAST")
        val cnf = SignedJWT.parse(attestationJwt).jwtClaimsSet.getClaim("cnf") as? Map<String, Any?>
            ?: throw BadJOSEException("Wallet attestation JWT missing 'cnf' claim")
        @Suppress("UNCHECKED_CAST")
        val jwkMap = cnf["jwk"] as? Map<String, Any?>
            ?: throw BadJOSEException("Wallet attestation JWT missing cnf.jwk")
        val jwk = JWK.parse(metadataJson.encodeToString(JsonObject.serializer(), mapToJsonObject(jwkMap)))
        verifySignedJwtWithKey(popJwt, jwk, "client attestation PoP")
    }

    private fun mapToJsonObject(m: Map<String, Any?>): JsonObject =
        JsonObject(m.mapValues { (_, v) -> anyToJson(v) })

    @Suppress("UNCHECKED_CAST")
    private fun anyToJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { anyToJson(it) })
        is Map<*, *> -> mapToJsonObject(v as Map<String, Any?>)
        else -> JsonPrimitive(v.toString())
    }

    private suspend fun resolveJwksUri(http: HttpClient, base: String): String {
        val urls = listOf(
            "$base/.well-known/oauth-authorization-server",
            "$base/.well-known/openid-configuration",
        )
        var lastDetail: String? = null
        for (url in urls) {
            val response = http.get(url)
            if (response.status != HttpStatusCode.OK) {
                lastDetail = "$url (${response.status})"
                continue
            }
            val bodyText: String = response.body()
            val root = metadataJson.parseToJsonElement(bodyText) as? JsonObject
            if (root == null) {
                lastDetail = "$url (invalid JSON)"
                continue
            }
            val jwks = root["jwks_uri"]?.jsonPrimitive?.content
            if (!jwks.isNullOrBlank()) return jwks
            lastDetail = "$url (jwks_uri missing)"
        }
        error(
            "Wallet provider metadata not found; tried: ${urls.joinToString()}. Last: $lastDetail"
        )
    }

    private suspend fun fetchJwkSet(http: HttpClient, jwksUri: String): JWKSet {
        val response = http.get(jwksUri)
        if (response.status != HttpStatusCode.OK) {
            error("JWKS request failed: $jwksUri (${response.status})")
        }
        val bodyText: String = response.body()
        return JWKSet.parse(bodyText)
    }

    private fun verifySignedJwtWithKey(jwtString: String, jwk: JWK, label: String) {
        val signedJwt = SignedJWT.parse(jwtString)
        try {
            when {
                jwk is ECKey -> {
                    val verifier = ECDSAVerifier(jwk.toECPublicKey())
                    if (!signedJwt.verify(verifier)) throw JOSEException("Invalid EC signature ($label)")
                }

                jwk is RSAKey -> {
                    val verifier = RSASSAVerifier(jwk.toRSAPublicKey())
                    if (!signedJwt.verify(verifier)) throw JOSEException("Invalid RSA signature ($label)")
                }

                else -> throw JOSEException("Unsupported JWK type for $label: ${jwk.keyType}")
            }
        } catch (e: JOSEException) {
            throw BadJOSEException("Wallet $label JWT verification failed: ${e.message}", e)
        }

        try {
            signedJwt.jwtClaimsSet
        } catch (_: Exception) {
            throw BadJOSEException("Invalid JWT claims ($label)")
        }
    }
}
