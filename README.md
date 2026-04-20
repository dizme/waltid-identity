# OpenID4VCI / OpenID4VP interoperability patches

This repository is a fork of [walt.id identity](https://github.com/walt-id/waltid-identity). The **default upstream README** is preserved as [`README.upstream.md`](README.upstream.md).

The sections below describe **only the changes we applied here** so that the issuer and verifier stacks behave as expected for **OpenID4VCI** (including OAuth 2.0 / OpenID4VCI draft-13 style discovery) and **OpenID4VP 1.0** flows when paired with a modern wallet implementation. They do not replace the OID4VCI / OID4VP specifications or the official walt.id documentation.

---

## Docker Compose and edge routing

### Verifier session URLs (`urlPrefix`)

**Issue:** Verification sessions were built with wrong base URLs (wrong port, or `localhost` when the wallet runs on another device on the LAN).

**Change:** `docker-compose/verifier-api2/config/verifier-service.conf` sets the session prefix from compose variables:

```properties
urlPrefix: "http://${SERVICE_HOST}:${VERIFIER_API2_PORT}/verification-session"
```

### Building `issuer-api` from source

**Issue:** Pre-built images do not include local Kotlin changes on the issuer.

**Change:** `docker-compose/docker-compose.yaml` builds `issuer-api` from the service Dockerfile in this monorepo:

```yaml
  issuer-api:
    build:
      context: ../
      dockerfile: waltid-services/waltid-issuer-api/Dockerfile
```

### Caddy: well-known path layout for the issuer

**Issue:** Clients resolve issuer metadata using paths such as `/.well-known/openid-credential-issuer/<standardVersion>`, while `issuer-api` serves `/<standardVersion>/.well-known/...`.

**Change:** On the listener for `ISSUER_API_PORT`, `docker-compose/Caddyfile` uses matchers and rewrites so incoming well-known URLs map to the paths the issuer actually exposes, then forwards to `issuer-api`. Comments in the file document the mapping. Direct access to `issuer-api` without Caddy skips this rewrite (useful only for debugging).

```caddyfile
@cred path_regexp credential ^/\.well-known/openid-credential-issuer/([^/]+)$
rewrite @cred /{http.regexp.credential.1}/.well-known/openid-credential-issuer

@auth path_regexp auth ^/\.well-known/oauth-authorization-server/([^/]+)$
rewrite @auth /{http.regexp.auth.1}/.well-known/oauth-authorization-server
```

---

## Issuer metadata (Draft 13 profile)

**Issue:** Discovery metadata omitted OAuth / OpenID4VCI draft-13 fields that conformant clients expect (PAR, DPoP, nonce endpoint, token endpoint auth methods, etc.).

**Change:** `waltid-libraries/protocols/waltid-openid4vc/src/commonMain/kotlin/id/walt/oid4vc/OpenID4VCI.kt` — in `createDefaultProviderMetadata`, branch `OpenID4VCIVersion.DRAFT13`, publish fields such as:

- `pushed_authorization_request_endpoint`
- `token_endpoint_auth_methods_supported` (e.g. `none`)
- `require_pushed_authorization_requests`
- `dpop_signing_alg_values_supported`
- `nonce_endpoint`

**Rationale:** For draft-13 style profiles, what the issuer supports must appear in discovery. The gap was on the issuer side (incomplete metadata vs. real behavior and client expectations), not on relaxing client checks.

Excerpt:

```kotlin
OpenID4VCIVersion.DRAFT13 -> OpenIDProviderMetadata.Draft13(
    issuer = baseUrl,
    authorizationServers = setOf(baseUrl),
    authorizationEndpoint = "$baseUrl/authorize",
    pushedAuthorizationRequestEndpoint = "$baseUrl/par",
    tokenEndpoint = "$baseUrl/token",
    // ...
    tokenEndpointAuthMethodsSupported = setOf("none"),
    requirePushedAuthorizationRequests = true,
    dpopSigningAlgValuesSupported = setOf("ES256"),
    // ...
    nonceEndpoint = "$baseUrl/nonce"
)
```

---

## Nonce endpoint (`POST .../nonce`)

**Context:** Metadata advertises `nonce_endpoint` where the wallet obtains `c_nonce` for the JWT proof to the `credential` endpoint; the issuer validates that nonce in the proof (freshness / anti-replay).

**Issue:** The wallet issued `POST` to the nonce path; a real route and consistent storage were required.

**Change:**

- `waltid-services/waltid-issuer-api/src/main/kotlin/id/walt/issuer/issuance/OidcApi.kt`
- `waltid-services/waltid-issuer-api/src/main/kotlin/id/walt/issuer/issuance/CIProvider.kt`

Implements `POST /{standardVersion}/nonce` with `c_nonce` and `c_nonce_expires_in`, plus an in-memory one-shot store. The credential endpoint accepts session-issued nonces or nonces from this route.

**Operations:** In a multi-replica deployment, use a shared store (e.g. Redis) with TTL aligned to `c_nonce_expires_in` if issuance and `POST /credential` can land on different nodes.

```kotlin
post("{standardVersion}/nonce", {
    request {
        standardVersionPathParameter()
    }
}) {
    val (nonce, expiresIn) = issueProofOfPossessionNonce()
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.respond(
        buildJsonObject {
            put("c_nonce", nonce)
            put("c_nonce_expires_in", expiresIn.inWholeSeconds)
        }
    )
}
```

---

## Issuance HTTP API: `issuerKey` shape

**Context:** OpenID4VCI defines proofs and credential endpoints; Walt's **HTTP issuance** bodies (`IssuanceRequest` on routes such as `POST .../openid4vc/jwt/issue`, `.../sdjwt/issue`, `.../mdoc/issue`) use product-specific JSON. Internally, `KeyManager.resolveSerializedKey` expects a **wrapped** shape `{"type":"jwk","jwk":{...}}`, while scripts and tools often send a **flat** JWK.

**Change:** `CIProvider.kt` — `normalizeSerializedIssuerKey`: if `type` is already set, leave as-is; if the object looks like a flat JWK (`kty` / `x`, etc.), wrap it before `resolveIssuerKey`. This adapts Walt's KeyManager; it does not relax RFC 7517 — the inner `jwk` must remain a valid JWK.

**Note:** The JWK inside the **credential proof JWT** is handled separately; RFC 7517 compliance there is the wallet's responsibility.

Excerpt from `CIProvider.kt`:

```kotlin
private fun normalizeSerializedIssuerKey(issuerKey: JsonObject): JsonObject {
    if (issuerKey["type"] != null) return issuerKey
    if (issuerKey.containsKey("kty") || issuerKey.containsKey("x")) {
        return buildJsonObject {
            put("type", JsonPrimitive("jwk"))
            put("jwk", issuerKey)
        }
    }
    return issuerKey
}
```

---

## Credential request: `proofs.jwt`

**Issue:** Wallets send `proofs.jwt` (array of JWT strings). The server previously deserialized mainly the single-field `proof` model.

**Context:** OpenID4VCI 1.0 uses a `proofs` object; for JWT proofs, `proofs.jwt` is an array, aligned with `proof_types_supported`.

**Change:** `waltid-libraries/protocols/waltid-openid4vc/src/commonMain/kotlin/id/walt/oid4vc/requests/CredentialRequest.kt` — `fromJSON` calls `normalizeProofsField` before deserialization: when `proofs` is present and `proof` is absent, the first element of `proofs.jwt` (or `proofs.attestation`) is mapped into `proof` with `proof_type` and `jwt` set.

```kotlin
private fun normalizeProofsField(jsonObject: JsonObject): JsonObject {
    if ("proof" in jsonObject || "proofs" !in jsonObject) {
        return jsonObject
    }
    val proofs = jsonObject["proofs"]?.jsonObject ?: return jsonObject
    val jwt = proofs["jwt"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
    // ...
    return JsonObject(jsonObject + ("proof" to normalizedProof))
}
```

---

## `dc+sd-jwt` credential configuration

**Issue:** Wallets use the format identifier `dc+sd-jwt` (OpenID4VCI 1.0 / SD-JWT VC profile). The stack exposed `dc+sd-jwt` but `vct` / `proof_types_supported.jwt` and issuance were not aligned with the internal `vc+sd-jwt` path.

**Change:**

- `waltid-services/waltid-issuer-api/src/main/kotlin/id/walt/issuer/config/CredentialTypeConfig.kt`
- `waltid-services/waltid-issuer-api/src/main/kotlin/id/walt/issuer/issuance/CIProvider.kt`
- `waltid-services/waltid-issuer-api/src/main/kotlin/id/walt/issuer/issuance/IssuerApi.kt`

`dc+sd-jwt` is treated as a first-class SD-JWT issuance path with appropriate metadata; OID4VCI matching and `authorization_details` carrying `vct` are aligned.

Excerpt:

```kotlin
val isSdJwt = format == CredentialFormat.sd_jwt_vc || format == CredentialFormat.sd_jwt_dc
"${entry.key}_${format.value}" to CredentialSupported(
    format = format,
    cryptographicBindingMethodsSupported = if (isSdJwt) setOf("jwk") else setOf("did"),
    proofTypesSupported = if (isSdJwt) mapOf(
        ProofType.jwt to ProofTypeMetadata(setOf("ES256"))
    ) else null,
    vct = if (isSdJwt) baseUrl.plus("/${entry.key}") else null,
    // ...
)
```

---

## Operational note: `x5Chain` in helper scripts (outside this repo)

When driving **SD-JWT** or **mDoc** issuance from automation in the **parent integration project** (e.g. `generate-credential-offer-qr.sh`), pass `x5Chain` in `IssuanceRequest` so the SD-JWT includes `x5c` and mDoc COSE `issuerAuth` includes `x5c` as expected by wallets and verifiers.

```json
  "x5Chain": [
    "$SD_JWT_ISSUER_X5C"
  ]
```

Without `x5Chain` for mDoc, COSE `issuerAuth` may omit `x5c` and verifiers report errors such as an empty `x5c` chain.

---

## Verifier API 2 — authorization request JWT (`request_uri`)

**Issue:** Using `respondText` for the authorization request JWS could append `; charset=UTF-8` to `Content-Type`. Strict clients expecting exactly `application/oauth-authz-req+jwt` may fail MIME matching.

**Change:** `waltid-libraries/protocols/waltid-openid4vp-verifier/src/commonMain/kotlin/id/walt/verifier2/handlers/authrequest/Verifier2AuthorizationRequestHandler.kt` — use `respondBytes` with an unparameterized content type.

```kotlin
is JWTStringResponse -> call.respondBytes(
    bytes = formattedSessionResponse.jwt.encodeToByteArray(),
    contentType = ContentType("application", "oauth-authz-req+jwt"),
    status = HttpStatusCode.OK
)
```

---

## Verifier API 2 — `direct_post` response

**Issue:** After `POST` to `response_uri`, some wallets require an explicit `application/json` `Content-Type` on the verifier response.

**Change:** `waltid-libraries/protocols/waltid-openid4vp-verifier/src/commonMain/kotlin/id/walt/verifier2/handlers/vpresponse/Verifier2VPDirectPostHandler.kt` — in `respondHandleDirectPostResponse`, encode the body with `respondText(..., ContentType.Application.Json)`.

```kotlin
val jsonBody = buildJsonObject { result.forEach { (k, v) -> put(k, v) } }
call.respondText(
    Json.encodeToString(JsonObject.serializer(), jsonBody),
    ContentType.Application.Json
)
```

---

## mDoc (mDL) — ISO dates in CBOR from `mdocData` JSON

**Context:** OpenID4VCI does not define how Walt's HTTP API maps JSON `mdocData` into mDL attributes; **ISO/IEC 18013-5** requires correct element typing (e.g. full-date with CBOR tag **1004**). Plain strings became untagged CBOR text.

**Issue:** In `doGenerateMDoc`, `mdocData` was converted with `JsonElement.toDataElement()` without element names, so date strings did not become `FullDateElement` / `TDateElement`.

**Change:**

- `waltid-libraries/credentials/waltid-mdoc-credentials/src/commonMain/kotlin/id/walt/mdoc/dataelement/json/JsonElementConversions.kt` — `toDataElement(elementIdentifier)` with recursive keys; for `birth_date` / `issue_date` / `expiry_date`, use `FullDateElement` (date-only `YYYY-MM-DD`) or `TDateElement` (RFC 3339); `portrait`: try base64 to bytes.
- `JsonElementToCborMappingConfig.kt` — fallback uses `value.toDataElement(key)` instead of `toDataElement()`.
- `waltid-services/waltid-issuer-api/src/main/kotlin/id/walt/issuer/issuance/CIProvider.kt` — `property.value.toDataElement(property.key)` in `doGenerateMDoc` (see also `x5Chain` below).

Excerpt from `JsonElementConversions.kt`:

```kotlin
fun JsonElement.toDataElement(elementIdentifier: String? = null): AnyDataElement = when (this) {
    is JsonObject -> mapValues { (key, value) -> value.toDataElement(key) }.toDataElement()
    is JsonArray -> map { element ->
        when (element) {
            is JsonObject -> element.mapValues { (k, v) -> v.toDataElement(k) }.toDataElement()
            else -> element.toDataElement(null)
        }
    }.toDataElement()
    // ...
}

// Date fields: full-date #6.1004 for YYYY-MM-DD, else tdate #6.0 (RFC 3339)
if (elementIdentifier == "birth_date" || elementIdentifier == "issue_date" || elementIdentifier == "expiry_date") {
    if (isoFullDateOnly.matches(content)) {
        return FullDateElement(LocalDate.parse(content))
    }
    return try {
        TDateElement(Instant.parse(content))
    } catch (_: Exception) {
        try {
            FullDateElement(LocalDate.parse(content))
        } catch (_: Exception) {
            StringElement(content)
        }
    }
}
```

Excerpt from `CIProvider.kt` (`doGenerateMDoc`, date mapping):

```kotlin
addItemToSign(
    nameSpace = namespace.key,
    elementIdentifier = property.key,
    elementValue = property.value.toDataElement(property.key),
)
```

---

## mDoc (mDL) — `x5Chain` and COSE `x5c` on `issuerAuth`

**Context:** Walt's `IssuanceRequest` PEM field `x5Chain` is product-specific; ISO/IEC 18013-5 expects the document signer chain in COSE, commonly as **`x5c`** in the unprotected header (RFC 9360). Verifiers using X.509 need that chain.

**Issue:** Without PEM in `IssuanceRequest`, `SimpleCOSECryptoProvider` does not populate `x5c` on `issuerAuth` → verifier errors such as "Contained x5c … is empty".

**Change:** `waltid-services/waltid-issuer-api/src/main/kotlin/id/walt/issuer/issuance/CIProvider.kt` — if `x5Chain` is missing or empty, fail with `CredentialError` / `invalid_request` before signing the MSO; pass the chain into `COSECryptoProviderKeyInfo` so `sign1` includes `x5c`. Helper scripts should send the same PEM material as in Walt's integration tests for `openid4vc/mdoc/issue`.

Excerpt from `CIProvider.kt` (`doGenerateMDoc`, `x5Chain`):

```kotlin
val x5Chain = request.x5Chain?.map { X509CertUtils.parse(it) } ?: listOf()
if (x5Chain.isEmpty()) {
    throw CredentialError(
        credentialRequest = credentialRequest,
        errorCode = CredentialErrorCode.invalid_request,
        message = "mDoc issuance requires a non-empty x5Chain (PEM certificates) so issuerAuth COSE includes x5c"
    )
}
// … COSECryptoProviderKeyInfo(…, x5Chain = x5Chain, …)
```

---

## Summary

These edits align **discovery**, **credential**, **nonce**, and **proof** handling with OpenID4VCI-style clients, tighten **Verifier2** HTTP semantics for VP flows, and improve **mDL** encoding and signing for ISO 18013-5 oriented interoperability—without replacing upstream product documentation in [`README.upstream.md`](README.upstream.md).
