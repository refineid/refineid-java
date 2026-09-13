# 7. Sign many documents in one job

Date: 2026-08-09

## Status

Proposed

## Context

People who sign for a living do not sign one document. They sign the
day's batch, and signing software that accepts one file at a time is
rejected on that ground alone. On macOS the expected gesture is
concrete: several files are dropped on the drop area at once.

A signature is not a file operation, though, and the card decides how
many it will make per authorization. The RefineID card stack records
the rule plainly: PIN 2 "is never cached, so one verification serves
one signature" (`CardOperations+Signing.swift`). One PIN 2, one
signature — that is the card's rule and not a policy this application
may relax. It is also what a qualified signature means: the holder
authorizes each one.

That rule meets the batch requirement head on. Ten documents signed
individually is ten PIN 2 entries, whatever the interface looks like,
because ten signatures were created.

Two further facts constrain the design on macOS:

- The PKCS#11 module publishes the signing key with
  `CKA_ALWAYS_AUTHENTICATE` false (`IdentityObjects.swift`), so a
  PKCS#11 consumer such as `SunPKCS11` believes one `C_Login` covers
  many `C_Sign` calls. It does not: CryptoTokenKit prompts for PIN 2
  itself, per signing operation, below the PKCS#11 layer. The prompts
  appear whether or not Java expects them.
- A container signature is genuinely one signature. An ASiC-E
  container carries many files and is signed once, so "sign these
  twelve documents" becomes one signature and one PIN 2 — not a
  shortcut, but a different and equally valid legal object.

## Decision

- Accept a set of documents as one job. Selecting or dropping many
  files starts one job, not many.
- Offer both shapes, and name what each costs before it starts:
  - **One container (ASiC-E)** over all the documents: one signature,
    one PIN 2.
  - **Each document signed separately** (PAdES for PDFs): one
    signature and one PIN 2 per document.
- Never cache, replay, or pre-collect PIN 2 to reduce the count. The
  number of authorizations equals the number of signatures, always.
- Treat the job as a sequence with a reported outcome per document:
  which succeeded, which failed and why, and where each result was
  written. A failure at document seven does not discard one to six.
- Let the holder stop a running job between documents, and say how
  many remain.

## Consequences

- The interface must state, before a job starts, how many times the
  card will ask for PIN 2. A holder who expects one prompt and meets
  twelve concludes the software is broken.
- The container form is the answer for anyone who wants a single
  authorization over a batch, and it is offered for that reason rather
  than presented as an optimization.
- `SunPKCS11` cannot be relied on to model per-signature
  authentication, because on macOS the prompting happens below it.
  What the application can rely on is that each `C_Sign` either
  produces a signature or fails; the job sequences those and reports.
- Signing without a visible per-document result is not acceptable: a
  batch that reports only "done" hides which documents were signed.
