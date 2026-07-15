# TravelAgencyOps — ISIC 791 Travel Agency Booking Coordination Actor

Travel agency and tour operator activities booking coordination
([ISIC Rev. 5 Division 79.1](https://unstats.un.org/unsd/classifications/Econ/Detail/791000))

A langgraph-clj StateGraph actor that coordinates travel booking logistics:
itinerary-booking scheduling, booking-status tracking, and travel supply
coordination. The closed :propose-only allowlist limits the actor to
administrative coordination duties — never visa/immigration eligibility,
travel insurance/liability determinations, refund/cancellation policy
decisions, or safety-authority overrides.

## Features

- Closed operation set: Five proposals only
- Three HARD governor checks (booking-verification, effect, scope-exclusion)
- Audit-first ledger (append-only journal)
- Portable .cljc (runs on JVM, nbb, browser)
- Rollout phases 0→3

## Running

```bash
# Mock advisor, demo simulation:
nbb -m travelagencyops.sim

# Or Clojure:
clojure -M:dev:run

# Tests:
clojure -M:dev:test

# Lint:
clojure -M:lint
```

## Governance

AGPL-3.0-or-later, Contributor Covenant Code of Conduct.

TravelAgencyOps handles booking logistics only. Decisions requiring licensed
travel professional judgment (visa eligibility, insurance underwriting, refund
policy) are always escalated to humans, never auto-committed.

## Registry

Registered in kotoba-lang/industry as isic-791, :implemented.
