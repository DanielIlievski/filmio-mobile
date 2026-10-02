---
name: android-data-layer
description: "Data-layer guidance for Filmio's Retrofit/OkHttp TMDB access, Room source of truth, Paging 3, mapping, local favorites, and data tests. Use when designing or reviewing services, repositories, mappers, pagination, or storage."
---

# Android Data Layer

Read the assignment PDF before making implementation choices. It requires Retrofit for TMDB API calls and lists favorites/offline storage as optional; the user chose to implement both. Follow `android-offline-first` for the selected Room/Paging design.

## Boundaries

- Put domain models and the contracts needed by presentation in the owning feature's `domain` module. Domain contains no Retrofit, OkHttp, JSON, database, or Android UI types.
- Put Retrofit services/DTOs, Room entities/DAOs, data mappers, Paging mediators, and repository implementations in `data` or justified shared infrastructure. Map DTOs to entities and entities to domain models before crossing a domain contract.
- For catalog data, the repository coordinates remote and local sources: Room is the canonical read source, while Retrofit refreshes it. Do not return direct Retrofit results to the UI.
- Data implementations depend inward on their own feature domain. Presentation depends on domain contracts and never imports data implementations.
- Keep TMDB response shapes, remote page keys, image paths, Room entities, and nullable wire values in data. Expose only fields, paging contracts, and error semantics useful to domain/presentation.

## Retrofit stack

- Define typed Retrofit service methods for the TMDB endpoints the implemented screens need. Keep base URL, endpoint paths, query parameters, and DTOs in the data layer.
- Configure a shared `OkHttpClient` for connection behavior and an appropriate HTTP interceptor for API authorization. Configure Retrofit with that client and one JSON converter, such as Moshi with `converter-moshi` and Kotlin model support (`moshi-kotlin` or generated adapters). Add a logging interceptor only if it provides diagnostic value, and redact credentials and sensitive headers.
- Supply the TMDB credential through build-time configuration or another non-committed secret source; do not hardcode it in Kotlin or commit it. Inject the configured Retrofit service through the chosen DI framework.
- Do not use Ktor for TMDB HTTP networking. Keep Retrofit and OkHttp out of domain and presentation APIs.
- Use `suspend` Retrofit calls with coroutines. Catch and translate transport, HTTP, and parsing failures at the data boundary according to `android-error-handling`. Rethrow coroutine cancellation.
- For unit tests, use a fake domain repository for ViewModels and a controllable HTTP endpoint such as `MockWebServer` when verifying Retrofit, mapping, pagination, or error behavior in the data layer.

## Assignment flows

- Main list: use AndroidX Paging 3 with a Room `PagingSource` and Retrofit-backed `RemoteMediator`. Preserve scoped remote keys and the API's end-of-list signal; use Paging load states and retry rather than a second manual paginator.
- Details: load the selected movie's extended information by a stable ID. Include supported ratings, votes, and author-related data where available; model absent fields safely.
- Search: represent movie-versus-series selection explicitly; cache result membership by query and type. Make request rate limiting controllable, choose and document throttle/debounce semantics during implementation, and prevent stale responses from replacing newer results. Keep timing in presentation or a meaningful domain operation, not Retrofit.
- Fetch images with an image loader from presentation. Data can expose a validated image URL or path, but must not depend on Compose or Coil.

## Persistence

The user chose Room-backed offline-first content and local favorites even though the PDF marks them optional. Keep previously loaded list/details/search content and favorites usable offline; Room is the single read source. Preserve favorites and unrelated cached content across refresh. Use DataStore only if a concrete small preference is needed. Do not add TMDB favorite sync, an outbox, auth refresh, or token storage without an actual requirement. See `android-offline-first` for cache keys, transactions, freshness, and offline failure semantics.

## UseCases

UseCases are optional. Call a domain repository contract directly from a ViewModel for a simple operation. Introduce a UseCase only for meaningful reusable business logic, multiple repository coordination, non-trivial transformation, or logic duplicated across ViewModels. Never add a one-to-one forwarding UseCase solely to complete a layer diagram.
