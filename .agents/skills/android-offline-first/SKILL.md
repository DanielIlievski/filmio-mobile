---
name: android-offline-first
description: Design, implement, or review Filmio's Android offline-first Room data layer, Paging 3 list/search feeds, cached TMDB details, and local favorites. Use when work changes cache schema, paged repositories, refresh behavior, offline UI semantics, or persistence tests. Do not use for unrelated networking or generic Room CRUD.
---

# Filmio offline-first data

The assignment PDF requires a TMDB movie list, details, and movie/series search; it lists favorites and offline mode as optional. The user explicitly chose both. Read the original PDF as primary source (the `docs/Android Technical Assignment-Senior.md` transcription is a convenience copy), root `AGENTS.md`, and `android-data-layer` before making implementation choices. This skill adapts `stanche-mobile`'s `kmp-offline-first` source-of-truth reasoning to an Android-only, read-mostly TMDB app. Do not copy its KMP targets, Ktor stack, server outbox, conflict resolution, tombstones, or account synchronization without an actual requirement.

## Offline contract

- Room is the canonical read source for movie lists, previously fetched movie details, previously searched movie/series result sets, and the local favorites list. UI reads through feature-domain repository contracts; a successful network call writes Room, and Room emissions update UI. Do not race direct Retrofit results against database results in a ViewModel.
- Previously loaded content and favorites remain usable without connectivity after process restart. A first-time empty cache cannot supply unseen TMDB data: show an explicit offline/unavailable state, not a misleading empty-result message. Cached content may be stale; surface refresh/failure status separately from the data when useful.
- Favorite add/remove is a local Room write and must work offline. TMDB does not own these favorites. Do not invent a server outbox, WorkManager sync loop, cross-device conflict policy, or authentication flow. Keep favorite state in its own table or equivalent durable relation so remote refresh never clears it.
- DataStore is for a concrete small preference, such as a selected search type or user setting, if one is required. It is not the catalog, favorite, page-key, or search-result store.
- Define the artwork guarantee explicitly. Coil's disk cache may make viewed images available offline, but a Room image URL alone does not. If offline posters must be guaranteed, design and test durable image storage or a deliberate fallback rather than assuming cache behavior.

## Room and Paging 3

- Use AndroidX Paging 3 for the infinite movie list. Build the `Pager` in data from a Room DAO `PagingSource` plus a Retrofit-backed `RemoteMediator`; UI items come from Room. Use the same pattern for paged search results if search is paginated. The data layer maps DTOs to entities and entities to domain models.
- Scope list membership, ordering, remote page keys, and freshness by the actual feed. Scope search cache and keys by normalized query **and** selected media type (plus locale/region or other request parameters when they affect results). TMDB movie and series IDs can overlap; use a composite media-type/ID identity where both appear.
- Apply fetched items, ordered feed/query membership, and the next-page/end marker in one Room transaction. A failed fetch leaves the prior committed cache and keys intact. `REFRESH` replaces only the relevant feed/query membership and remote keys; it must not wipe favorites, unrelated cached searches, or detail data still needed offline. `APPEND` must not repeat pages or emit duplicate rows. No `PREPEND` unless the feed contract needs it.
- Model true end of pagination from TMDB response metadata. Decide cache freshness/refresh policy explicitly; `initialize()` may skip an eager refresh for a fresh cache but must still allow user refresh. Make refresh and append errors retryable without hiding already cached rows.
- A movie detail fetch by stable ID writes Room before the UI sees new content. Distinguish a cached detail from a list-only summary; do not claim complete extended fields from a summary row. Keep nullable ratings, votes, review-author fields, and missing artwork safe.
- Search request timing belongs in presentation or a meaningful domain operation. Cancel/supersede old query flows and keep each query/type's cached results isolated. Offline search may show a previously cached exact query/type result; an unseen query/type is unavailable offline unless a separate local-search feature is deliberately built. Never imply a local subset is the complete TMDB search result.

## Boundaries and failures

- Keep Retrofit services, DTOs, Room entities/DAOs, `RemoteMediator`, and remote keys in data or justified shared data infrastructure. Domain models and contracts contain no Retrofit or Room types. A `Flow<PagingData<DomainModel>>` is an acceptable explicit Paging 3 contract at the feature-domain boundary; keep `Pager`, `PagingSource`, `RemoteMediator`, and `LazyPagingItems` out of domain.
- Map HTTP/transport/decoding errors at the data boundary. Paging mediator errors flow through Paging load states; presentation distinguishes initial failure with no cache from refresh/append failure while cached content remains visible. Propagate `CancellationException`.
- For database migrations, export schemas and preserve favorites and any data promised offline. Never use destructive migration as a routine fallback. Verify the chosen Room/Paging versions and APIs against current official Android documentation before editing Gradle; do not mix Room major-version artifacts.

## Verification

Test with a real Room database where transaction behavior matters, a fake TMDB service or MockWebServer for network cases, and Paging's test utilities where helpful. Cover cold online load, offline relaunch with cached rows, first launch offline, append/end-of-list, failed refresh/append preserving cache, retry, query/type isolation, movie/series ID collision, favorite add/remove offline and survival across refresh, detail summary versus full detail, and migration preservation. Test the selected search timing with coroutine virtual time. Keep tests focused on observable invariants rather than internal helper calls.

Official references: [Android offline-first data](https://developer.android.com/topic/architecture/data-layer/offline-first), [Paging from network and database](https://developer.android.com/topic/libraries/architecture/paging/v3-network-db), [Paging tests](https://developer.android.com/topic/libraries/architecture/paging/test), and [DataStore](https://developer.android.com/topic/libraries/architecture/datastore).
