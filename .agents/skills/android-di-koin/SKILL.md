---
name: android-di-koin
description: "Koin dependency injection guidance for Filmio's Android-only app, including Retrofit/OkHttp services, feature repositories, ViewModels, and app-level assembly. Use when choosing or wiring Koin dependencies."
---

# Android Dependency Injection (Koin)

The assignment permits Koin or Dagger/Hilt. This skill documents the Koin choice retained from `start-project`; it is not a PDF mandate. Keep DI assembly small and verify all required dependencies can be resolved.

## Principles

- One Koin module per feature layer — create it only if there are dependencies to provide.
- Modules are assembled in `:app`, never in feature modules themselves.
- In Compose root composables, always inject ViewModels via `koinViewModel()`.

---

## Module Definitions

Prefer the constructor-reference overloads (`singleOf`, `viewModelOf`, `factoryOf`) — they are more concise and let Koin resolve parameters automatically. Only fall back to the lambda overloads (`single { ... }`, `viewModel { ... }`, `factory { ... }`) when constructor injection alone is not enough, e.g. when you need to call a factory method, pass a named/qualified dependency, or do post-construction setup.

### Data layer module

```kotlin
// feature:movies:data
val moviesDataModule = module {
    single { get<Retrofit>().create(TmdbMovieService::class.java) }
    singleOf(::RetrofitMovieRepository) { bind<MovieRepository>() }
}
```

### Presentation layer module

```kotlin
// feature:movies:presentation
val moviesPresentationModule = module {
    viewModelOf(::MovieListViewModel)
    viewModelOf(::MovieDetailViewModel)
}
```

### Shared networking module (example)

```kotlin
// core:data, only if more than one feature needs shared networking setup
val coreDataModule = module {
    single { OkHttpClient.Builder().build() }
    single { Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build() }
    single {
        Retrofit.Builder()
            .baseUrl(get<TmdbConfig>().baseUrl)
            .client(get<OkHttpClient>())
            .addConverterFactory(MoshiConverterFactory.create(get<Moshi>()))
            .build()
    }
}
```

The example assumes an injected `TmdbConfig` whose base URL and credential come from non-committed configuration. Add an OkHttp authorization interceptor when configuring the client for the actual API. In feature data modules, create typed Retrofit services from the shared instance and bind repository implementations to feature-domain interfaces. Do not expose Retrofit or DTOs to presentation.

---

## Assembly in `:app`

Register all modules in the `Application` class:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@App)
            modules(
                // core
                coreDataModule,
                // features
                moviesDataModule,
                moviesPresentationModule,
                // Register other implemented feature modules here.
            )
        }
    }
}
```

---

## Injecting in Composables

Always use `koinViewModel()` in Root composables:

```kotlin
@Composable
fun MovieListRoot(
    onNavigateToDetail: (String) -> Unit,
    viewModel: MovieListViewModel = koinViewModel()
) { ... }
```

Never pass ViewModels down the composable tree — inject at the Root level only.

---

## Scoping Rules

| Scope | Preferred form | Fallback form | When to use |
|---|---|---|---|
| Singleton | `singleOf(::Impl) { bind<Interface>() }` | `single { ... }` | One instance for the app lifetime (repositories, OkHttpClient, Retrofit) |
| ViewModel | `viewModelOf(::MyViewModel)` | `viewModel { ... }` | ViewModel instances scoped to their lifecycle |
| Factory | `factoryOf(::Impl)` | `factory { ... }` | New instance on every injection (rare — prefer singleton or ViewModel) |

Use the `*Of` constructor-reference form by default. Only use the lambda form when you cannot express the binding with a constructor reference (factory methods, named qualifiers, manual setup).

---

## Naming Conventions

| Thing | Convention | Example |
|---|---|---|
| Koin module | `<feature><Layer>Module` | `moviesDataModule`, `moviesPresentationModule` |

---

## Checklist: Adding DI for a New Feature

- [ ] Define `val <feature>DataModule = module { ... }` in `feature:data`
- [ ] Define `val <feature>PresentationModule = module { ... }` in `feature:presentation`
- [ ] Register both modules in `:app`'s `startKoin { modules(...) }`
- [ ] Use `koinViewModel()` in Root composables that inject a ViewModel
