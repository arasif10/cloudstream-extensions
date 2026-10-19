# CloudStream plugins

CloudStream 3 plugins for [arasif10](https://github.com/arasif10).

## Install in CloudStream

Add this repository URL in **Settings → Extensions → Add repository**:

```
https://raw.githubusercontent.com/arasif10/cloudstream-extensions/master/repo.json
```

The list is built by GitHub Actions: every push to `master` compiles the plugins
and publishes `plugins.json` plus the `.cs3` files to the `builds` branch.

## Plugins

| Plugin | Language | Content |
| --- | --- | --- |
| ReAnime | en | Anime sub/dub from Re:ANIME (AniList metadata, FlixCloud streams) |
| DesiDubAnime | hi | Hindi/Tamil/Telugu/English dubs from DesiDubAnime |
| MyAnimes | hi | Hindi/Tamil/Telugu/English anime and movies from MyAnimes, one stream per audio track |

## Add another provider

1. Create `ProviderName/build.gradle.kts` (copy an existing one and change the
   description, `tvTypes` and `iconUrl`).
2. Put a `MainAPI` subclass and its `BasePlugin` entry point under
   `ProviderName/src/main/kotlin/recloudstream/`.
3. Push; the workflow picks the folder up automatically, builds
   `ProviderName.cs3` and adds it to `plugins.json`.

## Build locally

```sh
./gradlew make makePluginsJson
```

Output: `build/plugins.json` and `*/build/*.cs3`. Needs JDK 17 and the Android
SDK with platform 35.
