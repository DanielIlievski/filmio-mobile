# Filmio

Android-only Kotlin TMDB assignment using Clean Architecture, MVI, Compose Material 3,
Retrofit/OkHttp/Moshi, Room, and Koin. The current runnable slice loads **page 1** of
popular movies and displays the entire stored catalog in local alphabetical order.
It shows titles and descriptions, supports explicit Refresh/Retry, and preserves
cached content during failures and after process restart.

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

- `catalog:domain` exposes `Movie` and `CatalogRepository`.
  `fetchMovies(page): EmptyResult<DataError>` returns commit success or a
  typed network/local write error. `getMovies(): Flow<List<Movie>>`
  reads committed local rows.
  There are no artificial domain rules or forwarding UseCases to unit-test here.
- `catalog:data` owns the one Retrofit service, DTO decoding/mapping, the network
  stack, and `OfflineFirstCatalogRepository`. Refresh concurrency is guarded by
  the ViewModel. The repository forwards the requested page (nonpositive values use
  page 1), rejects mismatched response pages, skips null entries, and maps summaries
  into an atomic additive DAO upsert. Each batch shares one timestamp. Empty responses
  retain content; duplicate IDs use the last occurrence.
- `core:data` remains Kotlin/JVM and owns `safeCall`. Android SQLite write-error
  translation belongs to catalog data's internal `safeDatabaseUpdate` helper.
- `catalog:database` owns the five-table Room v1 schema, queries/transactions, and
  `createCatalogDatabase(context)` using application context and `filmio-catalog.db`.
  Summary refreshes preserve omitted movies, details, owned metadata, and favorites.
- `catalog:presentation` starts observation and one initial refresh when state is
  first collected. State contains movies, one nullable error, and one loading flag;
  `_state.value.isLoading` guards repeated refresh inputs. Cached titles remain visible.
  Refresh/Retry request page 1. UI items come only from Room observation, and successful
  refresh produces no confirmation. Local-read failure handling/recovery is deferred.
- `app` assembles the persistent database/DAO, BuildConfig configuration, feature Koin
  modules, Application startup, and the temporary screen under the existing theme.

Mapping preserves DTO values directly, including whitespace, nulls, blanks, and
numeric zero. Moshi rejects malformed JSON or missing required fields; a response
page must match the requested page before any write. Expected HTTP/transport/decoding
failures reuse `safeCall`. Catalog data maps Android `SQLiteFullException` to
`DataError.Local.DISK_FULL` and other `SQLiteException`s to `DataError.Local.UNKNOWN`.
Fetch returns these write errors so presentation can show localized retry feedback
while preserving content. Local read failures and unexpected defects still propagate;
cancellation remains cancellation.
The repository supports later pages; the current screen explicitly requests page 1
until Paging integration connects scrolling to page loads.
An empty read is successful local data, not a read error. With no loading/error, an
empty list shows no stored movies; a typed refresh failure shows retry feedback instead.
State resubscription restarts observation without repeating the initial request,
and ViewModel clearing cancels both operations.

This temporary screen is a single destination with text summaries. Final assignment
work remains: infinite scroll using Room/Paging/RemoteMediator, posters, details with
extended/author content, movie/series search with controllable request rate, and local
favorite UI/operations. Automatic reconnect, background sync, and list freshness are
also deferred. The selected eventual Room/search/offline contract still applies;
this slice does not weaken it. Image paths alone do not guarantee offline artwork.

## Verification

Automated testing is limited to focused local JVM unit tests. They use small fake
DAOs/repositories and MockWebServer, plus test-only Mockito mocks for Android SQLite
exception instances. They require no device or live TMDB token and cover
network error mapping, catalog requests/decoding, repository coordination, and
ViewModel states/retry/cancellation. Shared network cases are tested once in core
data. Domain currently has no business rules requiring separate tests.

```sh
./gradlew :core:data:test :feature:catalog:data:testDebugUnitTest \
  :feature:catalog:presentation:testDebugUnitTest :app:assembleDebug
```

These unit tests exercise our code at its boundaries; they do not verify real Room
SQL/persistence or Compose interactions. Use the walkthrough below to check the app.
The ViewModel suite still includes expectations for deferred local-read recovery and
the earlier loading/cancellation behavior; the full suite currently needs alignment.

Manual walkthrough with private local configuration:

1. Install/run online. Wait for initial loading to finish and verify stored titles
   and descriptions appear after the page-1 batch commits.
2. Tap Refresh. Titles remain visible during the attempt. Scroll through the list;
   this slice makes no request for page 2 and uses the same page-1 scope on refresh.
3. Force-stop the app, disable connectivity, and relaunch. Previously stored titles
   remain readable while the new initial refresh fails with retry feedback.
4. Tap Retry offline. Cached content remains unchanged after failure.
5. Restore connectivity while the screen stays open. Reconnection alone triggers no
   request; tap Retry explicitly to recover. Confirm the list still reflects
   the locally observed catalog and the error clears.

Do not uninstall/clear app storage between offline relaunch steps. Preserve the
exported schema; there is no destructive migration fallback.

Endpoint/authentication references: [TMDB popular movies](https://developer.themoviedb.org/reference/movie-popular-list),
[TMDB application authentication](https://developer.themoviedb.org/docs/authentication-application).
