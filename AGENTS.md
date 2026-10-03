# Filmio engineering guide

## Project overview and source of truth

Filmio is an Android-only Kotlin technical assignment using the TMDB API. The original **Android Technical Assignment-Senior.pdf** is the primary source of truth; `docs/Android Technical Assignment-Senior.md` is a convenience transcription. If this guide, a skill, or starter code conflicts with the PDF, follow the PDF and update this guide.

Required PDF flows: a Compose movie list with infinite scroll and each movie's title, image, and short description; a selected movie's detail screen with extended information such as ratings, votes, and author-related content where available; and search across movies or series after selecting the content type, with controllable request rate. The app uses one activity, handles network errors, and has unit tests for domain, data, and ViewModels. The PDF calls local favorites and offline mode optional; **this project explicitly includes both**. Previously loaded content and local favorites must remain usable offline. Delivery eventually needs a README with setup/design choices and several Git commits.

## Technology stack

These are intended choices for implementation, not claims that their dependencies are already configured. The repository currently contains only a starter `:app` module.

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

Room is selected for this project's offline-first behavior. DataStore may be added for a concrete small preference, not catalog/favorites/page-key storage. Do not carry over Ktor, Kotlin Multiplatform/iOS, or unrelated starter libraries.

## Architecture and modules

Use Clean Architecture, MVI, unidirectional data flow (UDF), and a proportionate multi-module structure. Chirp is a reference for inward dependencies and app-level composition, not a template to copy. Build modules as functionality is implemented:

| Module | Responsibility |
| --- | --- |
| `:app` | Single activity, navigation host, application configuration, and Koin module assembly. No feature business logic or DTO mapping. |
| `:feature:catalog:domain` / `:data` / `:presentation` | One cohesive film/series catalog feature covering movie list, detail, search, and local favorites. Domain owns models/contracts; data owns TMDB, Room, Paging mediators, and repository implementations; presentation owns Compose screens and ViewModels. |
| `:core:domain` | Pure-Kotlin shared `Result`, `Error`, and `DataError` contracts. Add other contracts only for genuine reuse. |
| `:core:data` | Shared data infrastructure only if genuine reuse later appears; catalog-specific Retrofit and Room setup can remain in catalog data. |
| `:core:presentation` / `:core:designsystem` | Shared UI utilities or reusable theme/components only when actual reuse warrants them. |

Do not create one domain/data/presentation trio per screen: these flows share catalog identity, caching, and favorites. Do not create empty core modules or convention plugins for hypothetical reuse. Gradle currently includes `:app`, the catalog layer modules, and `:core:domain`; add further modules only as implementation needs them. Shared behavior should be extracted only after a concrete cross-feature need appears.

## Module dependency rules

- **Domain:** feature domain may use Kotlin, approved pure `:core:domain` contracts, and Paging's non-UI `PagingData` only when needed in a paged repository contract. It SHALL NOT depend on Android UI, networking, JSON/DTO types, Room entities/DAOs, or another feature's data/presentation implementation. Keep business models, repository interfaces, and meaningful business rules here.
- **Data:** feature data SHALL depend inward on its own feature domain. It may contain Retrofit services, DTOs, remote/local data sources, repository implementations, entities/DAOs if storage is added, and mapping. It may use approved `:core:data` infrastructure. Framework-specific data models SHALL NOT escape into domain or presentation.
- **Presentation:** feature presentation SHALL depend inward on its own feature domain and approved shared/core UI abstractions. It SHALL NOT import a feature data module or another feature's data/presentation implementation. It renders domain-facing data and sends work through domain contracts.
- **Cross-feature:** feature modules SHALL NOT directly depend on another feature's data or presentation implementation. Use app-level navigation/composition, an explicit public navigation API, or a genuinely shared domain contract when interaction is required. Do not prematurely extract shared abstractions or couple one feature domain to another merely to reuse a model.
- **App:** `:app` may depend on feature presentation entry points and data modules for DI assembly. It wires implementations to contracts and owns navigation; it does not become a data or business layer. Keep Gradle dependencies explicit so direction is reviewable.

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

Follow `.agents/skills/android-data-layer/SKILL.md`, `.agents/skills/android-error-handling/SKILL.md`, and `.agents/skills/android-offline-first/SKILL.md`. Use typed `suspend` Retrofit services in feature data, a shared OkHttp client where useful, and Moshi for DTO conversion. Supply TMDB credentials through non-committed configuration; never hardcode or log them. Map DTOs to Room entities, then domain models at the data boundary. Repositories read canonical data from Room; network refresh writes Room. The paged list uses a Room `PagingSource` and Retrofit-backed `RemoteMediator` with scoped remote keys and transactional page updates. Load details by stable ID, cache their extended fields, and model absent fields safely. Keep local favorites in separate durable state so refresh cannot erase them. Cached search results are scoped by query and media type; unseen searches cannot be completed offline.

Translate HTTP, connectivity/timeout, rate-limit, and parsing failures centrally at the data boundary into a small domain-meaningful error vocabulary where useful. No `HttpException`, Retrofit `Response`, raw exception text, DTO, or HTTP status handling in presentation. Rethrow coroutine cancellation. Keep diagnostic logs free of credentials. UI state must distinguish uncached offline/unavailable, true empty, loading, cached/stale content, and refresh/append failure. Paging failures must keep cached rows visible and offer retry. Details and search should also offer meaningful retry. Use state for recoverable failures and events only for transient feedback.

## Testing strategy

- **Domain:** plain JVM tests for actual business rules and contracts with behavior; do not invent logic solely to create tests.
- **Data:** test DTO/entity/domain mapping, Room transactions, favorites surviving refresh, scoped page/search keys, Paging `RemoteMediator` refresh/append/end/retry, offline relaunch, uncached offline state, and detail caching. Cover representative HTTP, timeout, malformed-response, and cancellation paths with MockWebServer where the Retrofit boundary matters. Export Room schemas and test migrations that must preserve favorites/cached data.
- **ViewModels:** inject fake domain repositories and use coroutine virtual time to verify loading/content/empty/offline/failure states, Paging load-state handling and retry, detail load, favorite actions, navigation events, movie/series selection, search rate control, cancellation, and stale-result protection.
- **Compose:** a small set of behavior-focused UI tests for critical interactions and visible states. These supplement, rather than replace, the PDF-required domain/data/ViewModel unit tests.

Test externally meaningful behavior, not trivial getters or private implementation details. Add test utilities only when they remove real repetition.

## Naming and code conventions

Use `com.example.filmio` as the current package root until a deliberate rename. Name modules `:feature:<cohesive-feature>:<layer>` and packages to match (`feature.catalog.domain`, `feature.catalog.data`, etc.). Use screen-specific names such as `MovieListState`, `MovieDetailAction`, and `SearchEvent` where `<Feature>` would be ambiguous. Put repository interfaces in domain and descriptive offline-first implementations in data. Suffix transport models `Dto` and Room models `Entity`; domain models have neither suffix nor framework annotations. Use media type plus TMDB ID where movie/series identities may overlap. Prefer clear Kotlin names, immutable state, sealed actions/events where useful, and English for code, comments, and project documentation.

## Dependencies, scope, and simplicity

Add dependencies only for a concrete assignment requirement or implemented feature. PDF-mandated technology takes precedence; otherwise prefer focused AndroidX/Kotlin ecosystem choices and avoid overlapping libraries for the same job. This is a technical assignment: favor clarity over cleverness, simple abstractions over generalized frameworks, and adequate performance/resource handling without speculative infrastructure. Avoid unnecessary base classes, wrapper layers, one-line UseCases, and enterprise-scale scaffolding.

## Maintaining this guide

`AGENTS.md` is a living architectural document. Coding agents SHALL update it when they introduce or materially change architecture, module boundaries, major dependency choices, architectural conventions, cross-cutting patterns, testing or error-handling strategy, or networking/persistence conventions. Minor implementation details do not need an update. Recheck changes against the assignment PDF and the adapted skills; do not change assignment requirements to fit the starter project.
