---
name: android-data-layer
description: "Data-layer guidance for the Filmio Android assignment: Retrofit and OkHttp TMDB access, DTO mapping, domain repositories, pagination, optional persistence, and data tests. Use when designing or reviewing API services, data sources, repositories, mappers, or storage."
---

# Android Data Layer

Read `docs/Android Technical Assignment-Senior.md` before making implementation choices. The assignment requires Retrofit for TMDB API calls; its favorites and offline storage are optional.

## Boundaries

- Put domain models and the contracts needed by presentation in the owning feature's `domain` module. Domain contains no Retrofit, OkHttp, JSON, database, or Android UI types.
- Put Retrofit service interfaces, request/response DTOs, JSON configuration, remote data sources, data mappers, and repository implementations in `data` or approved shared infrastructure. Map DTOs to domain models before returning through a domain contract.
- A repository can be the domain contract for one remote source. Do not require multiple sources merely to justify the name. Add local and remote data sources when coordinating them actually helps.
- Data implementations depend inward on their own feature domain. Presentation depends on domain contracts and never imports data implementations.
- Keep TMDB response shapes, pagination fields, image paths, and nullable values in data. Expose only the fields and error semantics useful to the feature domain.

## Retrofit stack

- Define typed Retrofit service methods for the TMDB endpoints the implemented screens need. Keep base URL, endpoint paths, query parameters, and DTOs in the data layer.
- Configure a shared `OkHttpClient` for connection behavior and an appropriate HTTP interceptor for API authorization. Configure Retrofit with that client and one JSON converter, such as Moshi with `converter-moshi` and Kotlin model support (`moshi-kotlin` or generated adapters). Add a logging interceptor only if it provides diagnostic value, and redact credentials and sensitive headers.
- Supply the TMDB credential through build-time configuration or another non-committed secret source; do not hardcode it in Kotlin or commit it. Inject the configured Retrofit service through the chosen DI framework.
- Do not use Ktor for TMDB HTTP networking. Keep Retrofit and OkHttp out of domain and presentation APIs.
- Use `suspend` Retrofit calls with coroutines. Catch and translate transport, HTTP, and parsing failures at the data boundary according to `android-error-handling`. Rethrow coroutine cancellation.
- For unit tests, use a fake domain repository for ViewModels and a controllable HTTP endpoint such as `MockWebServer` when verifying Retrofit, mapping, pagination, or error behavior in the data layer.

## Assignment flows

- Main list: support incremental page loading for infinite scroll. Preserve the API's end-of-list signal, avoid duplicate concurrent requests, and make retry of a failed page possible.
- Details: load the selected movie's extended information by a stable ID. Include supported ratings, votes, and author-related data where available; model absent fields safely.
- Search: represent the user's movie-versus-series selection explicitly. Make request rate limiting controllable, choose and document throttle/debounce semantics during implementation, and prevent stale responses from replacing newer results. Keep the timing policy in presentation or a meaningful domain operation, not in the Retrofit service.
- Fetch images with an image loader from presentation. Data can expose a validated image URL or path, but must not depend on Compose or Coil.

## Persistence

The PDF does not require a database, favorites, token storage, or offline mode. Introduce Room or DataStore only for a chosen optional feature with a clear persistence need. If offline mode is implemented, define cache and source-of-truth behavior deliberately and keep entities and DAOs in data or a dedicated database module. Do not add auth refresh or token storage flows without an actual requirement.

## UseCases

UseCases are optional. Call a domain repository contract directly from a ViewModel for a simple operation. Introduce a UseCase only for meaningful reusable business logic, multiple repository coordination, non-trivial transformation, or logic duplicated across ViewModels. Never add a one-to-one forwarding UseCase solely to complete a layer diagram.
