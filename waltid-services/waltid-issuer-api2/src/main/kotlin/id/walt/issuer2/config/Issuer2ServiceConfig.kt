package id.walt.issuer2.config

import id.walt.commons.config.WaltConfig
import id.walt.crypto.keys.KeySerialization
import id.walt.crypto.keys.KeyType
import id.walt.crypto.keys.jwk.JWKKey
import kotlinx.coroutines.runBlocking

data class Issuer2ServiceConfig(
    val baseUrl: String,
    val ciTokenKey: String = runBlocking { KeySerialization.serializeKey(JWKKey.generate(KeyType.secp256r1)) },
    /**
     * Wallet Provider base URL used to resolve the signing key of OAuth-Client-Attestation JWTs
     * when the attestation header omits **x5c**. JWKS is discovered via
     * `/.well-known/oauth-authorization-server`, then `/.well-known/openid-configuration`.
     * The EUDI Wallet demo normally includes **x5c**, so discovery is skipped.
     * See [id.walt.issuer2.security.WalletClientAttestationVerifier].
     */
    val walletProviderBaseUrl: String = "https://wallet-provider.eudiw.dev",
) : WaltConfig()