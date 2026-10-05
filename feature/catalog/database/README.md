# Catalog database

This Android library owns the five-table `CatalogDatabase` version 1:
`movies`, `movie_details`, `movie_genres`, `movie_production_companies`, and
`movie_favorites`. The initial export is under
`schemas/com.example.filmio.feature.catalog.database.CatalogDatabase/1.json`.
The unshipped version-1 draft was revised before production construction or DI
integration. `createCatalogDatabase(context)` now constructs persistent `filmio-catalog.db` using
application context. App supplies its singleton/DAO bindings through Koin. Future
released schema changes still require preserving migrations; the observer/factory
leave the version-1 export unchanged.

`catalog:data` depends on this module and maps validated popular/search movie and top-level detail DTOs to local
records and committed reads to domain models. Database has no feature domain/data/presentation
or networking dependency. Room runtime is exported for the public superclass;
Paging common and coroutines core are exported for public `PagingSource` and `Flow`
signatures; other storage dependencies are private. Versions remain unchanged.

## DAO API

`CatalogDatabase` exposes three DAOs by operation ownership, rather than one CRUD
interface per table:

| DAO | Public operations | Contract |
| --- | --- | --- |
| `MovieDao` | `observeMovies`, `pagingSource`, `searchPagingSource`, `getMovie`, `upsertMovies` | Whole-catalog Flow and catalog/search paging in title/ID order; nullable one-shot summary read; atomic additive page upserts. |
| `MovieDetailDao` | `getMovieDetail`, `observeMovieDetail`, `upsertMovieDetail` | Transactionally read/observe one snapshot; atomically replace summary, detail, and ordered owned children. |
| `MovieFavoriteDao` | `pagingSource`, `observeIsFavorite`, `addFavorite`, `removeFavorite` | Current canonical summaries with favorite timestamps; observable status; local idempotent writes. |

`MovieDetailSnapshot` being null means the movie is absent. A nonnull snapshot with
null `detail` is a summary-only movie; fetched details can have nullable metadata.
Room relation reads run inside a transaction, and the DAO sorts children by stored
position before returning/emitting the snapshot. Complete replacement clears null
fields and empty child lists. It validates matching owners before writing, upserts
parents without destructive replacement, and deletes old children before inserting
new ones so positions can be reordered. Genre/company helpers are protected; data
cannot publish an incomplete snapshot through separate child-table DAOs.

Favorites are newest first (`addedAtEpochMillis DESC, movie ID ASC` for ties).
Adding an existing favorite preserves its original added time. Removing an absent
favorite is harmless; removing then re-adding records the new time. Adding requires
an existing canonical movie. Favorite paging joins current movie summaries, so a
later summary refresh appears without copying data into favorites.

No generic cache deletion, independent child CRUD, remote continuation, freshness
decisions, or HTTP handling is exposed. Local constraint/storage failures and
cancellation propagate to data; Room rolls back failed transactions. A successful
commit is the point after which data may publish IDs or advance continuation.

## Selected catalog and search behavior

Home displays all previously stored movies, combining popular and search content.
Responses add/update canonical movies without clearing older content. One movie
appears once even when multiple responses return it. This is everything fetched
so far, with deterministic local ordering, not every movie in the remote catalog.

For any nonblank query, search immediately reads matching stored titles/original
titles, including when that query has never been requested remotely. Local matching
uses literal substrings after trimming, ignores ASCII letter case, and preserves
other characters literally. Percent/underscore are not wildcards. The initial
ordering is `title COLLATE NOCASE ASC, id ASC`; neither local matching nor order
claims to reproduce TMDB semantics/ranking.

The bound search pattern uses GLOB with explicit ASCII letter pairs and escaped
pattern characters. Android SQLite builds can use ICU-aware `lower()`, so calling
that function would also fold accented letters and break the selected contract.

Blank search input returns no rows, even if obsolete active IDs were supplied.
The search factory binds user text and constructs its remote-ID clause exclusively
from typed `Long` values. Numeric ID literals allow long sessions without exceeding
older Android SQLite bind limits. Only committed movies are returned. The factory
captures its query and IDs; data creates a new source when membership changes and a
new Pager when the query changes. Room invalidates existing sources for relevant database writes, not for
changes to external in-memory session lists.

Online, an independently debounced API request validates and saves movies to Room
before the active query publishes their IDs. Visible search is the deduplicated
union of local matches and committed active remote IDs. This includes API-returned
movies whose titles do not match locally. Cached matches remain visible while
loading, offline, or after failure. No local matches offline means no cached matches,
not a confirmed absence from TMDB. Empty remote pages never clear cached movies.

Active query/type/options, returned IDs, page continuation, and request generation
remain in memory. A query/type change drops previous remote membership; an obsolete
response cannot publish into the new session. Relaunch keeps movie content and
favorites while remote pagination starts at page 1. There are no persistent result
sets, memberships, query history, page keys, feed/source flags, or per-movie order.

The paginated popular-movie flow uses `OfflineFirstCatalogRepository`,
`MovieDao.pagingSource()`, and `MoviesRemoteMediator`. Room remains the read
source while the mediator commits additive summaries before advancing in-memory
continuation. The data-layer anchor adapter preserves absolute local refresh keys
with disabled placeholders and relays Room invalidation; it does not modify queries
or the schema. Each Pager also invalidates its source after a successful nonterminal
all-null batch, since no table write can wake Paging in that case. Duplicate-only
writes still invalidate normally and do not imply terminal behavior.

The mediator rejects mismatched response pages, skips null entries, maps supplied
values directly, and uses one timestamp per batch. `safeDatabaseUpdate` maps expected
SQLite write failures to typed domain errors carried through Paging load states.
Cancellation and unexpected defects propagate. Native Paging coordinates local
loads, remote refresh/append, and retry. A local load reaching the database end may
request the next remote page immediately, including after initial refresh before
scrolling. Local read load errors use Paging retry with safe storage feedback.

Movie search now uses `OfflineFirstCatalogRepository.searchMovies(query, fetchRemote)`.
The local-only mode constructs a Room Pager without HTTP work. The remote mode uses
`MovieSearchRemoteMediator` and captures its immutable membership snapshot in each
`searchPagingSource` factory call. Every successful commit publishes membership before
explicit source invalidation, including duplicate-only and null-only batches. Refresh
replaces membership; append extends it. Failed/canceled/superseded loads retain prior
membership and cannot advance continuation. Canonical rows already committed before
cancellation remain safe to reload.

The ViewModel caches the current query's stream, projects load feedback, and emits Refresh/Retry
commands to the active root. Active default-network reconnect triggers page-1 refresh
without removing cache content; callbacks are released when the screen stops. Presentation
starts local reads immediately and switches to remote search after a configurable 350 ms
quiet period. Query-owned scopes and generation tokens prevent abandoned requests or
commands from affecting current content. Favorite domain operations/presentation remain
integration work. Details now
consume the existing `MovieDetailDao` observation and atomic-write operations through the same repository.

## Implemented detail consumer

`OfflineFirstCatalogRepository.getMovieDetails` maps the DAO's local snapshot Flow to
safe domain availability. Expected SQLite read failures terminate it with a typed
`CatalogStorageException`; callers catch this domain carrier, resubscribe, and retain
their last content. Observation initiates no network request. Every `fetchMovieDetails` invocation requests the latest API data
without a preliminary cache lookup. Existing details remain observable during the fetch.
Entry and active reconnect start requests; the detail screen exposes Back as its only action.

The detail mapper validates requested identity, uses one injected-clock timestamp, and
maps every selected summary/detail column. Null children are skipped; duplicate IDs retain
last-occurrence order with unique contiguous positions. `upsertMovieDetail` replaces all
owned metadata before completion is reported. Detail nulls/empty lists clear old values;
popular/search writes later update only shared summary fields. Favorites remain independent.
Data narrowly maps SQLite observation errors and reuses the existing safe write boundary. Cancellation
and defects propagate; network work stays outside the existing transaction.

No DAO, entity, SQL, index, foreign key, version, or export changed for details. Identity
hash remains `e4403482a9eea406ad365cc39809fb7d`. See the root README and
[`docs/verification/inline-movie-search.md`](../../../docs/verification/inline-movie-search.md)
for JVM evidence and separate device checks, including detail/favorite preservation
through search and subsequent Home writes.

## Integration handoff

Data owns DTO → entity → domain mapping, validation, networking, repositories,
`Pager`/`RemoteMediator`, active session state, and typed error translation. Database
owns SQL/ordered local projections/`PagingSource` and atomic summary-page
upserts/detail replacement. Do not put network calls inside transactions. Parent
writes use `@Upsert` or insert-ignore plus update, never `INSERT OR REPLACE`.

Commit complete validated responses before publishing remote IDs or advancing
session continuation. Changing active IDs must recreate the bound Room query/source
or explicitly invalidate it with updated parameters. Serialize/check refresh and
append within an active session; propagate cancellation and keep retry keys on
failure. End on empty raw results, reported last page, or accessible page 500;
reject mismatched page metadata. Local match/catalog/new-row counts do not decide end.

All movie callers use `en-US`; search uses `include_adult=false`. Local matches do
not wait for remote debounce. The movie list refreshes the active catalog or settled
movie query on reconnect while content remains visible; reconnect does not bypass debounce.
Reconnect does not synchronize historical queries. There is
no one-hour result-set freshness policy. Details always fetch on a new entry and active reconnect while displaying stored content.
The injected clock records commit timestamps.

The assignment uses local JVM unit tests only. Repository fakes exercise coordination,
not Room SQL or durable persistence. Review storage changes against these DAO contracts
and the exported schema, and use the root README's manual offline walkthrough.

Series storage, author endpoints, and multiple content locales require later design.
Companies are production metadata. No automatic eviction or routine destructive
migration is permitted for promised offline content.

## Verification

```sh
./gradlew :feature:catalog:database:assembleDebug :feature:catalog:data:testDebugUnitTest
```

Review the exported schema with its implementation. Future delivered-schema changes
must increment the version and validate preserving migrations. There is no
`Migration(0, 1)` or destructive fallback for this initial draft.
