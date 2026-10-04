# Catalog database

This Android library owns the five-table `CatalogDatabase` version 1:
`movies`, `movie_details`, `movie_genres`, `movie_production_companies`, and
`movie_favorites`. The initial export is under
`schemas/com.example.filmio.feature.catalog.database.CatalogDatabase/1.json`.
The unshipped version-1 draft was revised before production construction or DI
integration. The future filename is `filmio-catalog.db`; construction, migrations,
and DI remain integration work. Adding the DAOs does not change the version-1 schema.

`catalog:data` depends on this module and will map DTOs to local records and
committed reads to domain models. Database has no feature domain/data/presentation
or networking dependency. Room runtime is exported for the public superclass;
Paging common and coroutines core are exported for public `PagingSource` and `Flow`
signatures; other storage dependencies are private. Versions remain unchanged.

## DAO API

`CatalogDatabase` exposes three DAOs by operation ownership, rather than one CRUD
interface per table:

| DAO | Public operations | Contract |
| --- | --- | --- |
| `MovieDao` | `pagingSource`, `searchPagingSource`, `getMovie`, `upsertMovies` | Catalog/search paging in title/ID order; nullable one-shot summary read; atomic additive page upserts. |
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
captures its query and IDs; data must create a new source/Pager when those inputs
change. Room invalidates existing sources for relevant database writes, not for
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

These are integration contracts. The app/search/remote flows are not wired yet.

## Verified storage foundation

- Canonical movies have optional detail snapshots, ordered owned genres/companies,
  and independent local favorites. Null/zero values remain distinct, with `Long`
  budget/revenue preserving amounts beyond the `Int` range.
- PK/FK and owner/position uniqueness reject invalid/dangling metadata. Favorites
  restrict canonical deletion; removing details cascades only their owned children.
- DAO non-destructive page writes share canonical identity and retain omitted
  movies, details, and favorites, including duplicate-only and empty pages. Forced
  page/detail failure and cancellation before commit preserve committed content.
- DAO local-match/remote-ID paging and SQL fixtures select only committed rows,
  support new offline queries, include API-only matches, and deduplicate IDs without
  result tables. Large active-ID sets, SQL punctuation, and blank input are covered.
- Production snapshot reads preserve response positions, distinguish missing and
  summary-only rows, clear omitted detail data, and reject mismatched owners.
- Favorite paging/status observation covers idempotent add/remove, timestamp order,
  updated summaries, and invalidation. Catalog/search sources also invalidate after writes.
- File-backed reopen retains the shared catalog, optional details, ordered metadata,
  favorites, and local matching. Tests close resources and remove temporary files.

Schema fixtures and DAO tests exercise actual Room-created storage and local
PagingSource/Flow behavior. They do not prove network/mediator/repository/UI behavior.
Artwork paths do not guarantee offline image bytes.

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
not wait for remote debounce. Reconnect retries/refreshes the active catalog/query
while content remains visible; it does not synchronize historical queries. There is
no one-hour result-set freshness policy. Details retain tunable 24-hour freshness
with an injected clock; clock rollback makes them stale without deleting content.

The following covers every revised capability requirement for subsequent tests:

| Capability requirement | Integration tests / owner |
| --- | --- |
| Canonical identity | Popular + two searches + detail share identity; equal movie/series IDs remain separate (data). |
| Summary/detail completeness | Summary-only offline detail unavailable; fetched nullable details retain availability (data and presentation). |
| Enrichment and summary updates | Same summary enriched; later page preserves extended time/children; null collection/empty lists clear atomically; uncached detail becomes catalog content (data and database local APIs). |
| Nullable selected metadata | Blank/null normalization; known zero; invalid IDs/titles/dates/numerics reject complete update (data). |
| Boundary separation | Committed local reads map to framework-free domain content (data). |
| Independent favorites | Favorite survives omitted movies/refresh/restart; no dangling favorite; local add/remove offline (data and database local APIs). |
| Failed/canceled updates | Timeout/malformed/local failure retains snapshot; pre-commit cancellation rolls back; committed transaction remains valid (data and database local APIs). |
| Durable/versioned content | Real endpoint content reopens offline; future migration retains movies/details/children/favorites (data and database). |
| Shared catalog | Popular A/B and search B/C produce A/B/C once; detail shares identity; deterministic local order (data and database local APIs). |
| Immediate local search | New unseen query offline; cache before response/debounce; title/original-title literal punctuation/ASCII case; blank input clears old remote membership (data and presentation). |
| Local/remote union | Cache A/B + API B/C yields A/B/C; API-only title mismatch included; empty API keeps local matches; publication follows successful persistence (data and database local APIs). |
| Additive refresh/failures | Omitted movie and empty success retained; failed search leaves cached matches with retry; offline no-match is incomplete rather than confirmed remote-empty (data and presentation). |
| Session pagination | Restart page 1 with cached rows; failed append retries same page; duplicate-only page advances (data mediator). |
| Endpoint end | Last search page terminal; page 500 cap; short nonempty page continues; wrong response page rejected regardless of local counts (data). |
| Superseded requests | Slow old query/type IDs/errors/key cannot publish into new search; append/refresh serialization; selected type and overlapping IDs isolated (data and presentation). |
| Timing/reconnect | Immediate local input while remote debounce waits; rate configurable; active query/catalog refreshes on reconnect with content visible (data and presentation). |

Keep HTTP/mapping/repository/mediator tests in data, real local transaction/migration
tests here, and debounce/type/load-state tests in presentation. Series storage,
author endpoints, and multiple content locales require later design. Companies are
production metadata. No automatic eviction or routine destructive migration is
permitted for promised offline content.

## Verification

With an available Android device/emulator:

```sh
./gradlew :feature:catalog:database:assembleDebug :feature:catalog:data:assembleDebug
./gradlew :feature:catalog:database:connectedDebugAndroidTest
```

Review the exported schema with its implementation. Future delivered-schema changes
must increment the version and validate preserving migrations. There is no
`Migration(0, 1)` or destructive fallback for this initial draft.
