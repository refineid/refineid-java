# 5. Scope version 1 to document signing

Date: 2026-08-07

## Status

Accepted

## Context

The sibling RefineID applications offer, beyond document signing: PIN
activation and change, PUK unblock, and viewing the card's portrait
and signature images. Those features require direct card access (PIN
management APDUs, card file readout), which ADR-0002 deliberately
excludes — the platform crypto stacks expose only certificate
enumeration and signing.

On Windows, PIN management is idiomatically the minidriver's and the
operating system's job in any case.

## Decision

Version 1 of RefineID-Java does exactly one job: signing documents
(PAdES / CAdES / ASiC-E per ADR-0003) with a FINEID card, plus
validation of existing signatures. No PIN management, no PUK unblock,
no card-holder image readout, no card administration of any kind.

## Consequences

- The application's feature set is fully deliverable through the JCA
  abstraction; nothing in v1 pressures ADR-0002.
- Users needing PIN management are directed to the platform's native
  tooling (RefineID-Unix GUI/CLI, RefineID-Apple, Windows).
- If card administration is ever added, it will need a direct card
  path (`javax.smartcardio`) behind a separate interface and a new ADR
  revisiting ADR-0002.
