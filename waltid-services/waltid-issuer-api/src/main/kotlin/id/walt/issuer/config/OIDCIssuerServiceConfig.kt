package id.walt.issuer.config

import id.walt.commons.config.WaltConfig
import id.walt.crypto.keys.KeySerialization
import id.walt.crypto.keys.KeyType
import id.walt.crypto.keys.jwk.JWKKey
import kotlinx.coroutines.runBlocking

data class OIDCIssuerServiceConfig(
    val baseUrl: String,
    val ciTokenKey: String = runBlocking { KeySerialization.serializeKey(JWKKey.generate(KeyType.secp256r1)) },
    /**
     * Used only when the attestation JWT omits **x5c**; JWKS is resolved via
     * `/.well-known/oauth-authorization-server`, then `openid-configuration`. The EUDI Wallet demo
     * normally includes **x5c**, so discovery is skipped.
     */
    val walletProviderBaseUrl: String = "https://wallet-provider.eudiw.dev",
) : WaltConfig()
