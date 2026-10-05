# Catalog database

This module owns persistent `filmio-catalog.db`, Room SQL, local projections, and atomic writes. Catalog data owns DTO validation/mapping, HTTP, Pagers/mediators, request sessions, and error translation. App binds the database/DAOs as Koin singletons. Database has no catalog domain/data/presentation or networking dependency; Room runtime, Paging common, and coroutines are exported because they appear in its public API.

## Storage and write semantics

Version 1 exports [the schema](schemas/com.example.filmio.feature.catalog.database.CatalogDatabase/1.json). The five tables share one canonical movie identity:

| Table | Contract |
| --- | --- |
| `movies` | Canonical summaries shared by All, search, details, and Saved. Page upserts are additive; omitted movies and empty pages retain existing content. |
| `movie_details` | Optional shared-primary-key extended metadata. Row existence marks fetched availability, even when fields are null. |
| `movie_genres`, `movie_production_companies` | Detail-owned ordered children. Detail deletion cascades to these lists. |
| `movie_favorites` | Independent membership and added time; its foreign key restricts canonical movie deletion. |

There are no durable result sets, remote IDs, page keys, query history, or feed/order flags. The catalog is everything fetched so far; local ordering does not reproduce TMDB ranking. Movie callers currently use one content locale (`en-US`); additional locales or series require storage design rather than reusing movie IDs blindly.

DAOs own transactions. Keep HTTP outside them and use non-destructive parent upserts, never `INSERT OR REPLACE`. Constraint/storage failures propagate to data; a failed transaction must leave the previous snapshot intact.

- **`MovieDao`:** summary observations and catalog/search paging in `title COLLATE NOCASE ASC, id ASC` order; atomic page upserts affect summary columns only and preserve details/favorites.
- **`MovieDetailDao`:** transactional snapshot reads and complete replacement across summary, detail, genres, and companies. Null snapshot means absent movie; present snapshot with null detail means summary-only. Replacement clears supplied nulls/empty lists, validates owners, and deletes old children before inserting reordered positions. Relation reads explicitly sort by position because Room does not promise child order. Child writes remain protected inside this DAO.
- **`MovieFavoriteDao`:** observable membership/status and paged joins to current summaries. Desired-state writes validate canonical existence in the same transaction; a missing save target returns false. Repeated save preserves added time, remove/re-save records a new time, and unsave retains cached content. Saved orders by added time descending, then movie ID ascending; blank query returns all favorites.

Data supplies commit timestamps and validated records. Detail mapping preserves 64-bit money, skips null children, and retains each duplicate child's last occurrence with contiguous positions. No independent child-table CRUD or generic cache-deletion API is exposed.

## Literal search and remote membership

Local matching is a trimmed title/original-title substring, ignoring **ASCII** letter case only. Other characters remain literal, including `%`, `_`, `*`, `?`, and brackets. The bound GLOB pattern uses explicit ASCII letter pairs and escaped pattern characters; `lower()` on ICU-enabled Android SQLite can fold non-ASCII characters and change this contract.

`MovieDao.searchPagingSource` unions local matches with committed active remote IDs. Blank input returns no rows, even with obsolete IDs. User text is bound; only typed `Long` IDs enter the numeric SQL clause, avoiding older SQLite bind limits in long sessions. Saved search intersects literal matches with the favorite join and preserves saved-time order.

A source captures its query and remote-ID snapshot. Room invalidation observes table writes, not external membership changes. Data must commit movies, publish the new IDs, then invalidate/recreate the source so its replacement captures updated membership. This also applies to duplicate-only and null-only batches. Query changes create a new Pager; remote continuation and membership remain outside this module.

## Persistence and verification constraints

The version-1 schema is an unshipped draft revised in place. Released schema changes require a version increment and preserving migrations; do not use destructive fallback or automatic eviction for offline content. Keep exports consistent with entities, SQL, indexes, and foreign keys.

App backup rules exclude the database domain, including SQLite sidecars, from cloud backup/device transfer. Persistence is installation-local; restart retains content, uninstall/data clearing does not promise recovery.

Automated coverage is local JVM only. Fake-DAO tests in catalog data check coordination and query construction, not actual SQL, transaction atomicity, or durable persistence. Review storage changes against the exported schema and use the [root verification walkthrough](../../../README.md#verification) for relevant device checks.
