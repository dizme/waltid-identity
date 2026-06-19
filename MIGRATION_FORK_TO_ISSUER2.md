# Migrazione fork walt.id → upstream (issuer-api2)

> Branch di lavoro: `feature/port-fork-to-issuer2` (creato da `main` = `ef81a70da`, **v0.21.0-11**).
> Generato il 2026-06-19. Aggiornare quando la lista cambia.

## 1. Punto di divergenza

| | repo | ref | versione (tag walt-id) | data | note |
|---|---|---|---|---|---|
| **Fork (deploy)** | `wallet-wltbe-waltididentity` | branch `develop`, tip `39111ba` | ≈ **v0.20.0-4** (contenuto) | snapshot 2026-05 | snapshot **senza storia** (un solo commit `dd129ae`). `UPSTREAM_BASE.md` dichiara etuitus `24a46a83c` come `v0.19.0-343`: è il conteggio del fork etuitus, non i tag walt-id. |
| **Upstream (target)** | `waltid-identity` | branch `main`, `ef81a70da` | **v0.21.0-11** | 2026-06-18 | `origin` = github.com/**dizme**/waltid-identity, **identico** a walt-id upstream `main` (stesso SHA, 0 commit di scarto). Ha già `waltid-issuer-api2`. |

> **Versioni**: i tag v0.20.x/v0.21.0 non erano nel clone dizme (origin fermo ai tag ≤ v0.19.0), quindi
> `git describe` dava erroneamente `v0.19.0-718`. Aggiunto il remote `waltid → github.com/walt-id/waltid-identity`:
> contro i suoi tag il commit-target `ef81a70da` è **v0.21.0-11** e il baseline snapshot è **v0.20.0-4**.
> Il commit-target dell'analisi era già corretto → **nessun verdetto cambia**, solo le etichette.

Gli SHA etuitus non esistono nel clone (fork diversi che condividono la storia walt-id).
Il **baseline upstream pulito** equivalente al nostro snapshot è stato determinato empiricamente
(commit che minimizza il diff sulle cartelle sorgente): **`6a3931a39` = v0.20.0-4 (2026-05-12)**.
Dal 2026-05-15 in poi upstream introduce dipendenze/verifier2 che lo snapshot non ha (le deletions
nel diff saltano da 164 a ~1031), quindi `6a3931a39` è il punto di partenza reale.

**Le nostre modifiche** = `git diff 6a3931a39 fork/develop` ristretto a `waltid-services waltid-libraries
waltid-applications build-logic` = **42 file** (1096 ins / 164 del; la sola `D` è un file di stato Xcode, rumore).

Remote aggiunti in questo clone per il lavoro:
- `fork → ../wallet-wltbe-waltididentity` (ref `fork/develop`) — per diffare/cherry-pickare i file.
- `waltid → github.com/walt-id/waltid-identity` — upstream reale, per tag e aggiornamenti futuri.

## 2. Esito chiave

La maggior parte delle nostre modifiche **è già in upstream main**, e non solo in issuer-api2:
gran parte del lavoro **verifier2** (structured failures + error response OID4VP §8.5) è upstream,
spesso **byte-identico** → evidentemente è stato contribuito a monte. Va quindi **adottata la versione
upstream e scartata la nostra copia**.

Resta fork-only soltanto:
1. **`WalletClientAttestationVerifier`** (issuer) → unica feature vera da **portare** su issuer-api2.
2. **Coercion mdoc** date/portrait → fork-only, **contro** la policy "niente tolleranze lato wallet/dato" → **decisione**.
3. **Passthrough WT-907** (credy detta vct/format) → in issuer-api2 si esprime via `CredentialProfile` + override → **ridisegno**.
4. **Deploy/infra** (Artifactory mirror, Dockerfile, `/livez`, init AWS KMS verifier2, bump dipendenze) → gestito in pipeline/values, non è codice-feature.

Tutto il resto (draft-13 req/resp, nonce endpoint, dc+sd-jwt, proofs[], status list, KMS mDoc COSE)
è **nativo in issuer-api2** → si scarta.

## 3. Lista modifiche con disposizione

Legenda: ✅ già upstream (adotta upstream, scarta la nostra) · ⬆️ da portare · ❌ scarta (nativo/obsoleto) · ⚠️ decisione · 🔧 deploy/infra

### 3a. Librerie condivise + verifier2 — quasi tutto GIÀ UPSTREAM
| File | Δ fork↔main | Disp. |
|---|---|---|
| `…/verifier2/data/SessionFailure.kt` (new) | **identico** | ✅ structured failures: adotta upstream |
| `…/verifier2/data/Verification2Session.kt` | identico | ✅ |
| `…/verifier2/data/SessionEvent.kt` | identico | ✅ |
| `…/verifier2/verification/DcqlFulfillmentChecker.kt` | identico | ✅ |
| `…/verifier2/handlers/vpresponse/Verifier2SessionCredentialPolicyValidation.kt` | identico | ✅ |
| `…/policies2/vc/CredentialPolicyResults.kt` | identico | ✅ (`query_id`/`credential_index`) |
| `…/openid4vci/…/AuthorizationServerMetadata.kt` | identico | ✅ |
| `…/verifier2/…/Verifier2VPDirectPostHandler.kt` | +17/−15 | ✅ §8.5 error response: upstream equivalente |
| `…/verifier2/verification2/PresentationVerificationEngine.kt` | +49/−142 | ✅ upstream più evoluto |
| `…/verifier2/…/VerificationSessionCreator.kt` | +83/−46 | ✅ default `vp_formats_supported` upstream |
| `…/openid4vp-wallet/…/WalletPresentFunctionality2.kt` | +111/−176 | ✅ `walletReject` §8.5: upstream canonical |
| `…/verifier2/…/Verifier2AuthorizationRequestHandler.kt` | +1/−5 | ✅ content-type fix superato upstream |
| `…/openid4vc/…/OpenID4VCI.kt` | +8/−20 | ✅ metadata draft-13/attestation: upstream evoluto |
| `…/openid4vc/…/CredentialFormat.kt` | −1 | ✅ `dc+sd-jwt` nativo upstream |
| `…/openid4vc/…/requests/CredentialRequest.kt` | +1/−19 | ✅ normalize `proofs[]` nativo upstream |
| `…/openid4vp-verifier/build.gradle.kts`, `…/jvmTest/CiJvmTest.kt` | triviale | ✅ |

### 3b. Coercion mdoc — fork-only, DECISIONE
| File | Cosa fa | Disp. |
|---|---|---|
| `…/waltid-mdoc-credentials/…/json/JsonElementConversions.kt` (+42) | `birth_date/issue_date/expiry_date` → FullDate/TDate; `portrait` → ByteString(base64) | ⚠️ upstream **non** ce l'ha; contro policy "fix lato dato/setup" |
| `…/json/JsonElementToCborMappingConfig.kt` (+1) | passa `elementIdentifier` (hook della coercion) | ⚠️ idem |

### 3c. Issuer-api (v1) — target = issuer-api2
| File / blocco | Cosa fa | Disp. |
|---|---|---|
| `issuance/WalletClientAttestationVerifier.kt` (new, 190) + hook in `OidcApi`/`CIProvider` + `OIDCIssuerServiceConfig.walletProviderBaseUrl` | verifica `OAuth-Client-Attestation[-PoP]` (OID4VCI 1.0 App.E) | ⬆️ **PORTARE su issuer-api2** (issuer2 annuncia ma non verifica) |
| `CIProvider.RemoteCapableMDocCryptoProvider` + `signAsync` + `toCoseSigner` | firma mDoc COSE via KMS/`signRaw` | ❌ nativo in issuer2 (`MdocIssuer`/`toCoseSigner`) |
| `CIProvider.issueProofOfPossessionNonce` + `OidcApi {ver}/nonce` | nonce endpoint OID4VCI | ❌ nativo issuer2 |
| `OidcApi.enrichCredentialRequestBody` | rehydrate `format`/vct/doctype draft-13 | ❌ nativo issuer2 |
| `OidcApi.normalizeCredentialResponseForDraft13` | shape `credentials[]` (ma poi tiene legacy per WT-907) | ❌ **dannoso** per multipaz (richiede `credentials[]`) → scarta |
| Passthrough **WT-907** (`findMatchingIssuanceRequest`, `createCredentialOfferUri`, `IssuanceRequest.docType`, route sdjwt/mdoc) | credy detta vct/format/docType verbatim | ⬆️ **PORTARE su issuer2 (passthrough) per restare compat con credy** — vedi §6. Resta fuori-spec → **TODO grande** rientro in-spec (richiede modifiche a credy). |
| `dc+sd-jwt` ovunque (`CredentialTypeConfig`, `CIProvider`, config `.conf`) | format `sd_jwt_dc` | ❌ nativo issuer2 |
| `CredentialTypeConfig` email credential dizme | tipo specifico dizme | 🔧 config (riportare se serve nei profili) |
| `IssuerApi`/`OidcApi`/`Monitoring` minori | proof-nonce validation, `/livez` filter | ❌/🔧 |

### 3d. Verifier-api (v1, legacy) — NON deployato
| File | Cosa fa | Disp. |
|---|---|---|
| `verifier-api/…/VerifierApi.kt` + `VerifierService.kt` | `info.name/purpose` → presentation definition | ❌ `wid-verifier` = verifier-api2, non v1 |
| `verifier-api/…/Monitoring.kt` | `/livez` filter | 🔧 |

### 3e. Deploy / infra / dipendenze — non codice-feature
| File | Cosa fa | Disp. |
|---|---|---|
| `build-logic/…/waltid.base.gradle.kts` | mirror Artifactory InfoCert (egress pipeline) | 🔧 pipeline |
| `issuer-api/Dockerfile`, `verifier-api2/Dockerfile` | pin `gradle:9.2.0-jdk21` | 🔧 |
| `verifier-api2/Main.kt` + `build.gradle.kts` | `WaltCryptoAws.init()` + dep crypto-aws | 🔧 (verificare se già in main; serve per chiavi `{"type":"aws"}`) |
| `verifier-api2/…/Monitoring.kt`, `issuer-api/…/Monitoring.kt` | `/livez` filter | 🔧 |
| `wallet-api/build.gradle.kts`, `web-portal/package.json`, `digital-credentials/package.json`, `ktor-authnz/build.gradle.kts` | bump dipendenze (driver JDBC, next, vite, mina, hoplite, mockk…) | 🔧 (upstream ha già pari/superiore in gran parte) |

## 4. Piano di applicazione su issuer-api2

1. **Verifier2 / lib condivise** → niente da fare: già in `main`. (Eventuale `git checkout main -- <file>` se qualche
   copia fork dovesse rientrare per errore.)
2. **`WalletClientAttestationVerifier`** → portare la classe in `waltid-issuer-api2`, agganciarla
   all'endpoint token/credential di issuer2; mappare `walletProviderBaseUrl` nella config issuer2.
   Multipaz invia davvero gli header → necessaria.
3. **WT-907 passthrough** → **DECISIONE: si mantiene** per restare compatibili con credy senza
   modificarlo ora. Va portato su issuer2 come livello di compatibilità (vedi §5 contratto credy e
   §6 piano + **TODO grande**).
4. **Coercion mdoc** → decisione esplicita: NON portare e correggere il dato a monte (credy/setup),
   coerente con la policy; oppure portare se il dato non è controllabile.
5. **Deploy/infra** → reimpostare in values/pipeline (Artifactory, `/livez`, init AWS KMS verifier2,
   Dockerfile), non nel codice upstream.
6. Tutto il resto (draft-13, nonce, dc+sd-jwt, proofs, status list, KMS mDoc) → **scartare**, è nativo.

## 5. Contratto credy ↔ issuer (da preservare invariato)

credy (`wallet-wltbe-credy`, `src/service/waltid/issuer_client.go` + `src/service/issuance/creator.go`)
possiede la **propria anagrafica** dei tipi (colonne DB `sd_jwt_vc_vct`, `mdoc_doctype`) e chiama
l'issuer **server-to-server** così — questo è il contratto da NON rompere:

- **`POST /openid4vc/sdjwt/issue`** body `IssueSdJwtVcRequest`:
  `issuerKey`, `issuerDid`, `credentialConfigurationId` (con suffisso `_sd_jwt_vc`), **`vct`** (verbatim),
  **`credentialFormat:"dc+sd-jwt"`** (hardcoded), `credentialData`, `mapping`, `selectiveDisclosure`,
  `x5Chain` (DSC del tenant), `authenticationMethod:"PRE_AUTHORIZED"`, `standardVersion:"DRAFT13"`.
- **`POST /openid4vc/mdoc/issue`** body `IssueMdocRequest`:
  `issuerKey`, `credentialConfigurationId` (con suffisso `_mso_mdoc`), **`docType`** (verbatim dal DB),
  `x5Chain`, `mdocData`, `validityInfoStatus` (uri+idx per status list), `authenticationMethod`, `standardVersion`.
- **Risposta:** `text/plain` con l'**offer URI come stringa nuda** (NON `{"credential_offer_uri":...}`).
- **Header:** `statusCallbackUri` (callback stato sessione), `sessionTtl` (ms). NESSUN nonce, NESSUN
  `OAuth-Client-Attestation`.
- **Lato wallet:** credy fa da reverse-proxy verbatim verso l'issuer per `/{ver}/nonce`, `/token`,
  `/credential` e i metadata; riscrive solo `credential_issuer` nell'offer per puntare a sé. Quindi il
  flusso wallet-facing si adatta dai **metadata** dell'issuer → non richiede path fissi, MA i due
  endpoint di emissione sopra e la risposta text/plain SÌ.

Config: `WALTID_ISSUER_URL` → `http://wid-issuer`.

## 6. Passthrough su issuer-api2 — piano

**Problema architetturale.** issuer2 è *profile-driven*: unico ingresso `POST issuer2/credential-offers`
con `CredentialOfferCreateRequest { profileId (obbligatorio), authMethod, runtimeOverrides, ... }`.
format/vct/docType **non** stanno nel profilo né negli override: derivano da
`profile.credentialConfigurationId` → `CredentialConfiguration` nei metadata (config HOCON
`credential-issuer-metadata.conf`). `CredentialOfferRuntimeOverrides` (`models/CredentialOfferModels.kt:17-29`)
sovrascrive dato/chiave/did/mapping/SD/x5Chain/status/notifiche **ma NON vct/format/docType/ccid**.
credy invece è *request-driven* e li detta verbatim. I due modelli sono opposti → serve un livello di compat.

**Piano (per non toccare credy):** aggiungere a issuer2 endpoint di compatibilità che replicano le route
e le shape v1 e internamente costruiscono una **CredentialConfiguration inline/effimera** dai valori del
chiamante, senza profilo dichiarato.

1. **Endpoint compat** `POST /openid4vc/sdjwt/issue` e `/openid4vc/mdoc/issue` in issuer2: accettano il
   JSON v1 (`IssueSdJwtVcRequest`/`IssueMdocRequest`), leggono header `statusCallbackUri`/`sessionTtl`,
   rispondono **text/plain** con l'offer URI. (Nuovo controller, mappa la request v1 → flusso interno.)
2. **Config inline nella sessione.** Estendere `CredentialOfferCreateRequest`
   (`models/CredentialOfferModels.kt:31-66`, oggi `profileId` obbligatorio) e `IssuanceSession`
   (`domain/IssuanceSession.kt:23-47`) con una `inlineCredentialConfiguration` opzionale
   (format + vct/docType + credentialData/key/x5Chain/status). Bypassare `profileService.resolveProfile`
   in `service/CredentialOfferService.kt:34` quando è inline.
3. **Risoluzione in emissione.** In `OpenId4VciProtocolService.kt:272` (oggi
   `metadataService.getCredentialConfiguration(ccid)`) usare la config inline della sessione se presente,
   invece del lookup statico → qui format/vct/docType diventano effettivi; gli handler
   mDoc/SD-JWT/JWT a valle sono già format-agnostici.
4. **Rilassare validazione** `CredentialProfileService.validateProfile` (`:47-50`) e l'enforcement ccid
   `OpenId4VciProtocolService.kt:261` per il ramo inline (ccid effimero non presente nei metadata).
5. **Metadata.** Il wallet scopre gli endpoint dai metadata (credy proxa) → verificare che la
   credenziale inline compaia/sia coerente nei `credential_configurations_supported` quanto basta al
   wallet (eventuale registrazione dinamica temporanea).
6. **mDoc/KMS/x5Chain:** già nativi (`MdocIssuer.toCoseSigner→signRaw`, x5Chain obbligatorio) → nessuna modifica.

> ### 🚧🚧🚧 TODO GRANDE — RIENTRARE IN SPEC (rimuovere il passthrough) 🚧🚧🚧
> Il passthrough WT-907 è **fuori spec OID4VCI**: il chiamante (credy) detta vct/format/docType per-richiesta
> invece di usare i `CredentialProfile`/`credential_configurations_supported` dichiarati dall'issuer.
> È mantenuto SOLO per compatibilità con l'attuale `wallet-wltbe-credy`.
>
> **Rientro in-spec (lavoro futuro, richiede di MODIFICARE credy):**
> - credy smette di passare vct/format/docType per-richiesta; dichiara invece i tipi come
>   `CredentialProfile` su issuer2 (o via API di management dei profili) e chiede l'emissione per `profileId`.
> - Va sciolta la tensione **VCT custom/dinamici** (`self-attested-custom-vct-be-spec.md`): se i VCT
>   sono per-tenant/dinamici servono profili generati dinamicamente o un'API profili runtime.
> - Una volta migrato credy: **rimuovere** gli endpoint compat (§6.1), la config inline (§6.2-6.3) e
>   ripristinare la validazione stretta (§6.4). Tracciare con un ticket dedicato.

## 7. Implementazione effettiva (branch `feature/port-fork-to-issuer2`)

Design scelto: path di compat **isolato**, senza modificare il modello del management API
(`CredentialOfferCreateRequest` invariato) → tutto il codice fuori-spec è marcato `🚧 WT-907` e
facilmente removibile.

**Step 2 — Wallet client attestation (in-spec, da tenere):**
- `security/WalletClientAttestationVerifier.kt` (NEW) — verifica `OAuth-Client-Attestation[-PoP]` (x5c o JWKS discovery).
- `controller/OpenId4VciController.kt` — hook `verifyIfPresent` in `post("token")` (400 `invalid_client` se fallisce); no-op se header assenti.
- `config/Issuer2ServiceConfig.kt` — campo `walletProviderBaseUrl` (default `https://wallet-provider.eudiw.dev`).
- `build.gradle.kts` — dep `nimbus.jose.jwt`. `Issuer2Module` passa la config al controller.
- NB: issuer2 **annuncia già** `attest_jwt_client_auth` (`AuthorizationServerMetadata.fromBaseUrl` default) → mancava solo la verifica.

**Step 3 — WT-907 passthrough (out-of-spec, compat credy, da rimuovere):**
- `controller/LegacyIssuanceCompatController.kt` (NEW) — `POST /openid4vc/{sdjwt,mdoc}/issue`, body v1, **risposta text/plain offer URI** (BY_VALUE come v1), header `statusCallbackUri`→webhook, `sessionTtl`(ms)→expiry.
- `models/LegacyIssuanceModels.kt` (NEW) — `LegacyIssueSdJwtVcRequest` / `LegacyIssueMdocRequest` (shape credy).
- `service/CredentialOfferService.kt` — metodo `createInlineCredentialOffer(...)` (solo PRE_AUTHORIZED) che costruisce offerta+sessione da una `CredentialConfiguration` inline.
- `domain/IssuanceSession.kt` — campo `inlineCredentialConfiguration: CredentialConfiguration?` (persistito → restart-safe, fonte di verità all'emissione).
- `service/openid4vci/OpenId4VciProtocolService.kt` — a `/credential` usa `session.inlineCredentialConfiguration` se presente (poi fallback metadata). L'enforcement ccid `:261` resta valido (ccid effimero coerente offer↔session).
- `service/openid4vci/MetadataService.kt` — registro dinamico in-memory (`registerDynamicConfiguration`) unito a `credential_configurations_supported` per la discovery wallet.
- Mapping dati: sd-jwt `credentialData`/`mapping`/`selectiveDisclosure` diretti; mdoc `mdocData` (`{ns:{claim}}`) → `credentialData` diretto; `validityInfoStatus {uri,idx}` → `{status_list:{uri,idx}}`. mDoc COSE/KMS/x5Chain nativi.

**Punti da calibrare in collaudo (non bloccanti):**
- Payload del webhook di notifica issuer2 vs ciò che si aspetta credy (`credential_offer_read`/`credential_issued`).
- **Coercion mdoc NON portata** (policy): i valori in `mdocData` devono già avere il tipo CBOR corretto (date full-date, portrait come bytes) — altrimenti correggere a monte in credy/setup.

## 8. Deploy / infra (Step 5) — fuori dal codice upstream

Da reimpostare in pipeline/values al deploy (NON nel sorgente walt-id):
- **verifier2**: `WaltCryptoAws.init()` — già presente in `issuer2/Main.kt:32`; verificare l'equivalente nel Main del verifier2 deployato.
- **`/livez`**: filtro CallLogging (rumore) → gestibile via config logging, non patch.
- **Dockerfile**: pin `gradle:9.2.0-jdk21` (il build reale è via pipeline Gradle, non i Dockerfile upstream).
- **Artifactory mirror** (`build-logic/waltid.base.gradle.kts`): solo per egress pipeline InfoCert; in upstream resta `maven.waltid.dev`.
- `walletProviderBaseUrl` issuer2 → impostare nei values per-ambiente se il wallet provider non è il default eudiw.dev.
