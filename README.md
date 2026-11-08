# RefineID-Java

Future work: a cross-platform document-signing application for Finnish
identity cards. The aim is PAdES / CAdES / ASiC-E signing and validation
with qualified timestamps and EU trusted-list handling, built on
[EU DSS](https://github.com/esig/dss).

The design keeps smartcard-protocol code out of the application. Card
access is designed to go through the Java Cryptography Architecture to
the platform's RefineID card stack: the PKCS#11 module from
[RefineID-Unix](https://github.com/refineid/refineid-unix) on Linux
and BSD, the RefineID minidriver via MSCAPI on Windows, and a
PKCS#11-over-CryptoTokenKit bridge from
[RefineID-Apple](https://github.com/refineid/refineid-apple) on macOS.

Architecture decisions are recorded as ADRs in [doc/adr/](doc/adr/).
Start there; ADR-0002 is the load-bearing one.

## Project stage

This is future work and is not under active development. The repository
holds the architecture decisions and a bare-bones start, not a usable
application. No release has been made.

What the source has today:

- A small JavaFX application with a PIN prompt, a timestamp-authority
  dialog and a job plan for signing several documents.
- Signing through a PKCS#11 module with EU DSS, as PAdES or ASiC-E
  (XAdES).
- A script that builds a macOS application bundle, not an installer.
- Unit tests for the job plan and the signed-file naming only.

What it does not have yet:

- CAdES signing.
- Validation of existing signatures and EU trusted-list handling.
- Card access on Windows (MSCAPI).
- A default PKCS#11 module path for Linux and BSD. The default is the
  macOS one; another module is named with the `refineid.module` system
  property.
- Installers on any platform.
