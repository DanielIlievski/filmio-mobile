---
name: android-module-structure
description: "Module layout and dependency rules for the Filmio Android assignment. Use when planning feature, core, presentation, domain, data, app, or Gradle module boundaries."
---

# Android Module Structure

The assignment in `docs/Android Technical Assignment-Senior.md` is the primary source of truth. Build an Android-only, single-activity, Compose app. Use Clean Architecture with MVI and unidirectional data flow (UDF), which satisfies the PDF's MVVM-or-similarly-clean requirement. Keep modularization proportional to three screen flows.

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

Group screens by cohesive feature, such as movie browsing and search; do not automatically create one triple of modules per screen. Create a core module only for genuinely shared functionality. A feature-specific DTO, repository, or UI component stays with that feature. A shared Room database module is optional only if persistence is implemented. Gradle convention plugins are optional when they reduce repeated build configuration; they are not required for this assignment.

## Dependency direction

| Module | Allowed dependencies | Forbidden dependencies |
|---|---|---|
| Feature `domain` | Kotlin and approved pure `core:domain` abstractions | Android UI, Retrofit/OkHttp, JSON DTOs, persistence implementations, any other feature implementation or domain |
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
- Optional scope: Room/DataStore, local favorites, offline cache, and associated database modules. Add only if the optional feature is chosen.
- Keep API credentials out of committed source and route them to data configuration during a later setup phase.

Do not copy Chirp's Kotlin Multiplatform/iOS targets, Ktor stack, WebSocket/auth infrastructure, or its full build-logic module graph. Its useful reference is inward feature dependencies, small shared core modules, and app-level composition.
