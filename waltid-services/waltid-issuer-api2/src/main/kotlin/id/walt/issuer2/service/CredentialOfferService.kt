package id.walt.issuer2.service

import id.walt.issuer2.config.Issuer2ServiceConfig
import id.walt.issuer2.models.CredentialOfferCreateRequest
import id.walt.issuer2.models.CredentialOfferCreateResponse
import id.walt.issuer2.domain.IssuanceSession
import id.walt.issuer2.notifications.IssuanceNotificationService
import id.walt.issuer2.notifications.IssuanceNotifications
import id.walt.issuer2.notifications.IssuanceSessionEvent
import id.walt.issuer2.utils.JsonObjectPathMapper
import id.walt.mdoc.dataelement.json.JsonObjectToCborMappingConfig
import id.walt.openid4vci.DefaultSession
import id.walt.openid4vci.TokenType
import id.walt.openid4vci.metadata.issuer.CredentialConfiguration
import id.walt.openid4vci.offers.AuthenticationMethod
import id.walt.openid4vci.offers.CredentialOffer
import id.walt.openid4vci.offers.CredentialOfferRequest
import id.walt.openid4vci.offers.CredentialOfferValueMode
import id.walt.openid4vci.offers.IssuerStateMode
import id.walt.openid4vci.preauthorized.PreAuthorizedCodeIssueRequest
import id.walt.openid4vci.preauthorized.PreAuthorizedCodeIssuer
import id.walt.sdjwt.SDMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CredentialOfferService(
    private val profileService: CredentialProfileService,
    private val sessionService: IssuanceSessionService,
    private val preAuthorizedCodeIssuer: PreAuthorizedCodeIssuer,
    private val config: Issuer2ServiceConfig,
    private val notificationService: IssuanceNotificationService,
) {
    suspend fun createCredentialOffer(request: CredentialOfferCreateRequest): CredentialOfferCreateResponse {
        val profile = profileService.resolveProfile(request.profileId)
        val sessionId = request.sessionId ?: UUID.randomUUID().toString()
        val expiresAt = expirationTimestamp(request.expiresInSeconds)
        val overrides = request.runtimeOverrides
        val issuerKey = overrides?.issuerKey ?: profile.issuerKey
        val issuerDid = overrides?.issuerDid ?: profile.issuerDid
        val notifications = overrides?.notifications ?: profile.notifications
        val credentialData = profile.credentialData.mergeCredentialDataOverride(overrides?.credentialData)
        val idTokenClaimsMapping = overrides?.idTokenClaimsMapping ?: profile.idTokenClaimsMapping

        val issuerStateMode = when (request.authMethod) {
            AuthenticationMethod.PRE_AUTHORIZED -> null
            AuthenticationMethod.AUTHORIZED -> request.issuerStateMode ?: IssuerStateMode.INCLUDE
        }
        require(issuerKey.isNotEmpty()) { "issuerKey must not be empty" }
        require(issuerKey["type"] != null) { "issuerKey must contain a key type" }

        var resolvedTxCodeValue: String? = null
        val credentialOffer = when (request.authMethod) {
            AuthenticationMethod.PRE_AUTHORIZED -> {
                val oauthSession = DefaultSession(subject = sessionId)
                    .withExpiresAt(TokenType.ACCESS_TOKEN, expiresAt)
                val preAuthorizedCode = preAuthorizedCodeIssuer.issue(
                    PreAuthorizedCodeIssueRequest(
                        txCode = request.txCode,
                        txCodeValue = request.txCodeValue,
                        session = oauthSession,
                        scopes = emptySet(),
                        audience = emptySet(),
                    )
                )
                resolvedTxCodeValue = preAuthorizedCode.txCodeValue
                CredentialOffer.withPreAuthorizedCodeGrant(
                    credentialIssuer = issuerBaseUrl(),
                    credentialConfigurationIds = listOf(profile.credentialConfigurationId),
                    preAuthorizedCode = preAuthorizedCode.code,
                    txCode = request.txCode,
                )
            }

            AuthenticationMethod.AUTHORIZED ->
                CredentialOffer.withAuthorizationCodeGrant(
                    credentialIssuer = issuerBaseUrl(),
                    credentialConfigurationIds = listOf(profile.credentialConfigurationId),
                    issuerState = sessionId.takeIf { issuerStateMode == IssuerStateMode.INCLUDE },
                )
        }

        idTokenClaimsMapping?.let { mapping ->
            JsonObjectPathMapper.validateJsonObjectContainsPaths(
                jsonObject = credentialData,
                jsonPathList = mapping.values.toList(),
            )
        }

        val session = IssuanceSession(
            sessionId = sessionId,
            profileId = profile.profileId,
            authenticationMethod = request.authMethod,
            credentialConfigurationId = profile.credentialConfigurationId,
            issuerKey = issuerKey,
            credentialData = credentialData,
            mapping = overrides?.mapping ?: profile.mapping,
            selectiveDisclosure = overrides?.selectiveDisclosure ?: profile.selectiveDisclosure,
            idTokenClaimsMapping = idTokenClaimsMapping,
            mDocNameSpacesDataMappingConfig =
                overrides?.mDocNameSpacesDataMappingConfig ?: profile.mDocNameSpacesDataMappingConfig,
            x5Chain = overrides?.x5Chain ?: profile.x5Chain,
            issuerDid = issuerDid,
            credentialOffer = credentialOffer,
            expiresAt = expiresAt,
            notifications = notifications,
            credentialStatus = overrides?.credentialStatus ?: profile.credentialStatus,
        )
        sessionService.createSession(session)

        val offerRequest = when (request.valueMode) {
            CredentialOfferValueMode.BY_VALUE -> CredentialOfferRequest(credentialOffer = credentialOffer)
            CredentialOfferValueMode.BY_REFERENCE -> CredentialOfferRequest(
                credentialOfferUri = "${issuerBaseUrl()}/credential-offer?id=$sessionId",
            )
        }

        return CredentialOfferCreateResponse(
            offerId = sessionId,
            profileId = profile.profileId,
            authMethod = request.authMethod,
            issuerStateMode = issuerStateMode,
            expiresAt = expiresAt.toEpochMilliseconds(),
            txCodeValue = resolvedTxCodeValue,
            credentialOffer = offerRequest.toUrl(),
        )
    }

    /**
     * 🚧🚧🚧 WT-907 PASSTHROUGH (OUT-OF-SPEC) — compat with `wallet-wltbe-credy`. 🚧🚧🚧
     *
     * Creates a PRE_AUTHORIZED credential offer from a caller-supplied, inline credential
     * configuration (format/vct/docType dictated per-request) instead of a declared
     * [id.walt.issuer2.domain.CredentialProfile]. credy owns its own credential-type registry and
     * always uses the pre-authorized flow, so only that path is supported here.
     *
     * This deliberately bypasses [CredentialProfileService] and the `credential_configurations_supported`
     * registry. It exists only to keep the deployed credy working without modifying it.
     *
     * TODO(WT-907): return in-spec. credy must declare credential types as CredentialProfiles (or via a
     *  runtime profiles API) and request issuance by `profileId`; then delete this method, the legacy
     *  compat endpoints, [IssuanceSession.inlineCredentialConfiguration] and the MetadataService dynamic
     *  registry. Blocked on resolving custom/dynamic VCT (see self-attested-custom-vct-be-spec.md).
     */
    suspend fun createInlineCredentialOffer(
        credentialConfigurationId: String,
        inlineConfiguration: CredentialConfiguration,
        issuerKey: JsonObject,
        issuerDid: String?,
        credentialData: JsonObject,
        mapping: JsonObject?,
        selectiveDisclosure: SDMap?,
        x5Chain: List<String>?,
        mDocNameSpacesDataMappingConfig: Map<String, JsonObjectToCborMappingConfig>?,
        credentialStatus: JsonElement?,
        notifications: IssuanceNotifications?,
        expiresInSeconds: Long,
        valueMode: CredentialOfferValueMode = CredentialOfferValueMode.BY_REFERENCE,
    ): CredentialOfferCreateResponse {
        require(issuerKey.isNotEmpty()) { "issuerKey must not be empty" }
        require(issuerKey["type"] != null) { "issuerKey must contain a key type" }

        val sessionId = UUID.randomUUID().toString()
        val expiresAt = expirationTimestamp(expiresInSeconds)

        val oauthSession = DefaultSession(subject = sessionId)
            .withExpiresAt(TokenType.ACCESS_TOKEN, expiresAt)
        val preAuthorizedCode = preAuthorizedCodeIssuer.issue(
            PreAuthorizedCodeIssueRequest(
                txCode = null,
                txCodeValue = null,
                session = oauthSession,
                scopes = emptySet(),
                audience = emptySet(),
            )
        )
        val credentialOffer = CredentialOffer.withPreAuthorizedCodeGrant(
            credentialIssuer = issuerBaseUrl(),
            credentialConfigurationIds = listOf(credentialConfigurationId),
            preAuthorizedCode = preAuthorizedCode.code,
            txCode = null,
        )

        val session = IssuanceSession(
            sessionId = sessionId,
            // No real profile: use the credential configuration id as a synthetic profile id.
            profileId = credentialConfigurationId,
            authenticationMethod = AuthenticationMethod.PRE_AUTHORIZED,
            credentialConfigurationId = credentialConfigurationId,
            issuerKey = issuerKey,
            credentialData = credentialData,
            mapping = mapping,
            selectiveDisclosure = selectiveDisclosure,
            mDocNameSpacesDataMappingConfig = mDocNameSpacesDataMappingConfig,
            x5Chain = x5Chain,
            issuerDid = issuerDid,
            credentialOffer = credentialOffer,
            expiresAt = expiresAt,
            notifications = notifications,
            credentialStatus = credentialStatus,
            inlineCredentialConfiguration = inlineConfiguration,
        )
        sessionService.createSession(session)

        val offerRequest = when (valueMode) {
            CredentialOfferValueMode.BY_VALUE -> CredentialOfferRequest(credentialOffer = credentialOffer)
            CredentialOfferValueMode.BY_REFERENCE -> CredentialOfferRequest(
                credentialOfferUri = "${issuerBaseUrl()}/credential-offer?id=$sessionId",
            )
        }

        return CredentialOfferCreateResponse(
            offerId = sessionId,
            profileId = credentialConfigurationId,
            authMethod = AuthenticationMethod.PRE_AUTHORIZED,
            issuerStateMode = null,
            expiresAt = expiresAt.toEpochMilliseconds(),
            txCodeValue = null,
            credentialOffer = offerRequest.toUrl(),
        )
    }

    suspend fun getCredentialOffer(sessionId: String): CredentialOffer? {
        val session = sessionService.getSessionOrNull(sessionId) ?: return null
        val credentialOffer = session.credentialOffer ?: return null
        notificationService.notify(
            session = session,
            event = IssuanceSessionEvent.resolved_credential_offer,
        )
        return credentialOffer
    }

    private fun issuerBaseUrl(): String = config.baseUrl.trimEnd('/') + "/openid4vci"

    private fun expirationTimestamp(expiresInSeconds: Long): Instant =
        when (expiresInSeconds) {
            -1L -> Instant.DISTANT_FUTURE
            else -> Clock.System.now().plus(expiresInSeconds.seconds)
        }

    private fun JsonObject.mergeCredentialDataOverride(override: JsonObject?): JsonObject =
        override?.let { mergeCredentialDataPatch(it) } ?: this

    private fun JsonObject.mergeCredentialDataPatch(patch: JsonObject): JsonObject =
        JsonObject(
            toMutableMap().apply {
                patch.forEach { (key, patchValue) ->
                    this[key] = mergeCredentialDataValue(this[key], patchValue)
                }
            }
        )

    private fun mergeCredentialDataValue(
        configuredValue: JsonElement?,
        patchValue: JsonElement,
    ): JsonElement =
        if (configuredValue is JsonObject && patchValue is JsonObject) {
            configuredValue.mergeCredentialDataPatch(patchValue)
        } else {
            patchValue
        }
}