---
name: android-module-structure
description: "Module layout and dependency rules for the Filmio Android assignment. Use when planning feature, core, presentation, domain, data, app, or Gradle module boundaries."
---

# Android Module Structure

The original assignment PDF is the primary source of truth; `docs/Android Technical Assignment-Senior.md` is a convenience transcription. Build an Android-only, single-activity, Compose app. Use Clean Architecture with MVI and unidirectional data flow (UDF), which satisfies the PDF's MVVM-or-similarly-clean requirement. Keep modularization proportional to the required flows and user-selected offline/favorites scope.

## Suggested shape

```text
:app                         Single activity, navigation host, DI assembly
:core:domain                 Only genuinely shared domain contracts/models/errors
:core:data                   Shared Retrofit/OkHttp setup and data utilities, if needed
:core:presentation           Shared UI utilities, if needed
:core:designsystem           Reusable theme/components, if needed
:feature:<name>:domain       Feature models and repository contracts
:feature:<name>:data         Retrofit services, DTOs, mappers, repository implementations
:feature:<name>:presentation ViewModel, Screen, State, Action, Event
```

Group the movie list, detail, movie/series search, and favorites into a cohesive `:feature:catalog:{domain,data,presentation}`: they share media identity and one Room source of truth. Do not create one trio per screen. Catalog data owns its Room database, TMDB endpoints, DTOs, entities, DAOs, Paging mediators, and repositories. Place the shared Retrofit result boundary in `:core:data` when it is implemented, even if catalog is initially its only caller. Create other core modules only for a clear shared responsibility; Gradle convention plugins are optional when they reduce repeated configuration.

## Dependency direction

| Module | Allowed dependencies | Forbidden dependencies |
|---|---|---|
| Feature `domain` | Kotlin, approved pure `core:domain` abstractions, and Paging's non-UI `PagingData` for a paged repository contract if needed | Android UI, Retrofit/OkHttp, JSON DTOs, Room entities/DAOs, any other feature implementation or domain |
| Feature `data` | Its own feature `domain`; approved `core:domain` and `core:data` infrastructure | Other feature data/presentation implementations; exposing DTOs/entities/framework types to domain or presentation |
| Feature `presentation` | Its own feature `domain`; approved `core:presentation`/design system/UI libraries | Any feature data implementation; another feature presentation or data implementation |
| `:app` | Feature presentation entry points, DI modules, shared infrastructure needed for composition | Business logic, DTO mapping, or direct screen data access |

The exception for cross-feature domain access is an explicitly justified shared abstraction moved into `core:domain` or a small dedicated contract module. Do not make one feature's domain depend on another feature's domain just to share a model. Cross-feature behavior uses navigation contracts, app-level composition, or an approved shared/domain-level contract; it never imports another feature's data or presentation implementation.

`core:domain` remains pure Kotlin. `core:data` may own Retrofit/OkHttp construction, but feature data owns TMDB endpoint interfaces and feature DTOs. `core:presentation` and the design system may contain reusable Compose code, never business or networking logic. Prefer explicit Gradle dependencies so an illegal direction is visible in each module.

## Presentation and domain conventions

- For a screen named `<Feature>`, normally define `<Feature>Screen`, `<Feature>State`, `<Feature>Action`, `<Feature>Event`, and `<Feature>ViewModel` in presentation. State is persistent UI state; Action is a sealed user/UI input; Event is a sealed one-time effect. The ViewModel owns transitions, and the Screen observes state, sends actions, and handles events through UDF.
- A separate root composable may connect lifecycle, ViewModel, and navigation when useful. Avoid extra MVI base classes or reducers without a concrete benefit.
- UseCases are optional. Use one for meaningful business logic or coordination, not a one-line repository delegate. Simple repository operations may be called from the ViewModel through its feature domain contract.

## Technology choices for later setup

- Required by PDF: Kotlin, Gradle, Jetpack Compose presentation, one activity, Retrofit for TMDB, network error handling, and unit tests for domain, data, and ViewModels.
- Allowed by PDF: Compose Navigation or Fragment-based navigation; Koin or Dagger/Hilt. For this Compose-first app, prefer Compose Navigation and retain Koin from `start-project` unless later evidence makes another choice better.
- Supporting choices: OkHttp and a Retrofit JSON converter such as Moshi; Coil for poster/backdrop loading. These are implementation recommendations, not technologies named as mandatory by the PDF.
- User-selected optional scope: Room-backed offline-first catalog and local favorites, plus Paging 3 for the infinite list. DataStore remains conditional on a concrete small preference. See `android-offline-first`.
- Keep API credentials out of committed source and route them to data configuration during a later setup phase.

Keep this project Android-only with Retrofit for networking. Add WebSocket/auth infrastructure or a full build-logic module graph only if a concrete Filmio requirement calls for it. Keep feature dependencies directed inward, shared core modules focused, and application composition at the app level.
