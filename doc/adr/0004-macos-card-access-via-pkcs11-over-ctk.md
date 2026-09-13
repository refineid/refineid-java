# 4. macOS card access via a PKCS#11 module over CryptoTokenKit

Date: 2026-08-07

## Status

Accepted

## Context

ADR-0002 routes card access through JCA providers, but macOS has a
gap: the JDK has no provider that reaches CryptoTokenKit (CTK) tokens
— the Apple keychain provider covers only file-based keychains.

Two options exist to reach the card from Java on macOS:

1. Build the RefineID-Unix PC/SC-based PKCS#11 module for macOS. This
   creates a second card stack on the platform and contends with the
   system token daemon for the card — the classic macOS smartcard
   conflict (as seen with OpenSC alongside CTK drivers).
2. A PKCS#11 module implemented *on top of* CTK / Security.framework:
   it enumerates token-backed identities via `SecItemCopyMatching` and
   signs via `SecKeyCreateSignature`, so the system token daemon and
   the RefineID CTK extension do all card communication. Prior art:
   [keychain-pkcs11](https://github.com/kenh/keychain-pkcs11).

Security.framework exposes token keys as read-and-sign only, which
matches the PKCS#11 v2.40 read-only, sign-only profile RefineID
already ships on Linux.

Apple itself ships a minimal CTK-to-PKCS#11 bridge for ssh,
`/usr/lib/ssh-keychain.dylib`. Tested 2026-08-07 against real
hardware: with a 2026-generation FINEID card matched by the RefineID
token extension, the token published exactly two identities, both EC
P-384, and `ssh-keygen -D /usr/lib/ssh-keychain.dylib` failed with
"provider returned no slots" — the bridge handles RSA identities only.
(The card family may also carry RSA keys, but ECC is the default
enrollment and was all this token exposed.) Apple's bridge is
therefore unusable for ECC-enrolled FINEID cards, and it does not
serve NSS/Firefox or `SunPKCS11` in any case.

## Decision

macOS card access goes through a PKCS#11-over-CTK bridge module,
implemented and maintained in the RefineID-Apple repository (Swift
behind a C `C_GetFunctionList` entry surface), loaded by `SunPKCS11`
like any other PKCS#11 module.

The module advertises `CKF_PROTECTED_AUTHENTICATION_PATH` so PIN entry
uses the system dialog rather than passing PINs through Java.

## Why not the provider the JDK already ships

The JDK carries an `Apple` provider with a `KeychainStore`, and the
obvious question is why a module is needed when the operating system
already offers signing. Measured on macOS 26 with a FINEID card in the
reader: `KeychainStore` listed nine key entries, all of them software
keys from the login keychain -- developer certificates and a localhost
key -- and not the card. `KeychainStore` reads the legacy file-based
keychain; a CryptoTokenKit identity is a token item in the
data-protection keychain, which is a different API and one that
provider does not call.

The other route the JDK offers on macOS is `SunPCSC`, which is raw
PC/SC: it would mean writing card-protocol code in Java, the one thing
ADR-0002 exists to prevent.

Windows is genuinely different, and there the answer is yes: `SunMSCAPI`
reaches the card through the platform, so no module ships with the Java
application there. Linux offers no such service at all, and PKCS#11 is
the mechanism. So the module is a macOS and Linux need, not a
cross-platform one.

## What a module may declare that a card cannot honour

A PKCS#11 module tells consumers, per mechanism, the key sizes it
supports. `SunPKCS11` enforces that declaration verbatim: the JDK reads
`iMinKeySize` and `iMaxKeySize` straight from `C_GetMechanismInfo` and
refuses any key outside the range, with `InvalidKeyException`. There is
no policy file, system property or provider option that overrides it,
and the JCE unlimited-strength policy files people remember have not
existed since Java 9.

Measured against a module that declares `2048..2048` for every signing
mechanism, elliptic-curve mechanisms included: a 384-bit curve key is
refused as too small and a 3072-bit RSA key as too large, on a card
that holds both and signs with either. The declaration cannot be right
for both, and no Java-side setting rescues it.

Two things follow. A module RefineID ships must declare per-mechanism
limits that match what the card holds, or every SunPKCS11 consumer on
that machine loses signing. And the provider-agnostic promise of
ADR-0002 has a boundary worth naming: this application works with any
module that describes itself correctly, which is not the same as any
module.

## Consequences

- The CTK extension remains the only code touching the card on Apple
  platforms, preserving one card stack per platform (ADR-0002) and the
  role of the Swift implementation as the behavior reference.
- No card contention: all access is multiplexed by the system token
  daemon, which also provides PIN caching and dialogs.
- The bridge benefits more than this application — any PKCS#11
  consumer on macOS (Firefox/NSS card login, OpenSSH) gains FINEID
  support from the same module. For OpenSSH with ECC FINEID cards it
  is the *only* CTK-based path, since Apple's own bridge is RSA-only.
- ECDSA (P-384 first) is the primary signing path of the module, not
  an add-on: ECC is the default enrollment on current FINEID cards and
  the tested token published only EC identities. RSA support follows,
  since the card family and the token extension both support RSA
  profiles.
- `ssh-agent` restricts PKCS#11 providers to an allowlist (by default
  `/usr/lib*` and `/usr/local/lib*`); the module's install location or
  documented `ssh-agent -P` override must account for this.
- macOS support in RefineID-Java is blocked on that module shipping;
  it does not exist yet. Until then the application runs on Linux and
  Windows only.
- Integration risk to retire early: `SunPKCS11` and DSS must be tested
  against the bridge, particularly `CKA_ID` consistency between
  certificate and private-key objects.
- The bridge installs one binary under two names with disjoint
  exposure profiles; no name exposes everything. The default,
  `librefineid_pkcs11.dylib`, serves authentication consumers (ssh,
  browsers) and withholds identities whose certificate declares
  contentCommitment (nonRepudiation), so the legally weighty
  qualified-signature key is never ambient in every PKCS#11-loading
  process or PIN-cached by an ssh-agent. RefineID-Java's `SunPKCS11`
  configuration on macOS points at `librefineid_pkcs11_sign.dylib`,
  which exposes only the qualified-signature identity -- the signing
  key picker cannot offer the wrong key.
