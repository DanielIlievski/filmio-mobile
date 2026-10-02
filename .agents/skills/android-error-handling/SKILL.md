---
name: android-error-handling
description: "Typed errors and Retrofit failure mapping for the Filmio Android assignment. Use when defining domain errors, translating TMDB HTTP or transport failures, or presenting recoverable errors in MVI state and events."
---

# Android Error Handling

The assignment requires proper handling of TMDB network errors. With the selected Room source of truth, distinguish cached content with a refresh failure, a truly empty result, and an uncached offline request. Model expected failures at the data boundary and give the user useful loading, error, and retry states.

## Shared result types

When typed errors improve call sites, keep a small pure-Kotlin `Result<D, E>` and `Error` contract in `core:domain`. Use a feature-domain error contract when an error has business meaning. Do not create a large result/helper framework just to wrap Retrofit.

```kotlin
interface Error

sealed interface Result<out D, out E : Error> {
    data class Success<out D>(val data: D) : Result<D, Nothing>
    data class Failure<out E : Error>(val error: E) : Result<Nothing, E>
}

typealias EmptyResult<E> = Result<Unit, E>
```

Simple `map`, `onSuccess`, and `onFailure` helpers are optional if they make repeated handling clearer. Keep all shared domain types free of Android, Retrofit, OkHttp, and JSON imports.

## Retrofit boundary

- Retrofit `suspend` service calls belong in data. Translate non-successful HTTP responses, timeouts, connectivity/IO errors, and JSON decoding failures into a small domain-facing error vocabulary. Preserve enough distinction to show a useful message and determine whether retry makes sense.
- Inspect HTTP status only at the data boundary. For example, treat authorization/configuration errors differently from missing content, rate limits, and server failures. Do not pass `HttpException`, `Response`, OkHttp requests, or raw exception text into domain or presentation.
- Catch `CancellationException` separately and rethrow it immediately. A canceled page load or superseded search is not a user-visible network failure.
- Avoid broad catches that silently turn programming defects into an `UNKNOWN` network error. Log diagnostic detail without credentials; show safe, localized messages in the UI.
- A reusable Retrofit call wrapper is appropriate only if multiple endpoints need the same mapping. Keep endpoint-specific error interpretation with the owning feature's data code.

## MVI presentation

- Represent durable loading and retry state in `<Feature>State`. A transient snackbar or navigation response belongs in `<Feature>Event`.
- Map domain errors to user-facing text in presentation or approved shared UI code. Keep resource IDs and Compose types out of domain.
- Support Paging load-state retry for failed list refresh/append while preserving cached rows; support detail/search retry where meaningful. Show an uncached offline request as unavailable rather than empty. Clear obsolete errors on a new request, and prevent an older response from overwriting the latest search state.
- Unit-test representative success, HTTP failure, offline/timeout, malformed-response, and cancellation paths in data, plus ViewModel state/event handling of those outcomes.
