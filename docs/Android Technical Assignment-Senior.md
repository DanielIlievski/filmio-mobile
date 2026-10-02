# Android Engineer Technical Assignment

The goal of this test is to write from scratch a simple app in Kotlin. This app will show a list of films. The test will require building 2 different screens:

1) Main screen: The movies list based on <https://developers.themoviedb.org/3/getting-started/introduction>
   a) Must show a list of movies using Jetpack compose.
   b) The list must have an infinite scroll.
   c) The movie must show a title, a picture, and a short description.
2) Details Screen: Once you pick a movie, the application must open a new screen with the details of the selected movie. The screen must show extended content apart from the standard information, such as ratings, votes, and authors - feel free to add as much information as you want.
3) Search screen: Search content. The app must be able to search for different movies and series, selecting which one in advance. The search feature must be throttleable.

You can use Jetpack Compose Navigation for navigation between screens, or, if preferred, you may implement the older Fragment-based navigation.

## Technical details

### Dependencies

- Use Gradle to manage project dependencies. Libraries such as Retrofit (for networking) and Dagger/Hilt or Koin (for dependency injection) should be included as necessary.

### Architecture

- The architecture must follow MVVM or a similarly clean architecture pattern.
- Ensure that the project uses a single-activity architecture, where Jetpack Compose is used for the presentation layer.
- You can use either Jetpack Compose Navigation or Fragment-based navigation for managing screen transitions.

### Networking

- Use Retrofit to make API calls to the TMDB API.
- Handle network errors with proper error-handling mechanisms.

### Dependency Injection

### UI

- Build a declarative UI using Jetpack Compose.

### State Management

- Ensure proper state management in Compose to handle recompositions and UI updates efficiently.

### Unit tests

- Write unit tests for the domain, data layers, and ViewModels to ensure proper functionality and reliability.

## What would be evaluated

- Design and Approach:
  - Overall architecture and design patterns used (e.g., MVVM).
  - Use of modern Android development practices and patterns.
- Documentation and Comments:
  - Inline comments and overall project documentation, including a README file with setup instructions and explanations of design choices.
- Data Model:
  - Structure and organization of data models and their relationships.
- Errors management
- Unit tests
- Dependency Injection
- Usage of modern Kotlin features such as coroutines, sealed classes, flows, etc.
- Files organization in the project
- Resource Management
- Performance

## Notes

- The assignment must be delivered via the GIT repository and must contain several commits.
- English might drive the development of the exercise, files, comments, classes, etc.

## Optional

- Users to be able to have a favorite list (locally).
- Offline mode (storage of choice).
