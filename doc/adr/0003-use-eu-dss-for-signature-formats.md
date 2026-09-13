# 3. Use EU DSS for signature formats and validation

Date: 2026-08-07

## Status

Accepted

## Context

Qualified document signing with a FINEID card requires, beyond the raw
card signature: PAdES, CAdES, and ASiC-E envelope formats, RFC 3161
timestamps, revocation checking, and signature validation against the
EU/eIDAS trusted lists (LOTL/TL).

The sibling RefineID implementations built this from scratch. In the
Java ecosystem it exists off the shelf: Digital Signature Services
(DSS), maintained for the European Commission, is the reference
implementation of the eIDAS signature formats and trusted-list
validation. Comparable national signing tools (for example Estonia's
DigiDoc ecosystem, via digidoc4j) are built on it.

DSS also ships signature-token bindings that match ADR-0002 exactly:
`Pkcs11SignatureToken` (over `SunPKCS11`) and `MSCAPISignatureToken`
(over `SunMSCAPI`).

## Decision

RefineID-Java uses EU DSS for signature creation (PAdES baseline
profiles, CAdES, ASiC-E), timestamping, revocation data collection,
and validation including EU trusted-list handling, with Bouncy Castle
as the crypto provider and Apache PDFBox as the PDF backend. Card
signing is wired in through DSS's token abstraction on top of the JCA
providers from ADR-0002.

We do not re-implement any signature format, and we do not fork DSS.

## Consequences

- The largest and riskiest part of a signing application — format and
  validation correctness — is delegated to the EC-maintained reference
  implementation.
- DSS brings a sizeable dependency tree. This is a deliberate departure
  from the from-scratch discipline of the sibling repositories, and it
  makes dependency and vulnerability tracking (and keeping up with DSS
  releases) an ongoing maintenance duty.
- Signature and validation behavior may differ in detail from
  RefineID-Unix and RefineID-Apple, which have their own
  implementations. Cross-implementation test vectors are the guard.
- Trusted-list downloads and timestamp authority calls require network
  access; offline behavior must be defined explicitly in the
  application.
