# PocketDSL Mobile

Offline DSL/DSL.DZ dictionary reader for Android and iOS, built with Kotlin Multiplatform.

## Status

MVP / experimental.

This repository currently contains the initial Kotlin Multiplatform skeleton. Archive import, parsing, indexing, and rendering behavior are intentionally not implemented yet.

## Planned features

- Import DSL/DSL.DZ dictionaries.
- Import `.tar.bz2` dictionary packages.
- Offline SQLite index.
- Prefix search.
- Article rendering.
- Favorites.
- History.

## Dictionary data

Dictionary files are not included.
Users must provide dictionary files they are legally allowed to use.

## Not affiliated

This project is not affiliated with GoldenDict, ABBYY Lingvo, Apresyan,
Smirnitsky, StarDict, traduko.lib.ru, or any dictionary publisher.

## Repository layout

- `shared/` contains common dictionary engine, storage, search, and state packages.
- `composeApp/` contains shared Compose UI placeholders and platform entry points.
- `iosApp/` contains the SwiftUI host application placeholder.
- `test-dictionaries/` contains artificial test fixtures only.

## License

Source code: Apache License 2.0.
Sample test dictionaries: CC0-1.0, unless stated otherwise.
