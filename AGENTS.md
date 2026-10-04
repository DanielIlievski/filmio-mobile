# Filmio engineering guide

## Project overview and source of truth

Filmio is an Android-only Kotlin technical assignment using the TMDB API. The original **Android Technical Assignment-Senior.pdf** is the primary source of truth; `docs/Android Technical Assignment-Senior.md` is a convenience transcription. If this guide, a skill, or starter code conflicts with the PDF, follow the PDF and update this guide.

Required PDF flows: a Compose movie list with infinite scroll and each movie's title, image, and short description; a selected movie's detail screen with extended information such as ratings, votes, and author-related content where available; and search across movies or series after selecting the content type, with controllable request rate. The app uses one activity, handles network errors, and has unit tests for domain, data, and ViewModels. The PDF calls local favorites and offline mode optional; **this project explicitly includes both**. Previously loaded content and local favorites must remain usable offline. Delivery eventually needs a README with setup/design choices and several Git commits.

## Technology stack

These are intended choices for implementation, not claims that the application flows are complete. The repository contains the app and catalog/core modules, shared network error handling, and an initial catalog database schema. Catalog endpoints, repositories, and screens remain to be implemented.

| Technology | Purpose |
| --- | --- |
| Kotlin, Gradle | Application language and build/dependency management. |
| Jetpack Compose, Material 3, AndroidX Activity/Lifecycle/ViewModel | Declarative single-activity UI, lifecycle-aware state collection, and screen state ownership. |
| Coroutines and Flow | Asynchronous Retrofit calls, observable Room/UI state, and controllable/cancelable search input. |
| Retrofit, OkHttp, Moshi converter | TMDB API calls, HTTP configuration/authorization, and JSON-to-DTO conversion. Use one Retrofit converter. |
| Room and AndroidX Paging 3 | Durable catalog/favorites data; Room `PagingSource` and Retrofit-backed `RemoteMediator` support infinite scroll with the database as read source. |
| Koin | Small app-assembled bindings for networking, repositories, and ViewModels. |
| Jetpack Navigation 3 for Compose | App-level screen transitions using stable IDs/route arguments. |
| Coil | Poster/backdrop image loading in presentation. |
| JUnit, `kotlinx-coroutines-test`, MockWebServer | Domain/ViewModel unit tests and data-boundary HTTP tests. Compose UI Test covers selected user interactions. |

Room is selected for this project's offline-first behavior. DataStore may be added for a concrete small preference, not catalog/favorites/page-key storage. This is an Android-only project with Retrofit for HTTP; add other libraries only for concrete Filmio requirements.

## Architecture and modules

Use Clean Architecture, MVI, unidirectional data flow (UDF), and a proportionate multi-module structure. Keep dependencies directed inward and compose implementations at the app level. Build modules as functionality is implemented:

| Module | Responsibility |
| --- | --- |
| `:app` | Single activity, navigation host, application configuration, and Koin module assembly. No feature business logic or DTO mapping. |
| `:feature:catalog:domain` / `:data` / `:presentation` | One cohesive film/series catalog feature covering movie list, detail, search, and local favorites. Domain owns models/contracts; data owns TMDB, boundary mapping, Paging mediators, and repository implementations; presentation owns Compose screens and ViewModels. |
| `:feature:catalog:database` | Feature-specific Room storage leaf: entities, `CatalogDatabase`, DAOs, ordered local projections, Room `PagingSource` factories, atomic local writes, exported schemas, persistence tests, and future construction/migrations. No domain/data/presentation or networking dependency. |
| `:core:domain` | Pure-Kotlin shared `Result`, `Error`, and `DataError` contracts. Add other contracts only for genuine reuse. |
| `:core:data` | Kotlin/JVM Retrofit call result handling through `safeCall`. Catalog-specific services/configuration remain in catalog data; Room belongs to catalog database. |
| `:core:presentation` | Shared Android presentation utilities, beginning with `UiText` for dynamic and string-resource text. |
| `:core:designsystem` | Reusable theme/components only when actual reuse warrants them. |

Do not create one domain/data/presentation trio per screen: these flows share catalog identity, caching, and favorites. Do not create empty core modules or convention plugins for hypothetical reuse. Gradle includes `:app`, the catalog domain/data/database/presentation modules, `:core:domain`, `:core:data`, and `:core:presentation`. Catalog data depends on its domain, catalog database, and `:core:data`; the database dependency is one-way. Extract other shared behavior only after a concrete cross-feature need appears.

`UiText` lives in `:core:presentation` and supports dynamic strings and Android string resources with optional format arguments. Resolve it in Compose with `asString()` or from a suspending caller with `asStringAsync(context)`; the Android `Context` is explicit outside composition.

## Module dependency rules

- **Domain:** feature domain may use Kotlin, approved pure `:core:domain` contracts, and Paging's non-UI `PagingData` only when needed in a paged repository contract. It SHALL NOT depend on Android UI, networking, JSON/DTO types, Room entities/DAOs, or another feature's data/presentation implementation. Keep business models, repository interfaces, and meaningful business rules here.
- **Data:** feature data SHALL depend inward on its own feature domain. Catalog data owns Retrofit services/DTOs, DTO → entity → domain mapping, repositories, `Pager`/`RemoteMediator`, active request sessions, detail freshness, and error translation. It consumes catalog database's local records/APIs and approved `:core:data` infrastructure. Framework-specific data models SHALL NOT escape into domain or presentation.
- **Database:** catalog database owns Room/KSP configuration, entities, schemas, persistence tests, SQL/DAOs, ordered local projections, atomic local operations, and future construction/migrations. Data passes validated local records into those operations; it SHALL NOT own open-coded Room transactions. Database SHALL NOT depend on catalog domain/data/presentation, Retrofit, Moshi, or `:core:data`. Export Room runtime with `api` for the public database superclass, Paging common for public `PagingSource` queries, and coroutines core for public `Flow` queries.
- **Presentation:** feature presentation SHALL depend inward on its own feature domain and approved shared/core UI abstractions. It SHALL NOT import a feature data/database module or another feature's data/presentation implementation. It renders domain-facing data and sends work through domain contracts.
- **Cross-feature:** feature modules SHALL NOT directly depend on another feature's data or presentation implementation. Use app-level navigation/composition, an explicit public navigation API, or a genuinely shared domain contract when interaction is required. Do not prematurely extract shared abstractions or couple one feature domain to another merely to reuse a model.
- **App:** `:app` may depend on feature presentation entry points and data modules for DI assembly. Add a direct catalog database dependency when DI consumes its construction entry point. App wires implementations to contracts and owns navigation; no SQL or endpoint mapping belongs here. Keep Gradle dependencies explicit so direction is reviewable.

## MVI and UDF conventions

For a screen or coherent flow named `<Feature>`, presentation normally has `<Feature>Screen`, `<Feature>State`, `<Feature>Action`, `<Feature>Event`, and `<Feature>ViewModel`:

- `State` is immutable, durable/current UI state: loading, content, empty, selection, pagination, and recoverable failure/retry information as relevant.
- `Action` is a user/UI intent sent to the ViewModel, including retry and search-type changes.
- `Event` is a one-off effect, such as navigation or a transient message; do not store consumed effects in persistent state.
- `ViewModel` owns state transitions, calls feature-domain contracts, and emits events.
- `Screen` renders state and forwards actions. A small root composable may collect state/events with lifecycle awareness and connect navigation callbacks.

State flows **ViewModel → UI**; actions flow **UI → ViewModel**. Pass stable media type and ID between destinations, not DTOs or full response objects. Preserve essential route/query inputs when restoration matters. Let Paging 3 coordinate page loads and retries; do not add a parallel manual paginator. Prevent stale search responses. Choose and document the search debounce/throttle semantics and make the rate controllable. Avoid generic MVI frameworks, base ViewModels, and reducers until repetition justifies them.

## UseCase rules

UseCases are optional. A ViewModel may call its feature-domain repository contract directly for a simple operation. Do not add `GetMoviesUseCase -> repository.getMovies()` or another one-call forwarding wrapper. Add a UseCase only for meaningful business rules, coordination across repositories/operations, business transformations, or duplicated logic across ViewModels.

## Networking and error handling

Follow `.agents/skills/android-data-layer/SKILL.md`, `.agents/skills/android-error-handling/SKILL.md`, and `.agents/skills/android-offline-first/SKILL.md`. Use typed `suspend` Retrofit services in feature data, a shared OkHttp client where useful, and Moshi for DTO conversion. Supply TMDB credentials through non-committed configuration; never hardcode or log them. Map DTOs to Room entities, then domain models at the data boundary. Repositories read canonical data from Room; network refresh writes Room. The paged list uses a Room `PagingSource` and Retrofit-backed `RemoteMediator` with in-memory session continuation and transactional summary-page updates. Load details by stable ID, cache their extended fields, and model absent fields safely. Keep local favorites in separate durable state so refresh cannot erase them. Home displays all stored movies from popular/search requests as one catalog. Search immediately reads local title/original-title matches for the selected media type, including queries never requested remotely; online requests enrich the active results after committing movies to Room. Display the deduplicated union of local matches and active committed remote IDs. Local reads do not wait for the remote debounce. Offline no-match state means no cached matches, not proof that TMDB has no matches. This user-selected policy supersedes exact-query snapshot requirements in the adapted data/offline skills.

`:core:data` currently provides `safeCall` for a typed `suspend` Retrofit invocation. It returns `Result<T, DataError.Network>` using the shared `:core:domain` contract, classifies HTTP, transport, timeout, Moshi decoding failures, and Retrofit's null-body failure, and propagates cancellation and unexpected defects. Catalog endpoints and repositories have not been implemented yet. When future catalog list, detail, and search callers use this boundary, write fetched data to Room only after `Result.Success`; on `Result.Error`, keep cached rows and prior transactions intact and pass the typed failure to Paging or screen retry state. The wrapper itself does not retry or write to Room.

`CatalogDatabase` version 1 exports exactly five tables under `feature/catalog/database/schemas/`: canonical `movies`, optional shared-PK `movie_details`, detail-owned ordered `movie_genres` and `movie_production_companies`, and independent `movie_favorites`. Detail existence marks fetched availability; nullable metadata remains unknown. Monetary values use `Long`, collection ID/name are flattened, and no converters are needed. Detail deletion cascades only owned lists; favorite references restrict canonical deletion. Parent writes use non-destructive upserts rather than `INSERT OR REPLACE`. Popular/search refresh adds or updates summaries and preserves older movies, details, and favorites, including on an empty response. No result-set/membership tables, durable page keys, per-movie source/order/page fields, or request-history snapshots exist.

This is unshipped movie-only storage with production DAOs and instrumented SQL/DAO tests against actual Room. Construction/DI binding, endpoint DTO/domain mappers, repositories, mediator, domain favorite operations, and UI integration do not exist yet. Its initial version-1 export has been revised in place before delivery; released schema changes must increment the version and use preserving migrations. Future callers use fixed `en-US` and search `include_adult=false`; multiple content locales require separate localized storage. The selected schema omits summary genre IDs, global genre/company lookup tables, country/spoken-language arrays, collection/company artwork, popularity/adult/video flags, response totals, and raw JSON. Series and credits/review-author payloads require later work; companies are not authors. Stored image paths do not guarantee offline image bytes.

`CatalogDatabase` exposes three DAOs organized by operation ownership. `MovieDao` provides deterministic catalog/search `PagingSource` factories, a nullable one-shot summary read, and atomic additive page upserts. Blank search input returns no rows; nonblank search unions literal local matches with committed active IDs. Search text is bound, while only typed `Long` IDs enter a numeric SQL list to support sessions beyond SQLite's older bind limit. `MovieDetailDao` owns transactional snapshot reads/observation and full snapshot replacement across summaries, details, genres, and companies. Its nullable snapshot distinguishes missing movies from summary-only movies with null detail; it sorts owned metadata by stored position. Child writes stay protected inside this DAO, and mismatched owners or constraint failures reject the update. `MovieFavoriteDao` provides paged canonical summaries with added time, observable status, and idempotent add/remove. Favorites sort by added time descending then movie ID ascending; repeated adds preserve the original time, and re-adding after removal records the new time. No generic cache-deletion API or independent child-table DAO is exposed. DAO failures and cancellation propagate; feature data owns recovery/error translation.

Follow the integration handoff in `feature/catalog/database/README.md` and the change design. Data owns active query/type/options, committed remote IDs, and next-page/terminal state in memory; process restart keeps cached content while new remote pagination starts at page 1. An active API-returned movie can be included even if local title matching differs. Query/type changes clear prior remote IDs and obsolete requests cannot publish IDs, errors, or continuation into the new session. Successful local commit precedes ID publication/key advancement; failures retain the prior cache and session retry key. Later Room queries must rebuild/invalidate when active ID parameters change. Database owns local queries and atomic summary/detail writes; networking stays outside transactions. Paging still coordinates load/retry without a parallel manual paginator.

Initial local matching uses literal title/original-title substrings after trimming, ignores ASCII letter case, and preserves other characters literally; percent/underscore are not wildcards. The database builds a bound GLOB pattern with explicit ASCII letter pairs and escaped pattern characters because Android SQLite `lower()` may fold non-ASCII characters. Local results use deterministic `title COLLATE NOCASE ASC, id ASC` ordering, without a TMDB rank/order promise. Empty input issues no remote request. Online search uses controllable debounce independent of immediate local reads; reconnect refreshes the active catalog/query with content still visible, without historical-query refetch or background synchronization. Remove the old one-hour result-set freshness policy. Detail freshness remains a tunable 24-hour policy with an injected clock; future-dated timestamps are stale and expiration does not delete content. Pagination ends on empty raw results, reported last page, or accessible page 500 and rejects mismatched response pages; cached row/match counts do not decide completion. Remote/session operational contracts still need integration tests; schema fixtures and DAO tests establish local storage, query, observation, and atomic-write behavior. Never use routine destructive fallback for offline data.

Translate HTTP, connectivity/timeout, rate-limit, and parsing failures centrally at the data boundary into a small domain-meaningful error vocabulary where useful. No `HttpException`, Retrofit `Response`, raw exception text, DTO, or HTTP status handling in presentation. Rethrow coroutine cancellation. Keep diagnostic logs free of credentials. UI state must distinguish uncached offline/unavailable, true empty, loading, cached/stale content, and refresh/append failure. Paging failures must keep cached rows visible and offer retry. Details and search should also offer meaningful retry. Use state for recoverable failures and events only for transient feedback.

## Testing strategy

- **Domain:** plain JVM tests for actual business rules and contracts with behavior; do not invent logic solely to create tests.
- **Data:** test DTO/entity/domain mapping, active session continuation, additive Paging `RemoteMediator` refresh/append/end/retry, cache-first local/remote search union, query/type switching, reconnect, offline completeness, and detail caching. Cover representative HTTP, timeout, malformed-response, and cancellation paths with MockWebServer where the Retrofit boundary matters. Data integration tests may consume the real database module.
- **Database:** use instrumented real Room tests for FK/uniqueness failures, atomic local transactions, protected favorites, shared-catalog deduplication, local title matching/active-ID union fixtures, and file-backed offline reopen. Persistence tests live in catalog database. Export schemas and test future migrations that must preserve favorites/cached data. Version-1 fixtures do not prove remote/Paging or UI behavior.
- **ViewModels:** inject fake domain repositories and use coroutine virtual time to verify loading/content/empty/offline/failure states, Paging load-state handling and retry, detail load, favorite actions, navigation events, movie/series selection, search rate control, cancellation, and stale-result protection.
- **Compose:** a small set of behavior-focused UI tests for critical interactions and visible states. These supplement, rather than replace, the PDF-required domain/data/ViewModel unit tests.

Test externally meaningful behavior, not trivial getters or private implementation details. Add test utilities only when they remove real repetition.

## Naming and code conventions

Use `com.example.filmio` as the current package root until a deliberate rename. Name modules `:feature:<cohesive-feature>:<layer>` and packages to match (`feature.catalog.domain`, `feature.catalog.data`, etc.). Use screen-specific names such as `MovieListState`, `MovieDetailAction`, and `SearchEvent` where `<Feature>` would be ambiguous. Put repository interfaces in domain and descriptive offline-first implementations in data. Suffix transport models `Dto` and Room models `Entity`; domain models have neither suffix nor framework annotations. Use media type plus TMDB ID where movie/series identities may overlap. Prefer clear Kotlin names, immutable state, sealed actions/events where useful, and English for code, comments, and project documentation.

## Dependencies, scope, and simplicity

Add dependencies only for a concrete assignment requirement or implemented feature. PDF-mandated technology takes precedence; otherwise prefer focused AndroidX/Kotlin ecosystem choices and avoid overlapping libraries for the same job. This is a technical assignment: favor clarity over cleverness, simple abstractions over generalized frameworks, and adequate performance/resource handling without speculative infrastructure. Avoid unnecessary base classes, wrapper layers, one-line UseCases, and enterprise-scale scaffolding.

## Maintaining this guide

`AGENTS.md` is a living architectural document. Coding agents SHALL update it when they introduce or materially change architecture, module boundaries, major dependency choices, architectural conventions, cross-cutting patterns, testing or error-handling strategy, or networking/persistence conventions. Minor implementation details do not need an update. Recheck changes against the assignment PDF and the adapted skills; do not change assignment requirements to fit the starter project.
