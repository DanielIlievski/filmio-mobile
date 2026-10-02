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

- Test TMDB DTO-to-entity-to-domain mapping, absent/nullable fields, Room transactions, Paging keys/end markers, and repository behavior.
- Use a controllable HTTP server such as MockWebServer for Retrofit integration at the data boundary. Cover representative success, HTTP failure, connection/timeout, malformed response, and cancellation behavior. Verify that raw DTOs and exceptions do not escape the repository contract.
- Test the selected Room offline contract: cached relaunch, uncached offline state, favorite writes without network, favorites surviving refresh, query/media-type isolation, mediator retry, and migration preservation. Use a real Room database for transaction-sensitive behavior; use Paging test utilities where helpful.

## ViewModels

- Inject fake feature-domain repositories. Use `kotlinx-coroutines-test` to control timing, dispatchers, and Flow collection; no real TMDB calls in ViewModel tests.
- Verify state and one-time events for initial loading, cached content, true empty versus uncached offline results, Paging load-state retry, detail load/error, favorites, and navigation.
- Verify search query changes, movie/series selection, chosen throttle/debounce interval, cancellation, and stale-response behavior. Use virtual time rather than wall-clock sleeps.
- Keep `SavedStateHandle` tests focused on inputs that must survive restoration.

Compose UI tests are useful for critical interactions but do not replace the PDF's unit tests. Prefer a small number of behavior-focused tests; add a robot abstraction only when repeated multi-step UI journeys make it worthwhile.
