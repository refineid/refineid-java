# 2. Delegate card access to platform crypto stacks

Date: 2026-08-07

## Status

Accepted

## Context

The ReFineID project already maintains one smartcard protocol
implementation per platform, each proven against real FINEID hardware:

- a CryptoTokenKit token extension on Apple platforms
  ([ReFineID-Apple](https://github.com/ReFineID/ReFineID-Apple)),
- a smart card minidriver on Windows,
- a PKCS#11 v2.40 module (read-only, sign-only) on Linux and BSD
  ([ReFineID-Unix](https://github.com/ReFineID/ReFineID-Unix)).

A Java application could talk to the card directly through
`javax.smartcardio`, but that would create yet another implementation
of APDUs, file selection, and PIN verification that must be kept
behaviorally in sync with the existing ones.

The Java Cryptography Architecture (JCA) already provides the needed
abstraction: certificates and private keys are exposed through
`java.security.KeyStore`, and signing through `java.security.Signature`,
with pluggable providers.

- `SunPKCS11` (bundled with the JDK) exposes any PKCS#11 module as a
  JCA provider.
- `SunMSCAPI` (bundled with the JDK on Windows) exposes the
  `Windows-MY` certificate store, which routes smartcard operations
  through the installed minidriver via CAPI/CNG.

## Decision

ReFineID-Java contains no card-protocol code. It never opens a card
channel, never sends an APDU, and does not use `javax.smartcardio`.

All key discovery and signing goes through the JCA `KeyStore` /
`Signature` abstraction, backed per platform by:

| Platform | JCA provider | Card stack underneath |
| --- | --- | --- |
| Linux, BSD | `SunPKCS11` | `librefineid_pkcs11.so` from ReFineID-Unix |
| Windows | `SunMSCAPI` (`Windows-MY`) | ReFineID minidriver |
| macOS | `SunPKCS11` | PKCS#11-over-CryptoTokenKit module, see ADR-0004 |

PIN entry is delegated to the platform stack where possible: on
Windows the minidriver/CNG UI prompts, and PKCS#11 modules advertising
`CKF_PROTECTED_AUTHENTICATION_PATH` prompt through their own or the
system's dialog. Only where a module requires `C_Login` with a PIN
value does the application collect it, and it never persists it.

## Consequences

- The application stays small and portable: pure Java over standard
  JDK providers, one codebase for all three desktop platforms.
- Each platform's single proven card stack remains the only code
  touching the card; there is no third protocol implementation to keep
  in sync.
- The application requires the platform middleware to be installed; it
  is not self-contained. Installation documentation must state this
  per platform.
- Functionality is capped at what the read-and-sign profile exposes:
  no PIN change, no PUK unblock, no card-holder image readout
  (see ADR-0005).
- ECDSA through `SunMSCAPI` requires a modern JDK (13+); the project
  targets a current LTS anyway (ADR-0006), so 2026 ECC cards are
  covered.
- Provider quirks become integration risks: `SunPKCS11` is strict
  about PKCS#11 attribute consistency (notably `CKA_ID` linking
  certificate and key objects), so each backing module must be
  exercised in integration tests.
