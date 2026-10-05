# Filmio

Android-only Kotlin TMDB assignment using Clean Architecture, MVI, Compose Material 3,
Retrofit/OkHttp/Moshi, Room, Paging 3, and Koin. The runnable list displays committed
movies in local alphabetical order, pages through TMDB popular movies, supports
Refresh/Retry and active reconnect, and keeps cached content usable offline. Selecting a
movie opens a restorable detail screen backed by the same persistent catalog. Inline
movie search reads cached matches immediately and requests TMDB after a fixed
500 ms quiet period.

## Setup

Use Android Studio with the Android 37 SDK and an emulator/device running API 29+.
The Gradle wrapper and daemon JVM criteria are checked in; JVM modules target Java 11.
Set your Android SDK path in ignored `local.properties` as usual.

1. Create root `tmdb.properties` locally with your TMDB **API Read Access Token**:
   `TMDB_READ_ACCESS_TOKEN=your-private-read-access-token`.
2. Build/install `:app`, or run `./gradlew :app:assembleDebug` and install the local APK.

Root `/tmdb.properties` is ignored by Git. A **nonblank** `TMDB_READ_ACCESS_TOKEN`
environment variable takes precedence over that file, for example from a private CI
secret. A blank environment value falls back to the file. The API key is unused:
it is neither read nor generated into BuildConfig nor sent. Authentication uses the
Authorization Bearer header; credentials never enter URL parameters.

Without a token, builds and startup still work. Stored movies remain readable;
refresh still makes an HTTP request, and a TMDB authorization failure becomes a
typed error. Configure the token and rebuild to enable refresh.

Ignored configuration protects source control. A token embedded in BuildConfig or
an APK **is extractable**. Keep token-bearing APKs, generated sources, Gradle caches,
build scans, and CI outputs private; do not upload them as public assignment
artifacts. Public distribution with a concealed application credential would need
a separately designed server-side boundary. No logging interceptor is installed;
do not print private properties, headers, tokens, or remote error bodies.

## Design and current scope

- `catalog:domain` exposes `Movie`, `CatalogRepository.getPagedMovies(): Flow<PagingData<Movie>>`,
  `searchMovies(query, fetchRemote)`,
  a safe `CatalogPagingException(DataError)` carrier, and a connectivity Flow contract.
  There are no artificial domain rules or forwarding UseCases.
- `catalog:data` builds one Pager/mediator per stream. Room supplies every displayed
  item. `MoviesRemoteMediator` starts at page 1, appends sequentially, and has
  no remote PREPEND. It validates response pages and advances its in-memory continuation
  only after atomic commit and cancellation checks. Retry repeats a failed page;
  Refresh restarts at 1. Empty raw results, reported last page, and page 500 stop remote loads.
- Local page/initial sizes are 20, placeholders are disabled, and prefetch distance is 1.
  Native Paging cache-boundary loading can fetch the next remote page as soon as a local
  load reaches the database end, including page 2 before scrolling on a small initial cache.
  Longer caches load remaining local batches first. There is no manual scroll paginator.
  All-null nonterminal batches explicitly invalidate the current source. An offset adapter
  preserves absolute Room refresh anchors when the presenter omits leading placeholders.
- `catalog:database` owns five Room v1 tables and persistent `filmio-catalog.db`.
  Refresh/append add or update summaries without deleting omitted movies, details, or
  favorites. Duplicate IDs use the last supplied values; null entries are skipped;
  a batch shares one clock timestamp. The schema and DAO SQL are unchanged.
- `core:data` owns shared Retrofit failure mapping. Catalog data maps expected SQLite
  write errors; cancellation and unexpected defects propagate. Presentation localizes
  the safe typed errors and uses generic storage feedback for unrecognized source failures.
- `catalog:presentation` caches the current query's stream in a cancellable ViewModel scope. Its state projects source
  and mediator loading/errors plus confirmed empty, without mirroring the movie list or
  fetching pages independently. Empty requires successful local and remote refresh outcomes.
  Refresh and Retry emit distinct commands through `ObserveAsEvents` to the active
  root's same Paging collection.
  Indexed rows use stable ID keys and remembered list state, retaining content during
  loading/failure, with append progress/error footers and no success confirmation.
- Search owns a `TextFieldState`, observes edits with `snapshotFlow`, preserves raw
  text in `SavedStateHandle`, and ignores
  whitespace-only request changes. Local reads start immediately; after 500 ms without
  a different trimmed query, `MovieSearchRemoteMediator` starts page 1 with `en-US`
  and `include_adult=false`. The fixed `.debounce(500.milliseconds)` lives on the ViewModel’s
  `searchFlow`; no timing constructor or DI parameter is needed. Empty input makes
  no search request.
- Search results combine literal local title/original-title matches with committed
  remote IDs, deduplicated and ordered by local title/ID. ASCII case is ignored;
  `%`, `_`, and GLOB pattern characters remain literal. Remote IDs and continuation
  live only in the active Pager. Commit precedes membership publication and source
  invalidation. Changing queries cancels the previous scope, and generation tokens
  reject stale load feedback and queued commands.
- The pinned search field keeps focus during typing. Local-to-remote switching keeps
  one query presenter and scroll state, identified by its cached Flow without a content
  wrapper/revision counter. The outer `StateFlow` selects that Flow on query changes;
  the inner Flow emits PagingData for the selected query. `isSearchActive` selects
  search UI, while `isDebouncing` only marks the 500 ms wait before remote paging. Navigation 3 retains the list ViewModel for details Back;
  `SavedStateHandle` restores editor text after process recreation. Clear builds
  a fresh Home Pager and restores its visible movie ID and pixel offset. Search commits
  remain in Home and preserve details/favorites. Retry, Refresh, and active reconnect
  target current content without bypassing debounce. Offline no-match feedback says
  no cached matches; online empty is confirmed only after successful remote exhaustion.
- Active-screen default-network observation refreshes on unavailable-to-available
  transitions. Initial/duplicate connected status is ignored. Event collection starts
  the ViewModel's observation Job and cancels it on completion. Unbuffered reconnect
  sends wait for a receiver and are canceled when collection stops, so startup order
  cannot drop a reconnect and stopped screens retain no pending reconnect command.
  Registration failure leaves manual recovery usable.
- Detail observation is local-only: absent movie, summary-only, and fetched-but-unknown
  metadata remain distinct. `fetchMovieDetails` returns only completion/error after a
  validated atomic snapshot commit. Selected null values and empty child lists replace
  previous metadata; zero and 64-bit monetary values are preserved. Details, the list,
  and favorites share one canonical movie.
- Every detail entry fetches the latest API data while showing any stored summary/details.
  Local observation and fetching proceed independently, and content updates only through
  Room. The injected clock supplies commit timestamps.
- A detail ViewModel prevents overlapping work and repeats entry fetching only for a new
  destination. Expected local-read errors use a safe domain exception and terminate
  observation. The last movie stays visible, and storage feedback takes precedence. Active reconnect restarts failed observation and fetches
  again; command success cannot clear a read error or provide direct network content.
  Detail state has one `isLoading` flag and one nullable `error: UiText?`. Back is its only action.
  Popping cancels owned work; reopening fetches again.
- `app` assembles persistent storage, private BuildConfig configuration, existing Koin
  modules, and a single-activity Navigation 3 host. Serializable list/detail keys save only
  movie identity. Entry decorators retain each ViewModel; Back reuses the list's Paging
  generation. No feature mapping or business logic lives here.

Relaunch keeps the stored catalog and starts a new remote sequence at 1; cache size
never determines a TMDB page number. Mapping preserves supplied whitespace, blanks,
nulls, and numeric zero. Missing token does not add a credential-presence gate.

The list uses flat poster-and-summary rows, ratings, and the green Filmio light/dark theme.
Missing or failed posters retain a neutral 2:3 placeholder. Details use a 16:9 backdrop and include ratings/votes and selected runtime,
release, genre, company, collection, and other metadata with unknown-value fallbacks.
Credits/review authors, series search/content-type selection, and local favorite UI/operations
remain assignment work. Background synchronization and list freshness are outside
this change. Stored image paths alone do not guarantee offline artwork.

## Verification

Automated testing uses local JVM tests only: small boundary fakes, MockWebServer,
virtual time, test-only Paging utilities, and Mockito-created SQLite exception
instances. Tests cover commit/retry/cancellation, terminal metadata, no-row pages,
local-before-remote paging, generation reuse, relative-anchor translation, safe
load-state projection, commands, and reconnect lifecycle. Detail tests additionally cover
strict decoding, complete mapping, unconditional fetching, expected read/write errors,
cache preservation, commit ordering, local-only observations, read recovery, immutable
IDs, loading/observer scheduling, reconnect recovery, Back, and entry cancellation. Shared network classification
is tested once in core data. Domain has no business rules requiring separate tests.
Search tests cover request encoding, bound literal-query construction, immediate local
reads, commit-before-membership, stale cancellation, debounce boundaries, restoration,
current-query recovery, and honest empty/offline state. Query/DAO fakes establish
coordination and construction only; they do not execute Android SQLite.

```sh
./gradlew :core:data:test :feature:catalog:data:testDebugUnitTest \
  :feature:catalog:presentation:testDebugUnitTest :feature:catalog:database:assembleDebug :app:assembleDebug
```

Fakes do not prove Room SQL/atomicity, durable reopening, or Compose behavior.
No automated emulator, screenshot, or mutation infrastructure is introduced.

Manual walkthrough with private local configuration:

1. Install/run online. Confirm titles and descriptions after commit, then scroll
   through successive local/remote batches. A small cache may eagerly fetch the next
   remote page before scrolling, following native Paging scheduling.
2. Disable connectivity at a subsequent boundary. Cached rows remain visible with
   append Retry; scrolling alone does not continuously retry. Restore connectivity
   or Retry a recoverable service failure. Append Retry repeats its failed page.
3. Tap Refresh while scrolled. Cached rows remain visible and the remote sequence
   restarts at 1. Check that stable IDs retain the viewport through alphabetical upserts.
4. At a known terminal response, keep scrolling: no further append request should occur.
   Refresh permits a new sequence. A controllable fixture is useful for finite responses.
5. Force-stop, disable connectivity, and relaunch without clearing storage. Browse
   previously cached rows and verify refresh failure/retry feedback. An uncached offline
   installation should show unavailable feedback, never a confirmed empty message.
6. Restore connectivity with the screen active: one page-1 reconnect refresh occurs.
   Stop/resume the same screen online: there is no extra independent initial request.
   Check callback release on stop and registration on resume. A successful empty
   response with empty local storage should show only the confirmed empty message.

7. Select a movie and confirm its ID, title, available ratings/votes, and extended fields.
   While refreshing or offline, previously stored content stays visible. Summary-only
   movies explicitly say extended details have not been fetched. Companies are production
   companies. Back returns to the retained list; reopen the movie to fetch again while displaying its cached details.
8. Confirm details expose Back without manual fetch controls, including during loading
   or errors. Active reconnect fetches once, while leaving and resuming online does not
   queue reconnect work. Recreate/background-kill the app with details open: restore the
   selected ID, observe stored content, and fetch the latest details for the new screen.
9. With seeded null/empty replacement and a favorite, verify detail-owned metadata clears
   appropriately, favorites and their added times survive, and updated canonical summaries
   appear on Back through Room invalidation. A subsequent popular write must preserve
   detail-owned fields and fetched time. Keep source-read failure feedback separate from
   fetch feedback; use the unit tests for controlled SQLite failures.

The performed search checks and their limits are recorded in
[inline movie search verification](docs/verification/inline-movie-search.md).
Do not uninstall/clear the normal app between cached offline steps. The exported
schema is preserved; there is no destructive migration fallback.

Endpoint/authentication references: [TMDB popular movies](https://developer.themoviedb.org/reference/movie-popular-list),
[TMDB application authentication](https://developer.themoviedb.org/docs/authentication-application),
[TMDB movie details](https://developer.themoviedb.org/reference/movie-details),
[TMDB movie search](https://developer.themoviedb.org/reference/search-movie), and
[Navigation 3 state and ViewModel scoping](https://developer.android.com/guide/navigation/navigation-3/save-state).
