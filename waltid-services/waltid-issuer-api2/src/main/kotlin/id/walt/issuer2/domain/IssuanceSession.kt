package id.walt.issuer2.domain

import id.walt.mdoc.dataelement.json.JsonObjectToCborMappingConfig
import id.walt.issuer2.notifications.IssuanceNotifications
import id.walt.openid4vci.metadata.issuer.CredentialConfiguration
import id.walt.openid4vci.offers.AuthenticationMethod
import id.walt.openid4vci.offers.CredentialOffer
import id.walt.sdjwt.SDMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

@Serializable
enum class IssuanceSessionStatus {
    ACTIVE,
    SUCCESSFUL,
    UNSUCCESSFUL,
    REJECTED_BY_USER,
    EXPIRED,
}

@Serializable
data class IssuanceSession(
    val sessionId: String,
    val profileId: String,
    val authenticationMethod: AuthenticationMethod,
    val credentialConfigurationId: String,
    val issuerKey: JsonObject,
    val credentialData: JsonObject,
    val mapping: JsonObject? = null,
    val selectiveDisclosure: SDMap? = null,
    val idTokenClaimsMapping: Map<String, String>? = null,
    val mDocNameSpacesDataMappingConfig: Map<String, JsonObjectToCborMappingConfig>? = null,
    val x5Chain: List<String>? = null,
    val issuerDid: String? = null,
    val credentialOffer: CredentialOffer? = null,
    val authorizationRequest: Map<String, List<String>>? = null,
    val externalAuthorizationState: String? = null,
    val authorizationClaims: JsonObject? = null,
    val expiresAt: Instant,
    val status: IssuanceSessionStatus = IssuanceSessionStatus.ACTIVE,
    val statusReason: String? = null,
    val issuedCredentialFormat: String? = null,
    val notifications: IssuanceNotifications? = null,
    val isClosed: Boolean = false,
    val credentialStatus: JsonElement? = null,
    /**
     * 🚧 WT-907 PASSTHROUGH (out-of-spec, compat with `wallet-wltbe-credy`) — see
     * [id.walt.issuer2.controller.LegacyIssuanceCompatController].
     *
     * When set, the credential's format/vct/docType come verbatim from the caller instead of a
     * declared [CredentialProfile] / `credential_configurations_supported` entry. This is the
     * authoritative (and persisted, restart-safe) source used at issuance time; the in-memory
     * dynamic registry in [id.walt.issuer2.service.openid4vci.MetadataService] only serves metadata
     * for wallet discovery. Null for all normal profile-driven sessions.
     */
    val inlineCredentialConfiguration: CredentialConfiguration? = null,
)
