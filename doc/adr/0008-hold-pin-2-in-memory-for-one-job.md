# 8. Hold PIN 2 in memory for one job

Date: 2026-11-08

## Status

Accepted. Supersedes the clause of ADR-0007 that forbids caching, replaying
or pre-collecting PIN 2.

## Context

ADR-0007 forbids caching, replaying or pre-collecting PIN 2 to reduce
the number of times a holder types it. The card's rule is unchanged: it
verifies PIN 2 for every signature and carries no verification from one
signature to the next. What software decides is where the value comes
from each time: from a person, or from a value that person entered once.

Someone who signs a small batch of documents separately (PAdES) should
not have to type the same PIN 2 for each one, when the application told
them before the entry how many signatures that one entry authorizes.
The implementation already works this way as
`PinPolicy.ASK_ONCE_FOR_THE_JOB`, which contradicted ADR-0007.

## Decision

- When the token expects the caller to supply PIN 2, the holder types it
  once for a job, and the application presents that value to the card
  for each signature of the job. The card still verifies it for every
  signature.
- Before the entry, the application says how many signatures that entry
  authorizes. The job plan and the PIN dialog both say it.
- The application keeps its own copy of PIN 2 only in memory, as a
  `char[]`, for the length of one job. It never writes it to disk: no
  file, preference, keychain entry or log line.
- The application overwrites that copy with zeros when the job ends,
  whether the job completes, fails or is stopped.
- Nothing carries PIN 2 from one job to the next.
- When the token collects PIN 2 itself (`PinPolicy.ASK_EACH_SIGNATURE`),
  a system dialog asks for each signature and the application never sees
  the value. The token decides which of the two cases applies; the
  holder does not choose.
- This decision is for a small batch of documents in one job. It sets no
  number of documents.

## Consequences

- ADR-0007's clause "Never cache, replay, or pre-collect PIN 2 to reduce
  the count" no longer holds. Its other decisions stand, including that
  the container form (one signature, one PIN 2) is offered and that the
  interface states the number of PIN 2 entries before a job starts.
- The zeroing covers the array the application owns. The text typed into
  the JavaFX password field exists as Java `String` objects, in the
  control and in the result of the `getText()` call that reads it, until
  they are garbage collected, and this application cannot zero a
  `String`. Copies held by the PKCS#11 provider or EU DSS were not
  examined.
