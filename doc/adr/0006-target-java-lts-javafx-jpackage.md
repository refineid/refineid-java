# 6. Target current Java LTS, JavaFX, and jpackage

Date: 2026-08-07

## Status

Proposed

## Context

The application is a desktop GUI for end users on Linux, Windows, and
macOS. It needs a current JDK for provider features it depends on
(ECDSA through `SunMSCAPI` requires JDK 13+), a maintained desktop UI
toolkit, and a way to ship installers that do not require users to
manage a Java runtime.

## Decision

- Target the current Java LTS release (21 or later) and track LTS
  releases going forward.
- Build the UI with JavaFX (OpenJFX).
- Package with `jlink`/`jpackage` into per-platform, self-contained
  installers (deb/rpm, MSI, dmg/pkg) that bundle a trimmed runtime.

## Consequences

- Users install a native package and never see a JVM; the runtime is
  pinned and tested by the project rather than whatever the user has
  installed.
- Per-platform installers must be built on (or for) each platform in
  CI; the "one jar anywhere" distribution is not the shipping form,
  though it remains useful for development.
- JavaFX ships as separate modules included at `jlink` time.
- Status is Proposed rather than Accepted: the toolkit choice is the
  default recommendation and can be revisited before the first UI code
  lands without invalidating any other ADR.
