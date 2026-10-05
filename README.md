# Filmio

Filmio is an Android TMDB app built around a persistent catalog: popular movies and inline search feed the same Room store, details refresh while cached content stays visible, and local favorites form the Saved collection. The UI uses Compose Material 3, MVI, and single-activity Navigation 3; networking uses Retrofit/OkHttp/Moshi, pagination uses Paging 3, and Koin assembles dependencies.

## Run locally

Use Android Studio with Android SDK 37 and an API 29+ device/emulator. Use the checked-in Gradle wrapper and daemon JVM criteria (JDK 25); compiled modules target Java 11. Configure the SDK path in ignored `local.properties`.

Create ignored root `tmdb.properties` with your TMDB **API Read Access Token**:

```properties
TMDB_READ_ACCESS_TOKEN=your-private-read-access-token
```

A nonblank `TMDB_READ_ACCESS_TOKEN` environment variable overrides the file; a blank value falls back to it. The API key is unused. Authentication uses a Bearer header. Rebuild after changing the token.

```sh
./gradlew :app:assembleDebug
```

Run `:app` from Android Studio or install `app/build/outputs/apk/debug/app-debug.apk`. Without a token, build/startup and cached reads work; remote requests still reach TMDB and report authorization failures.

The token is embedded in BuildConfig and extractable from the APK. Keep credential-bearing APKs, generated sources, caches, and CI outputs private; ignored configuration alone does not protect distributed artifacts.

## Design decisions

[AGENTS.md](AGENTS.md) defines module boundaries and working conventions. The catalog stays one feature because list, search, details, and favorites share movie identity. Domain exposes contracts, data owns networking/mapping/paging, database owns SQL and transactions, presentation owns MVI, and app owns DI/navigation. ViewModels call repositories directly where no business coordination needs a UseCase.

Room is the read source for every screen. Successful requests commit before UI content changes. All is the additive collection of previously fetched popular/search movies in alphabetical title/ID order, rather than TMDB popularity order. Refresh never clears omitted movies or local favorites. Remote pages and search membership are session-only; relaunch retains content and restarts remote pagination at page 1. Paging may fetch the next remote page before scrolling when a local load reaches the cache boundary.

All search shows cached title/original-title matches immediately, then requests TMDB after a fixed 500 ms quiet period. Results combine local matches with committed remote IDs in local order. Matching ignores ASCII case and treats pattern characters literally. Empty input makes no search request. Saved and its search are entirely local, ordered newest-saved first. Scope/query changes cancel obsolete search sessions, and query/selection restore after process recreation.

Details observe Room independently of fetching. A new destination always requests current details; summary-only and fetched-but-unknown metadata stay distinguishable. Active reconnect refreshes current All/search or details while content remains visible. It does not synchronize historical queries or Saved. Navigation retains the list ViewModel and scroll position on Back.

The [database README](feature/catalog/database/README.md) covers snapshot replacement, favorite integrity, query construction, and migration constraints. Content/favorites survive restarts on the same installation; the database is excluded from backup/device transfer. Uninstall/data clearing loses it. Offline artwork depends on Coil's byte cache and is not guaranteed by stored image paths.

## Verification

Automated tests are local JVM tests using small fakes, MockWebServer, coroutine virtual time, and test-only Paging utilities. They cover network/mapping boundaries and repository/ViewModel coordination. Domain currently has no business rules needing separate tests. Fakes do not establish real Room SQL/atomicity, durable reopening, or Compose behavior.

```sh
./gradlew :core:data:test :feature:catalog:data:testDebugUnitTest \
  :feature:catalog:presentation:testDebugUnitTest \
  :feature:catalog:database:assembleDebug :app:assembleDebug
```

For changes affecting cache or restoration, manually check:

1. Load movies, search, open details, and save a movie online. Confirm search-enriched content appears in All and updates preserve Saved membership.
2. Disable connectivity, force-stop, and relaunch **without clearing data**. Browse cached list/details/Saved and search cached titles. Uncached content should report unavailable/no cached matches.
3. Restore connectivity with All/search or details active. Cached content should remain visible during refresh; Saved should make no request. Check failed append Retry separately from page-1 Refresh.
4. Navigate Back, clear search, switch All/Saved, and recreate the app. Verify query/selection and the relevant scroll anchors; unsaving should remove Saved membership while retaining cached content.
