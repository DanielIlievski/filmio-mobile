# Filmio engineering guide

## Scope and source of truth

Filmio is an Android-only TMDB assignment. The original **Android Technical Assignment-Senior.pdf** governs the requirements; the checked-in [transcription](docs/Android%20Technical%20Assignment-Senior.md) is a convenience copy. Do not redefine requirements to match implemented features.

The project explicitly includes offline content and local favorites, although the assignment makes them optional. Movie list, details, inline movie search, and Saved are implemented. Series/content-type selection, author-related content, and controllable search timing remain assignment work; the current remote debounce is fixed at 500 ms. Production companies are not authors.

See [README.md](README.md) for setup and checks, and the [database README](feature/catalog/database/README.md) for storage contracts. Keep these documents focused on durable decisions; update them when architecture or behavior changes, not as a feature log.

## Module boundaries

List, details, search, and favorites form one catalog feature because they share identity and storage. Do not split layers per screen or add speculative core modules, convention plugins, or generic MVI base classes.

| Module | Ownership and dependencies |
| --- | --- |
| `:app` | Single activity, Navigation 3 host, configuration, and Koin assembly. Consumes feature entry points and data/database construction; no feature logic, SQL, or DTO mapping. |
| `:feature:catalog:domain` | Models and repository/connectivity/error contracts. Kotlin/JVM with `:core:domain`, coroutines, and non-UI Paging contracts; no Android UI, Retrofit, DTOs, or Room. |
| `:feature:catalog:data` | TMDB services, DTO → local record → domain mapping, repositories, Pagers/mediators, request sessions, and SQLite error translation. Depends on catalog domain/database and `:core:data`. |
| `:feature:catalog:database` | Room entities/schema, SQL, ordered projections, source factories, construction, and atomic operations. No catalog domain/data/presentation or networking dependency. |
| `:feature:catalog:presentation` | Screens, MVI state/actions/events, and ViewModels. Depends on catalog domain and shared presentation utilities; no data/database imports. |
| `:core:domain` | Shared `Result`, `Error`, and `DataError` contracts. |
| `:core:data` | Kotlin/JVM Retrofit `safeCall`; no Android SQLite handling. |
| `:core:presentation` | `UiText`, lifecycle event collection, and shared Filmio palette/typography. Dynamic color is opt-in. |

Keep dependencies explicit and directed inward. Cross-feature interaction belongs in app navigation/composition or a justified public contract, never another feature's data/presentation implementation. ViewModels may call domain repositories directly; add UseCases for actual coordination or business rules, not one-call forwarding.

## Room as the source of truth

- All displayed content comes from committed Room reads. Network commands return completion/error and write through DAO-owned atomic operations; never render their DTOs directly or run HTTP inside a transaction.
- All/Home is the additive catalog of movies fetched through popular **and search** requests, ordered locally by title/ID rather than TMDB rank. Refresh and empty responses preserve older summaries, details, and favorites. Do not introduce eviction or destructive migration for promised offline content.
- Remote membership and continuation live in the active Pager, not persistent result sets, query history, or page keys. Process restart retains content but starts remote pagination at page 1. Cache size cannot determine a remote page.
- Detail observation is local-only. In its full projection, null movie means absent, null details means summary-only, and a present detail payload means fetched even with unknown fields. List projections intentionally omit details and cannot establish fetched availability.
- Each new detail destination fetches the latest API data while local content remains visible. Full snapshots replace selected nulls/empty lists; later summary writes preserve detail-owned metadata. Commit timestamps come from the injected clock; there is no TTL or background synchronization.
- Favorites are independent installation-local metadata. Saving requires a cached summary and makes no TMDB request; unsaving preserves content. Room membership drives controls and Saved rows. Saving does not fetch extended details or guarantee offline artwork; Coil byte caching is best effort.

## Paging and search invariants

Paging owns loading and retry; do not add a manual paginator. Native cache-boundary scheduling may fetch another remote page before scrolling when the local cache is small. Preserve `AnchoredMoviePagingSource`: it translates presenter-relative anchors to Room's absolute offsets with placeholders disabled.

For popular and remote search loads:

- Refresh requests page 1; append is sequential; no remote PREPEND. Reject mismatched response pages.
- Validate and commit before publishing search IDs or advancing continuation. Failure retains the retry page/membership. Canceled or superseded work cannot publish obsolete session state; already committed canonical rows are safe to reload.
- End on empty **raw** results, reported last page, or page 500. Mapped/new/local row counts cannot establish completion. Nonterminal all-null batches must invalidate the source so pagination continues without a table write.
- Search refresh replaces committed IDs; append extends them. Publish membership before explicitly invalidating the source so its replacement captures the new IDs, including duplicate-only or null-only responses.

All search immediately reads trimmed literal title/original-title matches, including queries never sent online. Remote search adds committed active IDs; the visible result is their deduplicated union in local order. This policy takes precedence over skill guidance requiring exact-query snapshots. Offline no-match feedback means **no cached matches**, not no TMDB matches.

The ViewModel owns one `TextFieldState`; `snapshotFlow` persists raw text and derives trimmed request identity. Local reads precede the fixed 500 ms quiet period; whitespace-only identity changes do not restart it. Empty input makes no search request. Retry/Refresh/reconnect cannot bypass debounce. Saved search applies the same local matcher before paging, retains saved-time/ID order, and has no HTTP, remote membership, debounce, or Refresh control.

Blank All/Saved streams are retained with `cachedIn(viewModelScope)`. Searches use a cancelable query scope because `cachedIn` keeps collecting without a UI receiver. Query/scope changes cancel obsolete work; generation tokens reject old load feedback and queued commands, including A → B → A. Keep one presenter across local-to-remote switching and preserve search-field focus. Clearing search restores the selected collection's movie-ID/pixel anchor through normal Paging access hints.

## Presentation, navigation, and DI

Keep Screen, State, Action, Event, and ViewModel in separate files. Use private `_state: MutableStateFlow` and public `StateFlow`; read-modify-write uses `update` with side effects outside its reevaluable lambda. Navigation and Paging commands use private `eventChannel`/`receiveAsFlow`; recoverable failures belong in state. Do not mirror Paging rows, page counters, DAO records, or retry keys into screen state.

Roots collect state/load feedback with lifecycle awareness and handle commands through `ObserveAsEvents` on the current Paging collection. Initial subscription work uses `onStart`, `hasLoadedInitialData`, and `stateIn(WhileSubscribed(5_000L))` so recollection does not repeat entry requests. Detail local observers belong to the state subscription; the fetch collector belongs to `viewModelScope` and `collectLatest` cancels superseded requests.

Connectivity observation belongs to active event collection (`onStart`/`onCompletion`). Refresh only on unavailable → available transitions, ignoring initial/duplicate availability; stopping collection releases callbacks and pending reconnect delivery. Saved reconnect makes no request. Detail reconnect retries the fetch, not a failed local observer; recollection restarts local reads. Registration failure leaves local content and explicit list recovery available.

App assembles Koin modules, singleton database/DAOs, TMDB configuration, repository, clock, and connectivity observer. Detail ViewModels receive route IDs as Koin parameters. Navigation 3 uses serializable keys plus saveable-state and ViewModel-store entry decorators. Routes carry identity, never full movie payloads; Back retains the list ViewModel/Paging generation. `SavedStateHandle` restores raw query and All/Saved selection. Future movie/series routes and storage must distinguish media type because TMDB IDs overlap.

## Networking and failures

Use the existing Retrofit/OkHttp/Moshi boundary with one converter. Movie calls use `en-US`; search uses `include_adult=false`. Supporting multiple content locales requires localized storage design. Preserve supplied nulls, blanks, zero, and 64-bit money rather than inventing defaults in mapping.

`safeCall` centrally classifies HTTP, transport, timeout, decoding, and Retrofit null-body failures without retries or writes. Catalog data maps SQLite-full to `DISK_FULL` and other expected SQLite failures to local `UNKNOWN`. Cancellation and unexpected defects propagate. Keep DTOs, framework exceptions, HTTP status handling, and raw remote error text out of presentation.

Paging carries typed failures through `CatalogPagingException`; local observation failures terminate with `CatalogStorageException` and recover through a new subscription. Retain last content, give source/storage errors precedence, and do not let fetch success clear an unresolved read failure. Distinguish loading, unavailable uncached content, cached content with refresh/append failure, and confirmed empty. All search empty requires successful remote exhaustion; Saved empty requires only successful local loading.

Credentials come from private build configuration described in the root README. Never log or commit tokens, authorization headers, or credential-bearing generated artifacts.

## Testing constraints

Automated coverage is **local JVM unit tests only**, a project decision that supersedes instrumented-test suggestions in skills. Do not add emulator, screenshot, mutation, or large testing infrastructure without a new user request.

- Test repository commit/retry/cancellation and cache preservation with small fakes; use MockWebServer for request/DTO boundaries. Shared network classification belongs in core data tests.
- Test ViewModel transitions, stale-generation rejection, debounce/restoration, and reconnect with fake domain contracts, virtual time, and test-only Paging utilities. Test domain business rules when present; do not invent UseCases or getter tests to fill a category.
- Fake DAOs do not verify Room SQL, transaction atomicity, durable reopening, migrations, or Compose behavior. Review persistence changes against the exported schema and record relevant manual checks separately from JVM evidence.
