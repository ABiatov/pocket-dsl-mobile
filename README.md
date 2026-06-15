# PocketDSL Mobile

Offline DSL/DSL.DZ dictionary reader for Android and iOS, built with Kotlin Multiplatform.

## Status

MVP / experimental.

This repository currently contains the initial Kotlin Multiplatform skeleton plus shared dictionary parsing, archive import, indexing, search, and article rendering pieces.

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
Do not commit real dictionary archives or extracted dictionary data.

For local smoke testing, place a real `.tar.bz2` dictionary archive in the ignored `dict-example/` folder. The default expected path is:

```text
dict-example/enruen-content-1.1.tar.bz2
```

Run the JVM-only package import smoke test with:

```bash
./gradlew :shared:smokeImportLocalDictionary
```

To use another local archive path:

```bash
./gradlew :shared:smokeImportLocalDictionary -PdictionaryArchive=dict-example/your-dictionary.tar.bz2
```

If the archive is missing, the smoke test prints a skip message and exits successfully. If present, it imports the package into an in-memory JVM database and prints a summary like:

```text
Import summary:
  imported dictionary count: 1
  imported entry count: 12345
  failed dictionary count: 0
  ignored file count: 6
Lookup probes:
  a -> a
```

The smoke test prints headwords and counts only; it does not print full article contents.

## Not affiliated

This project is not affiliated with GoldenDict, ABBYY Lingvo, Apresyan,
Smirnitsky, StarDict, traduko.lib.ru, or any dictionary publisher.

## Repository layout

- `shared/` contains common dictionary engine, storage, search, and state packages.
- `composeApp/` contains shared Compose UI placeholders and platform entry points.
- `iosApp/` contains the SwiftUI host application placeholder.
- `test-dictionaries/` contains artificial test fixtures only.

## Build requirements

Use the Gradle Wrapper from this repository for all Gradle commands:

```bash
./gradlew --version
```

Common Gradle checks do not require opening Android Studio or Xcode:

```bash
./gradlew tasks
./gradlew :shared:verifySqlDelightMigration
./gradlew :shared:metadataCommonMainClasses
./gradlew :composeApp:metadataCommonMainClasses
```

Android builds require a local Android SDK. Install Android Studio or the Android command line tools, then make the SDK available with either `ANDROID_HOME`:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

or a local `local.properties` file in the repository root:

```properties
sdk.dir=/Users/your-user/Library/Android/sdk
```

`local.properties` is machine-specific and must not be committed.

After the Android SDK is configured, build the Android debug app with:

```bash
./gradlew :composeApp:assembleDebug
```

A full `./gradlew build` may fail on a new machine until the Android SDK is configured, because the Android Gradle Plugin needs `ANDROID_HOME` or `local.properties` to resolve platform tools.

iOS builds require full Xcode, not only Apple Command Line Tools. If `xcodebuild` reports that the active developer directory is `/Library/Developer/CommandLineTools`, install/open Xcode and select it:

```bash
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
```

## License

Source code: Apache License 2.0.
Sample test dictionaries: CC0-1.0, unless stated otherwise.

## Verification

Stage 1 verification commands:

```bash
./gradlew --version
./gradlew tasks
./gradlew :shared:verifySqlDelightMigration
./gradlew :shared:checkKotlinGradlePluginConfigurationErrors
./gradlew :composeApp:checkKotlinGradlePluginConfigurationErrors
./gradlew :shared:metadataCommonMainClasses
./gradlew :composeApp:metadataCommonMainClasses
```

Equivalent grouped checks:

```bash
./gradlew :shared:verifySqlDelightMigration :shared:checkKotlinGradlePluginConfigurationErrors :composeApp:checkKotlinGradlePluginConfigurationErrors
./gradlew :shared:metadataCommonMainClasses :composeApp:metadataCommonMainClasses
```
