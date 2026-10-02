---
name: android-testing
description: "Test strategy for Filmio domain, Retrofit data, and MVI ViewModels. Use when planning or reviewing unit tests for pagination, details, search, mapping, errors, or Compose behavior."
---

# Android Testing

The assignment explicitly requires unit tests for domain, data, and ViewModels. Test meaningful behavior and failure paths; the PDF does not prescribe a test framework or coverage percentage. JUnit, `kotlinx-coroutines-test`, and a Flow assertion library such as Turbine are suitable choices. Add only libraries used by the tests.

## Domain

- Test non-trivial validation, mapping, or coordination rules when they exist. Do not create a forwarding UseCase just to produce a domain test.
- Keep domain tests as plain JVM tests without Android, Retrofit, database, or Compose dependencies.

## Data

- Test TMDB DTO-to-domain mapping, absent/nullable fields, pagination metadata, and repository behavior.
- Use a controllable HTTP server such as MockWebServer for Retrofit integration at the data boundary. Cover representative success, HTTP failure, connection/timeout, malformed response, and cancellation behavior. Verify that raw DTOs and exceptions do not escape the repository contract.
- Test optional persistence only if favorites or offline storage are actually implemented.

## ViewModels

- Inject fake feature-domain repositories. Use `kotlinx-coroutines-test` to control timing, dispatchers, and Flow collection; no real TMDB calls in ViewModel tests.
- Verify state and one-time events for initial loading, content, empty results, pagination/end-of-list, page retry, detail load/error, and navigation.
- Verify search query changes, movie/series selection, chosen throttle/debounce interval, cancellation, and stale-response behavior. Use virtual time rather than wall-clock sleeps.
- Keep `SavedStateHandle` tests focused on inputs that must survive restoration.

Compose UI tests are useful for critical interactions but do not replace the PDF's unit tests. Prefer a small number of behavior-focused tests; add a robot abstraction only when repeated multi-step UI journeys make it worthwhile.
