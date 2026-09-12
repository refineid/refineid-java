# 1. Record architecture decisions

Date: 2026-08-07

## Status

Accepted

## Context

RefineID-Java starts as an empty repository, but its architecture was
settled through design discussion before any code was written. Those
decisions and their rationale need to live with the code, in a form
that survives contributor turnover.

## Decision

We record significant architecture decisions as Architecture Decision
Records (ADRs), in the format described by Michael Nygard, numbered
sequentially in `doc/adr/`. Records are immutable: a superseded
decision gets a new ADR that references the old one, and the old ADR's
status is updated to "Superseded by ADR-NNNN".

## Consequences

Anyone joining the project can read `doc/adr/` in order and understand
why the system has the shape it has. Changing a fundamental decision
requires writing it down.
