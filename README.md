# Filmio

Android-only Kotlin TMDB assignment using Clean Architecture, MVI, Compose Material 3,
Retrofit/OkHttp/Moshi, Room, Paging 3, and Koin. The runnable list displays committed
movies in local alphabetical order, pages through TMDB popular movies, supports
Refresh/Retry and active reconnect, and keeps cached content usable offline.

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
  a safe `CatalogPagingException(DataError)` carrier, and a connectivity Flow contract.
  There are no artificial domain rules or forwarding UseCases.
- `catalog:data` builds one Pager/mediator per stream. Room supplies every displayed
  item. `PopularMoviesRemoteMediator` starts at page 1, appends sequentially, and has
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
- `catalog:presentation` caches one stream in the ViewModel. Its state projects source
  and mediator loading/errors plus confirmed empty, without mirroring the movie list or
  fetching pages independently. Empty requires successful local and remote refresh outcomes.
  Refresh and Retry emit distinct commands through `ObserveAsEvents` to the active
  root's same Paging collection.
  Indexed rows use stable ID keys and remembered list state, retaining content during
  loading/failure, with append progress/error footers and no success confirmation.
- Active-screen default-network observation refreshes on unavailable-to-available
  transitions. Initial/duplicate connected status is ignored. Event collection starts
  the ViewModel's observation Job and cancels it on completion. Unbuffered reconnect
  sends wait for a receiver and are canceled when collection stops, so startup order
  cannot drop a reconnect and stopped screens retain no pending reconnect command.
  Registration failure leaves manual recovery usable.
- `app` assembles persistent storage, private BuildConfig configuration, existing Koin
  modules, and the single activity. No feature mapping or business logic lives here.

Relaunch keeps the stored catalog and starts a new remote sequence at 1; cache size
never determines a TMDB page number. Mapping preserves supplied whitespace, blanks,
nulls, and numeric zero. Missing token does not add a credential-presence gate.

The screen still uses text summaries. Posters, details with extended/author content,
movie/series search with controllable request rate, and local favorite UI/operations
remain assignment work. Background synchronization and list freshness are outside
this change. Stored image paths alone do not guarantee offline artwork.

## Verification

Automated testing uses local JVM tests only: small boundary fakes, MockWebServer,
virtual time, test-only Paging utilities, and Mockito-created SQLite exception
instances. Tests cover commit/retry/cancellation, terminal metadata, no-row pages,
local-before-remote paging, generation reuse, relative-anchor translation, safe
load-state projection, commands, and reconnect lifecycle. Shared network classification
is tested once in core data. Domain has no business rules requiring separate tests.

```sh
./gradlew :core:data:test :feature:catalog:data:testDebugUnitTest \
  :feature:catalog:presentation:testDebugUnitTest :app:assembleDebug
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

Do not uninstall/clear the normal app between cached offline steps. The exported
schema is preserved; there is no destructive migration fallback.

Endpoint/authentication references: [TMDB popular movies](https://developer.themoviedb.org/reference/movie-popular-list),
[TMDB application authentication](https://developer.themoviedb.org/docs/authentication-application).
