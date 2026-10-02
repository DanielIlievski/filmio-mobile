---
name: failure-first-engineering
description: Design, implement, review, or test fallible operations by defining and covering credible failure behavior before the happy path. Use when adding or changing network or database calls, validation, parsing, file or secure storage, authentication, permissions, external SDKs, concurrency or background work, optimistic updates, and stateful workflows. Do not use for purely presentational or deterministic changes with no meaningful failure boundary.
---

# Failure-First Engineering

Make failure behavior an explicit part of the feature contract, not cleanup after the success case works.

"Failure first" governs the order of analysis, implementation, and verification. It does not require unnatural source-code ordering, pessimistic UX, or exhaustive handling of impossible scenarios.

## Workflow

### 1. Identify the fallible boundary

Before changing code, inspect the relevant contracts and existing conventions. Record:

- inputs and preconditions;
- external or stateful dependencies;
- state mutated and side effects emitted;
- callers and consumers of the result;
- retry, cancellation, lifecycle, offline, and account implications.

Do not begin with a success-only API if failure is an expected outcome. Choose an explicit result, state, or exception boundary appropriate to the project and layer.

### 2. Define the failure contract before the success contract

Build a focused failure matrix for credible cases. For each case, decide:

| Concern | Decision |
|---|---|
| Detection | Status, stable code, exception type, validation rule, state, or timeout that identifies it |
| Representation | Typed error, state variant, event, or deliberately propagated exception |
| Ownership | Layer responsible for catching, mapping, or recovering |
| State | Loading, cached, pending, partial, failed, or unchanged state left behind |
| Side effects | What must happen, must not happen, or must be compensated |
| Recovery | Retry, re-authenticate, request permission, reconcile, edit input, or stop |
| User impact | Localizable message or silent internal handling; never expose raw technical text |
| Observability | Safe diagnostic context without secrets, tokens, or personal data |

Cover all failures promised by an authoritative contract, plus realistic boundary failures and an unknown fallback. Do not invent distinctions that callers cannot act on.

### 3. Implement and test failure paths first

Add the error representation, mapping, state transitions, recovery behavior, and failure tests before the success implementation. Make each test assert observable behavior and important negative effects, such as:

- state is preserved, reverted, or marked pending as designed;
- a write or navigation event did not occur;
- cancellation remains cancellation instead of becoming a generic failure;
- retries are bounded and safe for duplicate execution;
- partial work cannot leave inconsistent state;
- stale results cannot overwrite newer state;
- sensitive data is not logged or surfaced.

Use the project's dedicated error-handling and testing skills when their stacks apply. Follow existing domain, data, and presentation boundaries instead of creating a second error abstraction.

### 4. Implement the happy path

Only after the credible failure contract is represented and verified, implement success behavior. Confirm success clears or reconciles loading, pending, retry, and error state without weakening the failure guarantees.

### 5. Verify the complete behavior

Run the narrowest relevant checks, then review the change as a state machine rather than a collection of branches:

- every operation reaches a valid terminal or recoverable state;
- every documented failure maps deterministically;
- unexpected failures have a safe fallback;
- side effects happen at most as often as intended;
- retry and cancellation semantics remain correct;
- the happy path and recovery path both work after an earlier failure.

Do not call the work complete when only success behavior is implemented or tested.

## Failure Inventory

Select only categories that are credible for the boundary.

- **Input and domain:** missing, malformed, out-of-range, conflicting, duplicate, or stale input; invariant violations.
- **Network and remote services:** offline or DNS/TLS failure, timeout, cancellation, relevant HTTP statuses and stable API codes, authentication expiry, rate limiting, malformed or incompatible payloads, and service failure.
- **Database and storage:** not found, uniqueness or constraint failure, transaction rollback, unavailable or full storage, corruption or migration mismatch, concurrent modification, and account isolation.
- **Parsing and files:** missing, unreadable, oversized, truncated, unsupported, incorrectly encoded, or schema-incompatible content.
- **Permissions and platform services:** denied, permanently denied, revoked, unavailable capability, interrupted lifecycle, process restoration, or background-execution limits.
- **Concurrency and async work:** cancellation, timeout, duplicate invocation, races, stale completion, partial success, and lost wake-up or reconnect behavior.
- **Stateful flows:** invalid transition, missing navigation argument, repeated event, interrupted multi-step flow, or restoration into an inconsistent state.
- **Optimistic and offline work:** durable pending state, retry eligibility, idempotency, rollback or reconciliation, same-account/environment checks, and visible terminal failure.
- **Security and privacy:** unauthenticated, unauthorized, untrusted input, secret exposure, unsafe logging, and excessive error detail.

## Review Output

When reviewing instead of implementing:

1. Report missing or incorrect failure behavior as concrete findings, ordered by impact.
2. Identify the exact trigger and observable consequence.
3. Distinguish a contract gap from a missing test.
4. Acknowledge when relevant failure coverage is complete; do not manufacture edge cases merely to produce findings.

When planning or implementing, state the failure contract briefly before presenting the happy-path design so that reviewers can validate the intended behavior.
