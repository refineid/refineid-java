# ReFineID-Java

Cross-platform document-signing application for Finnish identity
cards: PAdES / CAdES / ASiC-E signing and validation with qualified
timestamps and EU trusted-list handling, built on
[EU DSS](https://github.com/esig/dss).

The application contains no smartcard-protocol code. Card access is
delegated through the Java Cryptography Architecture to the platform's
ReFineID card stack: the PKCS#11 module from
[ReFineID-Unix](https://github.com/refineid/refineid-unix) on Linux
and BSD, the ReFineID minidriver via MSCAPI on Windows, and a
PKCS#11-over-CryptoTokenKit bridge from
[ReFineID-Apple](https://github.com/refineid/refineid-apple) on macOS.

Architecture decisions are recorded as ADRs in [doc/adr/](doc/adr/).
Start there; ADR-0002 is the load-bearing one.

Project stage: pre-implementation. No code yet.
