package id.walt.issuer2.controller

import id.walt.issuer2.models.LegacyIssueMdocRequest
import id.walt.issuer2.models.LegacyIssueSdJwtVcRequest
import id.walt.issuer2.notifications.IssuanceNotifications
import id.walt.issuer2.service.CredentialOfferService
import id.walt.issuer2.service.openid4vci.MetadataService
import id.walt.openid4vci.metadata.issuer.CredentialConfiguration
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 🚧🚧🚧 WT-907 PASSTHROUGH (OUT-OF-SPEC) — compatibility layer for `wallet-wltbe-credy`. 🚧🚧🚧
 *
 * Replicates the issuer-api **v1** issuance endpoints that credy calls server-to-server:
 *   - `POST /openid4vc/sdjwt/issue`  (body [LegacyIssueSdJwtVcRequest])
 *   - `POST /openid4vc/mdoc/issue`   (body [LegacyIssueMdocRequest])
 * returning the credential offer URI as **text/plain** (NOT JSON), and honouring the
 * `statusCallbackUri` / `sessionTtl` headers — exactly as the v1 deployed issuer did.
 *
 * credy dictates `vct` / `docType` / format per request and owns the credential-type registry.
 * issuer2 is otherwise profile-driven (`POST issuer2/credential-offers` with `profileId`), so here
 * we build an inline [CredentialConfiguration] from the caller's values, register it for metadata
 * discovery, and create a PRE_AUTHORIZED offer whose session carries the inline config.
 *
 * TODO(WT-907): RETURN IN-SPEC. credy must declare credential types as CredentialProfiles (or via a
 *  runtime profiles API) and request issuance by `profileId`. Once credy is migrated, DELETE this
 *  controller, [CredentialOfferService.createInlineCredentialOffer],
 *  [id.walt.issuer2.domain.IssuanceSession.inlineCredentialConfiguration] and the MetadataService
 *  dynamic registry, and re-enable strict profile validation. Blocked on resolving custom/dynamic
 *  VCT (self-attested-custom-vct-be-spec.md). This whole class is intentionally out-of-spec.
 */
class LegacyIssuanceCompatController(
    private val offerService: CredentialOfferService,
    private val metadataService: MetadataService,
) {
    private val log = KotlinLogging.logger { }
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private companion object {
        const val HEADER_STATUS_CALLBACK_URI = "statusCallbackUri"
        const val HEADER_SESSION_TTL_MS = "sessionTtl"
        const val DEFAULT_EXPIRY_SECONDS = 300L
    }

    fun register(route: Route) {
        route.route("openid4vc") {
            route("sdjwt") {
                post("issue") {
                    val request = json.decodeFromJsonElement(
                        LegacyIssueSdJwtVcRequest.serializer(),
                        call.receive<JsonObject>(),
                    )
                    log.debug { "[compat] sd-jwt issue: ccid=${request.credentialConfigurationId}, vct=${request.vct}" }

                    val configuration = sdJwtConfiguration(request.vct)
                    metadataService.registerDynamicConfiguration(request.credentialConfigurationId, configuration)

                    val response = offerService.createInlineCredentialOffer(
                        credentialConfigurationId = request.credentialConfigurationId,
                        inlineConfiguration = configuration,
                        issuerKey = request.issuerKey,
                        issuerDid = request.issuerDid,
                        credentialData = request.credentialData,
                        mapping = request.mapping,
                        selectiveDisclosure = request.selectiveDisclosure,
                        x5Chain = request.x5Chain,
                        mDocNameSpacesDataMappingConfig = null,
                        credentialStatus = null, // sd-jwt status is embedded in credentialData by credy
                        notifications = statusCallbackNotifications(call),
                        expiresInSeconds = sessionTtlSeconds(call),
                        valueMode = id.walt.openid4vci.offers.CredentialOfferValueMode.BY_VALUE,
                    )
                    call.respondText(response.credentialOffer, ContentType.Text.Plain)
                }
            }

            route("mdoc") {
                post("issue") {
                    val request = json.decodeFromJsonElement(
                        LegacyIssueMdocRequest.serializer(),
                        call.receive<JsonObject>(),
                    )
                    val docType = requireNotNull(request.docType?.takeIf { it.isNotBlank() }) {
                        "docType is required for mDoc issuance"
                    }
                    log.debug { "[compat] mdoc issue: ccid=${request.credentialConfigurationId}, docType=$docType" }

                    val configuration = mdocConfiguration(docType)
                    metadataService.registerDynamicConfiguration(request.credentialConfigurationId, configuration)

                    val response = offerService.createInlineCredentialOffer(
                        credentialConfigurationId = request.credentialConfigurationId,
                        inlineConfiguration = configuration,
                        issuerKey = request.issuerKey,
                        issuerDid = request.issuerDid,
                        credentialData = request.mdocData,
                        mapping = null,
                        selectiveDisclosure = null,
                        x5Chain = request.x5Chain,
                        mDocNameSpacesDataMappingConfig = null,
                        credentialStatus = request.validityInfoStatus?.let { normalizeMdocStatus(it) },
                        notifications = statusCallbackNotifications(call),
                        expiresInSeconds = sessionTtlSeconds(call),
                        valueMode = id.walt.openid4vci.offers.CredentialOfferValueMode.BY_VALUE,
                    )
                    call.respondText(response.credentialOffer, ContentType.Text.Plain)
                }
            }
        }
    }

    private fun sdJwtConfiguration(vct: String): CredentialConfiguration =
        json.decodeFromJsonElement(
            CredentialConfiguration.serializer(),
            buildJsonObject {
                put("format", "dc+sd-jwt")
                put("vct", vct)
                put("credential_signing_alg_values_supported", JsonArray(listOf(JsonPrimitive("ES256"))))
                put("cryptographic_binding_methods_supported", JsonArray(listOf(JsonPrimitive("jwk"))))
                put("proof_types_supported", buildJsonObject {
                    put("jwt", buildJsonObject {
                        put(
                            "proof_signing_alg_values_supported",
                            JsonArray(listOf(JsonPrimitive("ES256"))),
                        )
                    })
                })
            },
        )

    private fun mdocConfiguration(docType: String): CredentialConfiguration =
        json.decodeFromJsonElement(
            CredentialConfiguration.serializer(),
            buildJsonObject {
                put("format", "mso_mdoc")
                put("doctype", docType)
                put("credential_signing_alg_values_supported", buildJsonArray {
                    add(JsonPrimitive(-7)); add(JsonPrimitive(-9))
                })
                put("cryptographic_binding_methods_supported", JsonArray(listOf(JsonPrimitive("cose_key"))))
                put("proof_types_supported", buildJsonObject {
                    put("jwt", buildJsonObject {
                        put(
                            "proof_signing_alg_values_supported",
                            JsonArray(listOf(JsonPrimitive("ES256"))),
                        )
                    })
                })
            },
        )

    /** credy sends `{ uri, idx }`; issuer2's mDoc status parser expects `{ status_list: { uri, idx } }`. */
    private fun normalizeMdocStatus(validityInfoStatus: JsonObject): JsonObject =
        if (validityInfoStatus.containsKey("status_list")) validityInfoStatus
        else buildJsonObject { put("status_list", validityInfoStatus) }

    private fun statusCallbackNotifications(call: ApplicationCall): IssuanceNotifications? =
        call.request.headers[HEADER_STATUS_CALLBACK_URI]
            ?.takeIf { it.isNotBlank() }
            ?.let { IssuanceNotifications(webhook = IssuanceNotifications.WebhookNotification(url = it)) }

    private fun sessionTtlSeconds(call: ApplicationCall): Long =
        call.request.headers[HEADER_SESSION_TTL_MS]
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.let { it / 1000 }
            ?.coerceAtLeast(1)
            ?: DEFAULT_EXPIRY_SECONDS
}
