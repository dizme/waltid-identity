# TLS per l’issuer (Caddy, opzione 2 stack wallet2)

I file qui servono al listener **HTTPS** di Caddy su `ISSUER_API_TLS_PORT` (vedi `.env`).  
Senza `tls.crt` / `tls.key` il container **caddy** non parte.

## 1. Generare certificati con mkcert

Sul PC di sviluppo (host con `mkcert` installato), nella stessa LAN del telefono:

```bash
cd waltid-identity/docker-compose/certs
mkcert -install   # una tantum sul PC (root CA nel trust del browser/OS)
# Includi SERVICE_HOST dal .env, localhost e gli indirizzi usati dagli script
mkcert -cert-file tls.crt -key-file tls.key \
  192.168.1.8 localhost 127.0.0.1 ::1
```

Sostituisci `192.168.1.8` con il valore attuale di `SERVICE_HOST` in `wallet2/waltid-identity/docker-compose/.env`.

## 2. Root CA sul telefono Android (EUDI wallet)

La app EUDI non considera fidato il leaf `mkcert` finché la **root CA** di mkcert non è fra i certificati utente.

Opzione A (consigliata):

1. Copia sul telefono `$(mkcert -CAROOT)/rootCA.pem`
2. Impostazioni → Sicurezza → Installa un certificato → CA → seleziona `rootCA.pem`

Opzione B: la build include già `network_security_config` con `trust-anchors` **user** + **system** (`eudi-app-android-wallet-ui/network-logic/...`), quindi i certificati utente sono ammessi dopo installazione della CA.

## 3. Script Python sul PC (`generate_*_qr.py`)

Gli script aprono `https://localhost:<ISSUER_API_TLS_PORT>` verificando la catena con la stessa root.

Imposta (facoltativo se esiste `mkcert` nel `PATH`):

```bash
export WALTID_TLS_CA_PATH="$(mkcert -CAROOT)/rootCA.pem"
```

Se `mkcert -CAROOT` fallisce, esporta esplicitamente il path al file `rootCA.pem`.

## 4. Riavvio stack

```bash
cd waltid-identity/docker-compose
COMPOSE_PROFILES=services docker compose up -d issuer-api caddy
```

L’issuer pubblica ora `credential_issuer` / `credential_offer_uri` come **https** (`issuer-api/config/issuer-service.conf`), come richiesto dalla libreria OpenID4VCI della EUDI Android app.

## 5. `SSLHandshakeException: Trust anchor for certification path not found`

Significa: il client Android non ha un **anchor** (root) che valida `tls.crt` servito da Caddy.

### A) Stai usando **mkcert** (dev)

1. **Stesso `rootCA.pem` su PC e telefono**  
   Dopo `mkcert -install` sul PC, copia `$(mkcert -CAROOT)/rootCA.pem` sul telefono e installalo come **CA utente** (Impostazioni → Sicurezza → Installa certificato → CA).

2. **Le build EUDI “stock” spesso non fidano le CA utente**  
   `Network Security` predefinita o quella del catalogo EUDI può usare solo `system`. In quel caso, **installare la root mkcert sul telefono non basta** e continuerai a vedere `Trust anchor … not found` anche con URL giusto.

   Possibilità senza toccare Kotlin/manifest esterni:
   - applicare **solo** la patch `eudi-app-android-wallet-ui/network-logic/src/main/res/xml/network_security_config.xml` che aggiunge `<certificates src="user" />` sotto `trust-anchors` (il manifest assembly punta già a `@xml/network_security_config`), oppure
   - usare la sezione **B** (certificato pubblico).

3. **SAN corretti**: rigenera `tls.crt` / `tls.key` includendo **l’IP o l’hostname** che userà il wallet nell’URL (es. `192.168.1.8` dal `.env`).

### B) Nessuna modifica all’APK — certificato da **CA pubblica** (Let’s Encrypt, ecc.)

Android si fida già delle root nei trust **di sistema**: sostituisci i file locali `tls.crt` / `tls.key` con una catena valida pubblicamente (tipicamente **fullchain** + **chiave privata**) ottenuti per un **nome DNS** (`issuer.example.org`), non per un solo IP privato.

Operativamente:

1. Ottieni certificati (es. **certbot**, **lego**, ACME sul router) per `issuer.example.org`. La validazione può essere **DNS-01** se il PC è solo in LAN.
2. Copia sul host i file PEM nel volume `docker-compose/certs/` come `tls.crt` (fullchain) e `tls.key` (private key), permessi leggibili da Caddy.
3. In `docker-compose/.env` imposta `SERVICE_HOST=issuer.example.org` (stesso nome che risolve dal telefono verso il PC di test: DNS LAN, `/etc/hosts` sul router, o risoluzione pubblica).
4. Riavvia **caddy** e **issuer-api** così `issuer-service.conf` e le offer puntano a `https://issuer.example.org:7443/...`.
5. Rigenera la credential offer / QR così `credential_issuer` usa il **hostname**, non l’IP.

In questo modo il wallet EUDI non richiede né CA utente né `network_security_config` personalizzato.

### C) Debug rapido

```bash
# Dal PC: catena vista dal client (stesso host/porta del telefono)
openssl s_client -connect 192.168.1.8:7443 -servername 192.168.1.8 </dev/null 2>/dev/null | openssl x509 -noout -subject -issuer -dates
```

Il Subject deve combaciare con l’host nell’URL del wallet; l’Issuer deve essere una CA che il telefono già considera fidata (**system**) se non usi patch `user`.
