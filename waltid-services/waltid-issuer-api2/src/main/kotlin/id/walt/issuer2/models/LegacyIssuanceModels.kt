package id.walt.issuer2.models

import id.walt.sdjwt.SDMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 🚧🚧🚧 WT-907 PASSTHROUGH (OUT-OF-SPEC) — compat wire models for `wallet-wltbe-credy`. 🚧🚧🚧
 *
 * These mirror the issuer-api **v1** request bodies that credy POSTs to
 * `/openid4vc/sdjwt/issue` and `/openid4vc/mdoc/issue`. credy owns its own credential-type
 * registry and dictates `vct` / `docType` / format per request (rather than referencing a
 * declared CredentialProfile). See [id.walt.issuer2.controller.LegacyIssuanceCompatController].
 *
 * TODO(WT-907): delete once credy migrates to the in-spec profile-driven API
 *  (`POST issuer2/credential-offers` with `profileId`).
 */
@Serializable
data class LegacyIssueSdJwtVcRequest(
    val issuerKey: JsonObject,
    val issuerDid: String? = null,
    val credentialConfigurationId: String,
    val vct: String,
    /** Wire format hint from credy ("dc+sd-jwt"); issuer2 only supports SD_JWT_VC, so it is ignored. */
    val credentialFormat: String? = null,
    val credentialData: JsonObject = JsonObject(emptyMap()),
    val mapping: JsonObject? = null,
    val selectiveDisclosure: SDMap? = null,
    val x5Chain: List<String>? = null,
    val authenticationMethod: String? = null,
    val standardVersion: String? = null,
)

@Serializable
data class LegacyIssueMdocRequest(
    val issuerKey: JsonObject,
    val issuerDid: String? = null,
    val credentialConfigurationId: String,
    val docType: String? = null,
    val x5Chain: List<String> = emptyList(),
    /** Namespaced mDoc data `{ namespace: { claim: value } }` — used verbatim as credentialData. */
    val mdocData: JsonObject = JsonObject(emptyMap()),
    val authenticationMethod: String? = null,
    val standardVersion: String? = null,
    /** IETF status-list reference `{ uri, idx }`; normalised to issuer2's `{ status_list: { uri, idx } }`. */
    val validityInfoStatus: JsonObject? = null,
)
