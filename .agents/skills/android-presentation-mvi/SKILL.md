---
name: android-presentation-mvi
description: "MVI and unidirectional data flow for Filmio Compose screens. Use when designing a Screen, State, Action, Event, ViewModel, navigation effect, search behavior, or state restoration."
---

# Android Presentation: MVI and UDF

The assignment permits MVVM or a similarly clean pattern and requires a single activity with Jetpack Compose presentation. Here, MVI is the selected ViewModel-based presentation convention. Keep it simple and observable.

## Screen contract

For a screen named `<Feature>`, normally define:

| Type | Responsibility |
|---|---|
| `<Feature>Screen` | Renders state, emits actions, reacts to one-time events through a small root/host when needed |
| `<Feature>State` | Persistent UI state for the current screen, including loading, content, selection, and recoverable error states |
| `<Feature>Action` | Sealed class/interface of user or UI actions sent to the ViewModel |
| `<Feature>Event` | Sealed class/interface of one-time effects such as navigation or a transient snackbar |
| `<Feature>ViewModel` | Owns state transitions, calls feature-domain contracts, and emits events |

The Screen never calls Retrofit, a DAO, or another feature's implementation. Data flows Screen action -> ViewModel -> domain contract -> ViewModel state/event -> Screen. Collect state with lifecycle awareness. Prefer immutable state and update it atomically. Use an event stream for one-time effects; do not leave consumed navigation or snackbar commands in persistent state.

A root composable can inject the ViewModel, collect state/events, and connect navigation callbacks while a previewable Screen receives state and an action callback. Keep these in the same file when that is convenient. Do not require base ViewModels, reducer frameworks, separate Intent/Effect hierarchies, or boilerplate wrappers without a concrete payoff.

## Assignment behavior

- Movie list UI should render Paging 3 load states: initial loading, cached content, true empty, refresh/append loading, failure, retry, and end of list. Let Paging trigger subsequent pages instead of maintaining a parallel manual paginator.
- Details state should use a stable movie ID, show core and extended information when available, and represent loading/error/retry clearly.
- Search state should include query, selected content type (movie or series), cached results, and loading/error/empty/offline states. Use a cancelable Flow operator or equivalent to control query-triggered request rate and prevent stale results. Choose and document throttle/debounce semantics and the interval during implementation; the PDF does not specify them.
- Navigation remains at the app/host boundary. Pass stable IDs or route arguments, not data-layer DTOs or full mutable screen models.
- Compose UI state should survive recomposition. Use `SavedStateHandle` for essential inputs such as selected ID, search query, or content type when process restoration matters; avoid saving an entire response payload.
- Use Coil or another justified image loader for film artwork in presentation; show appropriate placeholders and error content.

## Domain calls and errors

Call a repository interface from the feature domain directly when the operation is simple. Add a UseCase only for meaningful business logic, multiple repositories, non-trivial coordination, or reusable transformations. A UseCase that only calls `repository.getMovies()` is unnecessary.

Map domain errors to UI text in presentation. Keep network/JSON exceptions out of the ViewModel and resource IDs out of domain. Use state for errors that persist until retry or input change, and events only for genuinely transient feedback.
