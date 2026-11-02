# Reference Canvas

A native Android reference workspace: collect any file or link onto **boards**, arrange them on an **infinite canvas** or browse them as a **gallery**, annotate images, organize with folders and tags, search everything offline, and keep **verified local backups**. No account, no cloud, no analytics, no Internet permission.

## Honest status

All six build stages are written. **None of it has been compiled, run, or tested on a device**, because the environment it was written in had no Android SDK, Gradle or network. Treat the first CI run as the first real build: expect compile errors to fix. Everything below describes what the code is designed to do, and the checklist at the end separates "implemented" from "verified".

## Features

- **Boards**: create, rename, duplicate, favorite, pin, nest in folders, search. No limit on boards, references, tags or folders.
- **Import**: Photo Picker, file picker (any file type), camera, Share sheet, clipboard, and web links (stored as text, never fetched). Files are streamed into app storage with a checksum, moved in atomically, cancellable, reported per item, with retry; interrupted imports are cleaned up on next launch.
- **Gallery**: paged grid, sorting, density, multi-select, batch favorite / tag / folder / add to board.
- **Infinite canvas**: pan, pinch-zoom (0.002–16×), drag cards (single or group), long-press to select, bring to front / send back, duplicate, align, grid arrange, nudge buttons, undo/redo, fit / reset, grid and snap, saved viewport, keyboard support. Only cards near the screen are drawn.
- **Viewer**: zoomable images, swipe between items, details, share, open with; video (Media3), PDF (Android renderer), text, and a fallback card for anything else. Missing files show an error with retry.
- **Annotations** (images): pen, marker, eraser, line, arrow, rectangle, ellipse, text, select/delete, colors, widths, undo/redo, instant save, show/hide, export a flattened copy (save to board, share, or save to a chosen location). The original file is never modified.
- **Organize & find**: tags, nested folders, offline search over titles, file names, descriptions, link addresses, tags and board names with type/favorite/tag/board/folder/date filters, sorts, recent searches.
- **Trash**: deletes are recoverable for 30 days; restore, delete forever, empty.
- **Backup & restore**: one verified archive in a folder you choose (details below). Manual or scheduled.
- **Settings**: accent color, theme, defaults, backup, storage usage, cache clearing, unused-file cleanup, trash, privacy statement, version, shareable diagnostic log.

## Architecture

MVVM with unidirectional state. Compose screens render state and send events to `@HiltViewModel`s; repositories wrap Room DAOs, DataStore and the file store; pure logic lives in `domain/` and `core/backup` and is unit-tested without Android.

```
app/src/main/java/app/referencecanvas/
  core/      database (Room), datastore, design (theme), di, filesystem, backup, util
  data/      repository, importer, exporter
  domain/    model, canvas (math), annotation (format, geometry, rendering primitives)
  feature/   home boards canvas viewer annotate search tags settings trash share favorites
  ui/        shared components, navigation
```

Key choices: Room with an exported schema and explicit migrations (never destructive); `Double` canvas coordinates clamped to ±10,000,000; Paging 3 for large lists; Coil for decode-to-size thumbnails; Media3 for video; kotlinx.serialization for annotation and backup formats; WorkManager for scheduled backup.

## Stack

Kotlin 2.0.21 · Compose BOM 2024.12.01 (Material 3) · Room 2.6.1 · Paging 3.3.5 · DataStore 1.1.1 · Hilt 2.52 · Navigation 2.8.5 · Coil 2.7.0 · Media3 1.5.1 · WorkManager 2.10.0 · kotlinx.serialization 1.7.3 · AGP 8.7.3 · Gradle 8.9 · JDK 17 · minSdk 26 · target/compile SDK 35. These are a known-compatible set, not the newest; bump them deliberately in `gradle/libs.versions.toml`.

## Build and test

The Gradle wrapper JAR is binary and couldn't be generated here. Once, locally:

```
gradle wrapper --gradle-version 8.9      # then commit gradlew, gradlew.bat, gradle/wrapper/*
```

```
./gradlew :app:testDebugUnitTest         # unit tests (JVM)
./gradlew :app:lintDebug                 # lint (errors fail the build)
./gradlew :app:assembleDebug             # app/build/outputs/apk/debug/
./gradlew :app:connectedDebugAndroidTest # database, backup round-trip and migration tests (emulator/device)
```

After the first successful build, **commit `app/schemas/`** (the exported Room schema); `MigrationTest` needs it.

If you installed an early build, uninstall it first: the version-1 schema gained tables during development before any release.

## GitHub Actions

- `.github/workflows/android-ci.yml`: on pushes to `main`, pull requests and manual runs: validate → compile → unit tests → lint → assemble debug → upload reports and the debug APK. A manual-only emulator job runs the instrumented tests. Change `branches: [main]` if your default branch differs. Actions are pinned by major version tag; pin by commit SHA if your policy requires it.
- `.github/workflows/release.yml`: push a tag such as `v1.2.3`. It runs tests and lint, then builds a signed APK and AAB, **verifies the signature with `apksigner`**, writes SHA-256 sums, and publishes a GitHub Release. A tag build without signing secrets fails rather than producing something unsigned. A manual run can produce an `-UNSIGNED` test artifact only if you tick `allow_unsigned`; it is verified to really be unsigned and is never published.

Release signing secrets (repository settings): `RC_KEYSTORE_BASE64` (`base64 -w0 release.jks`), `RC_KEYSTORE_PASSWORD`, `RC_KEY_ALIAS`, `RC_KEY_PASSWORD`. The keystore is decoded to a temp file for the build and deleted afterwards. Nothing signing-related is committed; `keystore.properties` and `*.jks` are git-ignored. For a local signed build, create `keystore.properties` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

## Storage and privacy

- The manifest declares no permissions of its own and no Internet permission. (WorkManager's library adds `ACCESS_NETWORK_STATE`, `WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED` for scheduling; none give network access.)
- Files live in app-private storage; the app never reads your gallery or storage broadly, only items you pick, share or paste. `allowBackup` is off so Android doesn't silently copy data to cloud backup.
- Logs and diagnostics never contain file names, titles, tags or content.
- **"Unlimited" means no artificial caps**: no limit on boards, references, tags, folders or imports. Real limits are free storage, memory and time. The Photo Picker itself has an OS-defined per-selection cap.

## Backup and restore

**Where**: pick a folder once (Settings ▸ Backup) via Android's folder picker. The app can only write there, and never talks to any cloud service; if that folder is synced by something you run, that's your choice. A backup stored only on the phone does not protect you against uninstalling the app or losing the device, and the app says so.

**Format** (`ReferenceCanvas-backup-<UTC time>.rcbackup.zip`, format version 1):

```
data/<table>.jsonl        one JSON row per line for each database table
data/prefs.json           appearance and default preferences
files/references/<id>.<ext>   managed files, byte for byte
entries.jsonl             path, size and SHA-256 of every entry above
manifest.json             LAST: format/app/schema versions, counts, SHA-256 of entries.jsonl
```

The archive is streamed (no giant in-memory buffer). The manifest is written last, so an archive without one is, by definition, incomplete.

**Making one**: a short database transaction snapshots all tables to temp files; the archive is written to the folder; then the finished file is **read back and fully validated** (every checksum, every count). Only if that passes is it reported as a success and are backups older than the newest three removed. If anything fails or is cancelled, the partial file is deleted and the previous backups are untouched. Last attempt, outcome and last verified success are shown in Settings.

**Restoring**: (1) pick a backup; the whole archive is read and verified, changing nothing; (2) a summary is shown and you must confirm; (3) files are extracted and the database is replaced inside **one transaction** that rolls back on any problem, including the archive differing from what you confirmed. Unrecognized, newer-format or tampered archives (including path-traversal entries) are refused. Backup destination and schedule are device-specific and are not restored.

**Scheduling**: Off / Daily / Weekly via WorkManager, with low-battery and low-storage constraints. Android decides when it actually runs; a very large library may exceed what one background run can do, and failures are shown in Settings (no notifications, because the app asks for no notification permission). "Back up now" runs in the app with progress and cancel.

## Known limitations

- **Unverified**: nothing has been built or run (see top). Gesture feel, performance with thousands of cards, PDF/video behavior, and all emulator tests are untested.
- Canvas cards cannot be resized or rotated; no marquee selection. Annotations can't be moved or edited after creation (delete and redraw); annotation is gesture-based with no keyboard-only path; only static images can be annotated.
- Search is `LIKE`-based (fine for thousands of items, not tuned for hundreds of thousands); no FTS, no image-content search (by design). No saved filter presets.
- Canvas card metadata for a whole board is held in memory (a few hundred bytes each).
- PDF: fit-to-width scrolling only. Text viewer shows the first 512 KB.
- Restore only handles backups from this schema version or older formats the app knows (currently just v1); a newer backup is refused rather than guessed at.
- Backup/restore of very large libraries has not been timed; restore reads the archive twice (verify, then apply).
- External-URI ("don't copy") references are modelled in the database but not offered in the UI. No thumbnails disk cache (decode-on-demand with Coil's memory cache). Dark theme is primary; light theme exists but is less polished. Reduced-motion: the app uses no custom animations.
- Accessibility: labels, roles, selection state and large touch targets are provided on interactive controls, with button alternatives to the canvas gestures; TalkBack has not actually been run.

## Acceptance checklist

"Implemented" means the code exists and was reviewed by reading it. **No box means verified by running it**, because nothing has been run.

| Area | Status |
|---|---|
| Launch into a usable interface; create/rename/delete boards | Implemented |
| Import multiple references; no item caps | Implemented |
| Folders and tags; references on multiple boards | Implemented |
| Canvas and gallery; positions and viewport persist | Implemented |
| Full-screen viewer; previews for images, video, PDF, text; fallback card | Implemented |
| Annotations: create, save, export | Implemented |
| Search and filtering; favorites; sorting | Implemented |
| Offline operation (no network permission) | Implemented |
| Import recoverable; missing files detected; storage constraints stated | Implemented |
| Backups can be created and restored; validation prevents unsafe restores | Implemented, with unit tests of the format and emulator tests of the round trip |
| Room persistence, schema export, migration plan and test scaffold | Implemented |
| Efficient loading (paging, viewport culling); off-main-thread file work | Implemented |
| Unit tests; lint configured; CI workflows; release signing handled securely | Written; **not yet run** |
| Debug APK / signed release artifacts | **Not produced** (needs a build environment) |
