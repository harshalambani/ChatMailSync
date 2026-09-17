# Chat Mail Sync — Kotlin-core / F-Droid plan and Windows finish-off audit

*Read-only audit, 2026-09-17. Repo `harshalambani/ChatMailSync`, branch `main` at 56fc41f (v2.2.0, versionCode 42).*

Read-only audit of the local checkout plus what GitHub exposes publicly. Nothing was written, built, or pushed. One access limit up front: `gh` is not installed on the local machine, and from the cloud side the GitHub API is blocked and PR/issue search pages are robots-blocked, so branch protection, rulesets, open issues/PRs and Dependabot config could not be read. Those are listed in H with the exact commands to run.

---

## A. Executive summary and stale items in the brief

The port is feasible and the boundary is cleaner than most: Kotlin reaches Python through 28 call sites in 8 files, all returning dicts/lists of primitives, and the only third-party Python dependency is `python-dateutil`, used in exactly one function (`parser._parse_timestamp`, and only for the time-of-day half). The hard parts are not the IMAP client or MIME — they are byte-exact parity on three things that existing installs depend on: `compute_message_hash` inputs (especially the ISO timestamp string), the `sync_state.db` schema/`user_version` dance, and the `.cmsbackup` bundle format. Get those wrong and the app re-mails every chat, which is the failure it exists to prevent.

The Windows retirement is essentially done: PR #69 (`retire-windows` → main, merged 2026-09-16, branch deleted) removed the desktop code, PortableApps packaging and their tests. What remains is a short list of dead code, dead-comment references, a stale repo description/topics, and the Python CI jobs that only exist because the core is still Python.

Items in the brief that are stale or wrong:

1. The "Windows app ended at v2.1.5" comment is in `android/app/build.gradle.kts:36-42`, not the top-level `android/build.gradle.kts`. Tag `windows-final` exists (annotated, on db46f85 = v2.1.5).
2. There is no `android/gradle/libs.versions.toml`. Versions are inline: AGP 9.2.0, Kotlin 2.4.0, compose plugin 2.4.0, Chaquopy 17.0.0 (`android/build.gradle.kts:2-5`), Gradle 9.4.1 wrapper (no `distributionSha256Sum`).
3. Facade: four public functions were added since the last scan — `progress_state`, `get_pending_self_sender_banner`, `clear_self_sender_banner`, `list_chat_senders`. More importantly, `android_api` is not the only boundary: Kotlin calls `src.config` (`set_root`, `retired_provider_landing`) and `src.mail_client` (`build_imap_transport`, `check_connection`, `format_connection_result`, `connection_stage_plan`, `transport.close`) directly.
4. Line counts have moved: mail_client 2286 (not ~2151), sync_manager 974, state 852, android_api 751, migration 639, config 448, progress 216; parser 468, html_renderer 460, self_sender 205, mail_index 197, media_extractor 137, app_version 91. Total 7724.
5. Tests: 374 `def test_` across 18 pytest files (not 412; PR #69 reported 383 *passed* because of parametrize expansions). JUnit: 157 across 20 files — matches.
6. `src/app_version.py` is still imported by `src/mail_index.py:41` and its value lands in every emailed index JSON (`"app_version": app_version()` at mail_index.py:108). On Android `sys.frozen` is never set, so every index attachment says `"development build"`. That is a live bug, not just a leftover.
7. Provider presets shipped: gmail, yahoo, icloud, aol, fastmail, custom (`config.py:157-164`); `outlook` is retired → lands on `custom` (`config.py:175-184`).
8. `retire-windows` is merged and deleted (PR #69). Part 2a is answered.
9. FrozenIdentifiersTest freezes four things (prefs name, keystore alias, python root, applicationId/namespace). PR #69's commit message says the `watch_folder` identifiers are "frozen by FrozenIdentifiersTest.kt" — they are not; no test guards the WorkManager names.
10. No Kotlin test reads Python files. `RestorableSettingsTest`, `FrozenIdentifiersTest`, `CutoffDateTest`, `RunSetupAgainTest`, `ChatsFilterChipsAndTopBarTest` scan *Kotlin* sources and `app/build.gradle.kts`. The dependency runs the other way: four pytest files read Kotlin/docs (`test_faq_parity.py` → HelpScreen.kt + user-guide.md; `test_privacy_parity.py` → PrivacyScreen.kt + privacy.html; `test_chat_detail.py` → ChatsListScreen.kt; `test_android_api.py:445` → MainActivity.kt). Those guards vanish with pytest and must be re-homed in JUnit.
11. Backup `imap_host`: Python excludes it from `_PORTABLE_SETTINGS` (migration.py:81-99) but *re-derives* it from the provider preset on import (`_with_derived_host`, :132-164), so `settings_json` handed to Kotlin does carry `imap_host` for the five presets and omits it for `custom`. Kotlin `applySettings` (Migration.kt:305-334) applies it if present. So the two sides agree; the nuance is that the bundle file never contains `imap_host`.
12. No Obtainium asset-filter regex exists anywhere in the repo; docs tell users to paste the repo URL and Obtainium picks the single `.apk` asset.
13. `dependenciesInfo {}` is absent from `app/build.gradle.kts` → AGP defaults `includeInApk = true`, so the Google-encrypted dependency blob is in the APK; F-Droid's scanner will flag it.
14. GitHub repo description still reads "…Windows + Android." and topics include `windows`, `python`, `gmail`.
15. fastlane `short_description.txt` is 87 characters; F-Droid caps summary at 80. Changelogs exist for 31–37 and 42; 38–41 (2.1.2–2.1.5) are missing.
16. `.github/workflows/tests.yml:84-95` comments reference `OauthVisibilityTest.kt` (deleted) and a `cryptography` Dependabot bump; the lock now contains only `python-dateutil` + `six`.
17. v2.2.0 APK asset: `ChatMailSync-2.2.0.apk`, 20.5 MB (2.1.4 was 21.4 MB), 3 assets total (APK + two source archives).

---

## B. Current-state inventory

**Android app (confirmed).** `applicationId`/`namespace` `com.chatmailsync.app`, minSdk 24, compileSdk/targetSdk 36, `abiFilters += "arm64-v8a"` at defaultConfig (`app/build.gradle.kts:29-59`). Dependencies: compose-bom 2026.06.01, material3, material-icons-core, activity-compose 1.11.0, core-ktx 1.16.0, lifecycle-runtime-ktx 2.9.0, work-runtime-ktx 2.10.0, navigation-compose 2.9.0, documentfile 1.0.1, junit 4.13.2 test-only (`:157-179`). No `.java` files; 14,221 lines of Kotlin (main + test). R8 minify + shrinkResources on release; one keep rule for WorkManager (`proguard-rules.pro:11`).

**Chaquopy.** `com.chaquo.python` 17.0.0, `version = "3.13"`, single pip pin `python-dateutil==2.9.0.post0` (`:144-155`). `syncPythonCore` is a Gradle `Sync` task mirroring repo-root `src/**/*.py` into `android/app/src/main/python/src/` before `preBuild` (`:115-142`). Repos: `google()`, `mavenCentral()`, `gradlePluginPortal()` and `maven("https://chaquo.com/maven")` in both plugin and dependency resolution (`settings.gradle.kts:14,23`) — the latter is the one non-standard repository and goes away with Chaquopy.

**Module inventory and import graph** (only intra-`src` edges; stdlib omitted):

| Module | Lines | Imports from src | Stdlib/3rd-party of note |
|---|---|---|---|
| android_api | 751 | config, migration, self_sender, mail_client, parser, state, progress, sync_manager | json, threading, platform |
| sync_manager | 974 | config, mail_client, parser, self_sender, state | shutil, re, logging |
| mail_client | 2286 | config, html_renderer, mail_index, media_extractor, parser | imaplib, ssl, socket, email.mime.*, base64, uuid, subprocess, getpass, queue |
| migration | 639 | state, config | sqlite3, zipfile, json, uuid, tempfile, shutil |
| state | 852 | config | sqlite3, hashlib |
| html_renderer | 460 | parser, self_sender | hashlib(md5), mimetypes, uuid, html |
| parser | 468 | config | re, zipfile, **dateutil.parser** |
| config | 448 | — | os |
| progress | 216 | — | dataclasses |
| self_sender | 205 | — | — |
| mail_index | 197 | app_version, state | json, email.mime.base |
| media_extractor | 137 | config | zipfile, mimetypes |
| app_version | 91 | — | re, sys |

Dependency order (leaves first): config → {progress, self_sender, app_version} → parser → state → {html_renderer, media_extractor, mail_index} → mail_client → migration → sync_manager → android_api.

**Facade + call-site table.** Every Kotlin → Python call. "PyObject dict" means Kotlin reads fields via `.callAttr("get", key).toString()`; booleans are compared as `"True"` or via `.toBoolean()`.

| Kotlin file:line | Module.function | Args in | Return read as |
|---|---|---|---|
| ChatMailApplication.kt:22-24 | config.set_root(path) | str | ignored |
| ChatDetailScreen.kt:78-80 | android_api.get_cutoff(chat_id) | str | dict → `cutoff_date` (str/None) |
| ChatDetailScreen.kt:186-187, 201-202 | set_cutoff(chat_id, text) | str, str ("" clears) | dict (ok, chat_id, cutoff_date, error) |
| ChatDetailScreen.kt:243-247 | reset_preview(chat_id) | str | dict → archived_count (int), mailbox_folder (str) |
| ChatDetailScreen.kt:280-285 | reset(chat_id, true) | str, bool | dict → ok, file_restored, error |
| ChatDetailScreen.kt:388-391 | delete_chat(chat_id) | str | dict → ok, error |
| ChatsListScreen.kt:86-97 | status() | — | list[dict] → chat_id, display_name, has_thread, gmail_thread_id, last_run_status, messages_synced, last_run_at, source_filename |
| MainActivity.kt:579-585 | imap_providers() | — | list[dict] → key, label, host, port |
| MainActivity.kt:597-598 | config.retired_provider_landing(key) | str | str |
| MainActivity.kt:603-607 | mail_client.connection_stage_plan() | — | list[dict] → name, label |
| MainActivity.kt:722-731, 802-812, 851-860 | mail_client.check_connection(host, port, email, password[, listener]) | str, int, str, str, Kotlin `StageListener` object (duck-typed `onStage(name,label,ok)` callback, Python→Kotlin) | dict → ok; then `format_connection_result(dict)` → str |
| MainActivity.kt:897 | get_self_sender() | — | dict → name, source, summary, detail, override, learned |
| MainActivity.kt:909-910 | get_pending_self_sender_banner() | — | str/None |
| MainActivity.kt:915 | clear_self_sender_banner() | — | None |
| MainActivity.kt:921-922 | set_self_sender(name) | str/None | dict (same as get_self_sender) |
| MainActivity.kt:932-937 | list_chat_senders() | — | list[dict] → sender, msg_count |
| MainActivity.kt:948-951 | list_inbox() | — | list[dict] → name, size_bytes |
| MainActivity.kt:957 | remove_from_inbox(name) | str | dict (ignored) |
| MainActivity.kt:976-978, 1716-1717, 1759-1760 | preview_text(path, cutoff) | str, str | str (multi-line) |
| Migration.kt:113-133 | export_backup(dest, settings_json, version) | str, JSON str, str | dict → ok, error, counts{chats,hashes} |
| Migration.kt:156-182 | import_backup(path) | str | dict → ok, error, already_imported, settings_json, chats_added, hashes_added, cutoffs_added, created_at_epoch_ms |
| Migration.kt:223-224 | imap_providers() | — | key→label map |
| Migration.kt:285-288 | describe_backup(path) | str | dict → ok, created_at, chats |
| SyncLogScreen.kt:85-88 | sync_log(days) | int | list[dict] (sync_runs rows + `uneventful`) |
| SyncLogScreen.kt:129-131 | sync_status(days) | int | dict |
| SyncProgressScreen.kt:175 | request_stop() | — | None |
| SyncWorker.kt:129 | progress_state() | — | dict (phase, chat, fraction, text, log) |
| SyncWorker.kt:204-205 | mail_client.build_imap_transport(host, port, email, password) | str, int, str, str | opaque PyObject, later `.callAttr("close")` (:227) |
| SyncWorker.kt:220-222, 327-355 | sync(transport, chunk_size, dry_run, chat_filter, None, trigger, cutoff) | PyObject/None, str, bool, str/None, None, str, str | dict → files_found/synced/skipped/failed, messages_parsed/synced/skipped/cutoff, chats_recovered, stopped, errors[], media_omitted[] |

Never called from Kotlin: `ping`; `preview`/`format_preview` only via `preview_text`; `check_connection_text` unused on Android. Also note `CutoffDateTest.kt:197-206` asserts the literal string `"sync", transport, chunkSize, dryRun, chatFilter, null, trigger, cutoff,` exists in SyncWorker.kt — it will fail the moment the Chaquopy call is replaced and must be rewritten in the same PR.

**Tests.** pytest (374 defs): test_android_api 64, test_state 38, test_connection_check 34, test_imap_transport 31, test_migration 29, test_config 26, test_sync_manager 25, test_parser 21, test_self_sender 21, test_progress 20, test_html_renderer 14, test_mail_index 14, test_app_version 11, test_progress_output 9, test_mail_transport 8, test_privacy_parity 5, test_faq_parity 3, test_chat_detail 1. Fixtures: 3 files, 16 lines total (`android_export.txt`, `ios_export.txt`, `ambiguous_dates.txt`) — far too small for shadow verification; `tools/make_demo_exports.py` generates the fictional demo set and is the right corpus seed.

Module → pytest map: android_api → test_android_api (+ test_chat_detail); state → test_state; mail_client → test_connection_check, test_imap_transport, test_mail_transport, test_progress_output; migration → test_migration; config → test_config; sync_manager → test_sync_manager; parser → test_parser; self_sender → test_self_sender; progress → test_progress; html_renderer → test_html_renderer (also the only file touching MediaExtractor — media_extractor has no dedicated tests); mail_index → test_mail_index; app_version → test_app_version. test_faq_parity / test_privacy_parity test docs↔Kotlin, not a module.

JUnit (157): FirstRunTest 37, AppPasswordTest 17, MigrationTest 16, CutoffDateTest 15, RestorableSettingsTest 8, ProviderPickerTest 6, ConnectionCheckHelpersTest 6, BackgroundHealthTest 6, TabForRouteTest 5, SettingsRowsTest 5, FrozenIdentifiersTest 5, ExportFilesTest 5, ConnectionStatusTest 5, LaunchScanTest 4, ShowCustomServerFieldsTest 3, SelfSenderLearnedBannerTest 3, HelpLinkTest 3, ChatsListScreenTest 3, ChatsFilterChipsAndTopBarTest 3, RunSetupAgainTest 2. All plain JVM, no Robolectric (`app/build.gradle.kts:173-178`). `ConnectionStatusTest.kt:11` still cites `tests/test_connection_status.py`, deleted in #69.

**IMAP surface actually used** (mail_client.py): `imaplib.IMAP4_SSL(host, port, ssl_context=imap_tls_context(), timeout)` with TLS ≥1.2 (:632-637, :552), `LOGIN` (:655), capabilities captured at greeting for RFC 7889 `APPENDLIMIT` (:722-745), `LIST "" *` (:855) with delimiter learning and modified-UTF-7 encode/decode (:374-447), `CREATE` treating "already exists" as success (:888), best-effort `SUBSCRIBE` (:898), `APPEND` with `(\Seen)` flag, INTERNALDATE derived from the message Date, and `APPENDUID` parsed from the response (:929-935, :501-514), `LOGOUT` (:750). No `SELECT`, no fetch, no delete. One transparent reconnect on `IMAP4.abort` (:757-775). Size policy: wire-size budgets from `PROVIDER_MAX_MESSAGE_BYTES` (gmail/yahoo/aol 25,000,000; icloud 20,000,000; fastmail 70,000,000; default 25,000,000) × `MESSAGE_SIZE_SAFETY_FACTOR 0.90`, measured on *encoded* bytes via `html_renderer.encoded_part_bytes` (config.py:376-425). OAuth is gone: no google-auth/googleapiclient anywhere in src, tests, gradle or requirements; what remains is recognition of a legacy `gmail_oauth` backend string and a `token.json` path (config.py:97-135, AppPrefs.kt:71,196-200), and `docs/RESTORING-OAUTH.md`.

**MIME shape** (`_build_html_mime_message`, mail_client.py:1641-1710): `multipart/mixed` → [`multipart/related` → `text/html; charset=utf-8` (base64) + inline images with `Content-ID`/`Content-Disposition: inline`] + attachments (base64, RFC 2231 filename via `add_header`) + the index JSON attachment (mail_index.py:171-174) with headers `X-ChatMailSync-Version/Chat/Count/Index`. Top headers: Subject, From (`_format_sender`), `To: me`, `Message-ID: <wa-sync-<uuid hex>@local>`, `Date` as `%a, %d %b %Y %H:%M:%S +0000`, `In-Reply-To`/`References` for threading. Plain-text variant at :1598-1631.

**Frozen identifiers (exact values, file:line).** Changing any of these on update breaks installed users.

| Identifier | Value | Location |
|---|---|---|
| SharedPreferences file | `chatmailsync_prefs` | AppPrefs.kt:31 and SecretStore.kt:57 |
| Keystore alias | `chatmailsync_imap_key` | SecretStore.kt:54 (AES/GCM, `AndroidKeyStore`) |
| Data root | `File(context.filesDir, "chatmailsync")` | ChatMailApplication.kt:44; `data/inbox` created at :47 |
| Python-side layout under root | `data/`, `data/inbox`, `data/processed`, `data/sync_state.db` | config.py:48-51; migration.py:168-179 duplicates `root/data/sync_state.db` |
| applicationId / namespace | `com.chatmailsync.app` | app/build.gradle.kts:29,34 |
| FileProvider authority | `com.chatmailsync.app.fileprovider` | AndroidManifest.xml:57 |
| WorkManager unique names | `manual_sync` (SyncWorker.kt:64); `watch_folder`, `watch_folder_once`, `watch_folder_auto_sync` (WatchFolderWorker.kt:39-46) | periodic work is enqueued under `watch_folder` (:69-70) — a renamed periodic name leaves the old one scheduled forever |
| Worker class names | `com.chatmailsync.app.SyncWorker`, `com.chatmailsync.app.WatchFolderWorker` | WorkManager persists class names in its DB; renaming strands queued work |
| Notification channel | `watch_folder_channel` | WatchFolderWorker.kt:47 |
| Tag `"watch_folder"` | not used as a tag — it is the unique-work name; no `addTag` anywhere | — |
| AppPrefs keys | `watched_folder_uri, auto_watch_enabled, imported_doc_ids, theme_mode, watch_interval_minutes, synced_file_policy, connected_email, chunk_size, dry_run_default, mail_backend, oauth_removed_notice_shown, imap_provider, imap_host, imap_port, imap_email, imap_password_secret, pending_synced_files, last_connection_ok, last_connection_at, last_backup_at, cutoff_date, first_run_done` | AppPrefs.kt:32-53; plus `imap_password_lost` (SecretStore.kt:68); secret blob format `base64(iv):base64(ciphertext)` (SecretStore.kt:115-129) |
| Backend value | `imap` (legacy `gmail_oauth`) | AppPrefs.kt:71,77; config.py:97 |
| app_state keys in DB | `self_sender_override`, `self_sender_learned`, `self_sender_learned_pending` | state.py:204-214 |
| Backup bundle | suffix `.cmsbackup`; ZIP entries `manifest.json`, `settings.json`, `sync_state.db`; manifest keys `schema_version` (=1), `bundle_id`, `created_at` (ISO seconds, local), `app_version`, `counts{chats,runs,hashes,cutoffs,…}`; settings allow-list `chunk_size, watch_interval_minutes, synced_file_policy, theme_mode, dry_run_default, mail_backend, imap_provider, imap_port, imap_email` | migration.py:57-99, 199-250; Migration.kt:28, 51-61, 305-334, 353-364 |
| Message hash | `sha256(chat_id + "\x00" + timestamp_iso + "\x00" + sender + "\x00" + body)` UTF-8 hex | state.py:282-285; `timestamp_iso = isoformat(timespec="seconds")` parser.py:51 |
| Chat id derivation | strip `whatsapp chat with `, strip trailing ` (n)`, lowercase, drop non `[a-z0-9\s]`, spaces→`_` | parser.py:213-244 |
| Message-ID / index | `<wa-sync-<hex>@local>`; index `INDEX_SCHEMA = 1`, `X-ChatMailSync-*` headers | mail_client.py:1633-1634; mail_index.py:50,63-66 |

**state.py DDL, verbatim** (state.py:22-116; `--` SQL comments inside `chats` and `chat_cutoffs` omitted for length):

```sql
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS chats (
    chat_id           TEXT PRIMARY KEY,
    display_name      TEXT NOT NULL,
    gmail_thread_id   TEXT,
    gmail_label_id    TEXT,
    anchor_message_id TEXT,
    source_filename   TEXT NOT NULL,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sync_runs (
    run_id           INTEGER PRIMARY KEY AUTOINCREMENT,
    chat_id          TEXT    NOT NULL REFERENCES chats(chat_id),
    status           TEXT    NOT NULL CHECK(status IN ('pending', 'complete', 'failed')),
    trigger          TEXT    NOT NULL DEFAULT 'manual',
    last_synced_ts   TEXT,
    last_synced_hash TEXT,
    messages_parsed  INTEGER NOT NULL DEFAULT 0,
    messages_synced  INTEGER NOT NULL DEFAULT 0,
    messages_skipped INTEGER NOT NULL DEFAULT 0,
    messages_cutoff  INTEGER NOT NULL DEFAULT 0,
    error_message    TEXT,
    started_at       TEXT    NOT NULL,
    completed_at     TEXT
);

CREATE TABLE IF NOT EXISTS message_hashes (
    hash       TEXT    PRIMARY KEY,
    chat_id    TEXT    NOT NULL REFERENCES chats(chat_id),
    message_ts TEXT    NOT NULL,
    run_id     INTEGER NOT NULL REFERENCES sync_runs(run_id)
);

CREATE TABLE IF NOT EXISTS chat_cutoffs (
    chat_id   TEXT PRIMARY KEY,
    cutoff_ts TEXT NOT NULL,          -- ISO 8601, local midnight
    set_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_senders (
    chat_id    TEXT NOT NULL,
    sender     TEXT NOT NULL,
    first_seen TEXT,
    last_seen  TEXT,
    msg_count  INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (chat_id, sender)
);

CREATE TABLE IF NOT EXISTS app_state (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_message_hashes_chat  ON message_hashes(chat_id);
CREATE INDEX IF NOT EXISTS idx_sync_runs_chat       ON sync_runs(chat_id);
CREATE INDEX IF NOT EXISTS idx_sync_runs_status     ON sync_runs(status);
```

Migration logic (state.py:164-197): after `executescript(_DDL)`, try `ALTER TABLE sync_runs ADD COLUMN trigger TEXT NOT NULL DEFAULT 'manual'` and `ALTER TABLE sync_runs ADD COLUMN messages_cutoff INTEGER NOT NULL DEFAULT 0`, each swallowing `OperationalError`; then `PRAGMA user_version` — if `< 1` run `_dedupe_sync_runs` (natural-key sweep over `RUN_NATURAL_KEY` at :129-133), and if `< _SCHEMA_VERSION` (= 2) set `PRAGMA user_version = 2`. A seventh table, `imported_bundles (bundle_id TEXT PRIMARY KEY, imported_at TEXT NOT NULL, app_version TEXT)`, is created by migration.py:432-450, not by state.py. Python opens a fresh `sqlite3.connect` per call with `row_factory = Row` and commit/rollback per context (:147-157).

**Room vs raw SQLiteOpenHelper — recommendation: raw `SQLiteOpenHelper`.** The schema must remain byte-identical to what Python created on ~40 versionCodes of installs: `AUTOINCREMENT`, `CHECK(...)`, `REFERENCES` without `ON DELETE`, composite PK, WAL, `user_version` already at 2. Room owns `user_version` as its own schema version and validates tables against generated `TableInfo` on open; an existing DB with `user_version = 2` and no `room_master_table` forces you to declare Room version 2 and write a no-op "migration" plus exact entity definitions whose generated DDL must match, or Room throws on first open. It also drags in an annotation processor (KSP) and a schema-export step — all Maven Central, so not an F-Droid blocker, but it is pure risk for zero gain: the queries here are a few dozen hand-written statements that port 1:1. Raw `SQLiteOpenHelper` with the `_DDL` string copied verbatim, the same try/ALTER, the same `user_version` check (do *not* use `onUpgrade`; keep Python's version-number semantics), `setForeignKeyConstraintsEnabled(true)` and `enableWriteAheadLogging()` reproduces Python's behaviour exactly and lets `test_state.py` port line for line. One caveat for the backup snapshot: Python uses the sqlite backup API (`_snapshot_db`, migration.py:253); Android exposes no backup API and `VACUUM INTO` needs SQLite 3.27 — minSdk 24 ships 3.9 — so the Kotlin snapshot must be `PRAGMA wal_checkpoint(TRUNCATE)` + file copy under a write lock, which the parity tests should cover.

**F-Droid blockers.** Absent: Firebase, GMS/Play Services, Billing/Review/In-App-Update, Crashlytics, ML Kit, jitpack, local `.jar`/`.aar`, `fileTree`/`flatDir` — grep of every gradle file and proguard-rules is clean. Present: Chaquopy plugin + `https://chaquo.com/maven` repo (the blocker), no `dependenciesInfo { includeInApk = false; includeInBundle = false }`, no `distributionSha256Sum` in `gradle-wrapper.properties`, `gradle-wrapper.jar` committed (accepted by fdroidserver when it matches a known Gradle release hash; 9.4.1 must be one). Manifest permissions: INTERNET, POST_NOTIFICATIONS, FOREGROUND_SERVICE(_DATA_SYNC) (AndroidManifest.xml:5-8). The app's only network use is the user-chosen IMAP server plus `ACTION_VIEW` intents to open help/privacy URLs (UrlOpener.kt:28) — no anti-feature applies (presets naming Gmail/iCloud are user-selected servers, same as any mail client); `allowBackup="true"` with extraction rules is fine. Signing: release uses a local `keystore.properties` (gitignored; absent on CI). Without reproducible builds F-Droid signs with its own key: a user on the GitHub/Obtainium/GetApps key cannot update to the F-Droid build in place — it is uninstall → reinstall, which deletes `filesDir/chatmailsync` and the state DB unless they restore a `.cmsbackup` first.

**fastlane state.** `fastlane/metadata/android/en-US/`: `title.txt` ("Chat Mail Sync"), `short_description.txt` (87 chars — over F-Droid's 80), `full_description.txt` (2817 bytes, under 4000), `changelogs/{31,32,33,34,35,36,37,42}.txt` (42.txt is 483 bytes, under the 500 cap; 38–41 missing), `images/icon.png` 512×512, `images/phoneScreenshots/01…06` 1080×2340. No `featureGraphic.png` (optional), no other locales. Everything needed for a metadata MR is present except the summary length; F-Droid also requires the changelog file name to equal the versionCode being built (so `43.txt`/whatever 3.0.0 ships as).

---

## C. Windows-removal audit and finish-off checklist

**(a) Git/GitHub.** `retire-windows` → PR #69, merged 2026-09-16 (ac2afa4), head branch deleted; not present locally or on `origin`. Milestones: none. Labels: GitHub defaults plus `dependencies` and `python` (Dependabot's auto-labels) — nothing Windows-specific. Open issues/PRs could not be listed (see H). Stale remote branches unrelated to Windows: 14 squash-merged feature branches still on `origin` (app-password-ux, batch-6-2.2.0, batch-7-backup-screen, batch-7b-restore-flow, first-run, me-chat-strip, me-learned-banner, provider-order, settings-polish, settings-split, ci-test-workflow, quieter-sync-progress, show-app-version, b8-splash-and-storage-rename) and three local ones (`ci-test-workflow`, `wip/version-2.0.5-bump`, `b8-splash-and-storage-rename`).

**(b) Repo leftovers**, classified:

| Hit | Class |
|---|---|
| `src/app_version.py` (whole file; reads PortableApps `appinfo.ini`, `sys.frozen`) + `tests/test_app_version.py` (11 tests) + import at `mail_index.py:41`, use at `:108` | dead code that is *still executed* and emits "development build" on Android — replace with a version passed in from Kotlin (`BuildConfig.VERSION_NAME`) or drop the field; then delete file + tests |
| `src/mail_client.py:76-156` `_current_username`, `_restrict_acl`, `_restrict_auth_dir_acl`, `_restrict_file_acl` (icacls/subprocess/getpass) | dead code — no caller anywhere in src or tests |
| `src/mail_client.py:1844-1897` `_stderr_is_terminal`, `_print_progress` and calls at :2083, :2215; `tests/test_progress_output.py` (9 tests, PyInstaller `console=False` cases) | dead-on-Android console progress; goes with the port (never port it) |
| `src/config.py:12-31` (PyInstaller/PortableApps/CHATMAILSYNC_ROOT env fallback), `:52-63` `AUTH_DIR`, `LEGACY_TOKEN_FILE`, `IMAP_CREDENTIALS_FILE` ("NTFS ACL hardening" comment) | dead docs/code; `is_legacy_oauth_user` still reads `LEGACY_TOKEN_FILE` — keep the legacy detection semantics, drop the Windows framing |
| `requirements-dev.txt:1` "NOT bundled into the frozen exe" | dead docs |
| `.github/workflows/tests.yml:13-16, 84-95` (Windows 3.14 job history, `OauthVisibilityTest.kt`) | dead docs (comments only) |
| `.gitattributes:1-7, 19-20, 30` (`*.ps1 eol=crlf`, `*.exe binary`) | harmless; `gradlew.bat` still needs CRLF, so keep the mechanism, prune ps1/exe lines |
| `PrivacyScreen.kt:33,68-69,133`; `docs/privacy.html:127-128,226` (Windows DPAPI wording) | still-needed for now per PR #69 (covers 2.1.5 Windows users); `test_privacy_parity.py` pins the two copies equal — change both in one PR, bump `PRIVACY_LAST_UPDATED` |
| `AppPrefs.kt:25,212`, `SecretStore.kt:20`, `SyncProgressScreen.kt:198`, `progress.py:4`, `sync_manager.py:829`, `tests/test_android_api.py:311`, `test_chat_detail.py:3-4`, `test_faq_parity.py:7`, `test_privacy_parity.py:15-16`, `tools/render_icons.py:23-24` | past-tense historical comments — leave |
| `docs/RELEASING.md:53`, `docs/RESTORING-OAUTH.md:11-12`, `README.md:15-16`, `CHANGELOG.md:9,27,52-53`, `CREDENTIALS-BACKUP.md:89-90`, `fastlane/.../42.txt:10`, `docs/index.html:157` | intentional "ended at 2.1.5" statements — keep |
| `CREDENTIALS-BACKUP.md:25` "Windows credential manager / gh keyring" | dev-machine fact, fine |
| `2026-*.md` (21 dated plans), `Completed/**` (5 files), `CHANGELOG.md` | historical record — do not rewrite |
| GitHub description "Windows + Android.", topics `windows`, `python`, `gmail` | stale settings (see f) |
| `RunApp.bat` (points at the old `WAMailSync` path and `gui.py`), `.claude/settings.local.json` customtkinter allow-entry | untracked/gitignored local files; delete locally at leisure |
| `Chat-Mail-Sync-Password-Storage.docx`, `WhatsApp Gmail sync icon.zip` (tracked at repo root) | not Windows-specific but pre-rename clutter; candidates for `Completed/` or removal — not opened (binary) |
| `src/sync_manager.py:102` "Windows: C:\Users\…" path-scrub comment | still-needed (scrubs any path shape) |

**(c) Workflows.** Only two files: `tests.yml` (workflow "Tests") with jobs `test` → display name `Linux (3.13, matches Chaquopy)` (matrix, one entry) and `android-unit-tests` → `Android unit tests (Kotlin)`; `codeql.yml` (workflow "CodeQL") with job `analyze` → `Analyze (python)` and `Analyze (java-kotlin)`. Actions also shows GitHub-managed `Dependabot Updates`, `Dependency Graph` and `pages-build-deployment`. No Windows job or matrix entry survives — nothing "goes away now" for Windows. At Chaquopy removal: `Linux (3.13, matches Chaquopy)` goes away entirely; `Analyze (python)` goes away; both remaining jobs lose their "Set up Python 3.13 for Chaquopy" steps (tests.yml:116-119, codeql.yml:76-80) and get faster. Comments in tests.yml:13-16, 84-95 are stale now.

**(d) Required checks.** Could not be read (needs authenticated `gh api repos/harshalambani/ChatMailSync/branches/main/protection` and `/rulesets`). PR #69 shows "5 checks passed": the four job names above plus one GitHub-generated check (probably the CodeQL or Dependency Graph summary). If any of the four are required, the rule is: a required check whose job no longer exists stays "Expected" forever and blocks every merge. Sequence for each removal/rename: (1) open the PR that deletes/renames the job; (2) before merging, edit branch protection/ruleset to drop the old name (and add the new one only after it has reported once on that PR); (3) merge. Never rename a job and rely on the PR's own checks — they run under the *new* name while protection waits for the old one. The job name `Linux (3.13, matches Chaquopy)` contains the word Chaquopy, so it will be tempting to rename it in phase 0; do not — leave it until phase 5 deletes it, so protection changes once.

**(e) Releases.** Windows assets (`ChatMailSyncPortable_<ver>.zip` and `ChatMailSyncPortable_<ver>_English.paf.exe`) are on v2.0.2–v2.0.4, v2.1.0, v2.1.1, v2.1.3, v2.1.4, v2.1.5 (v2.1.2 shows 3 assets, APK only); v2.2.0 is APK-only. Nothing to delete. `docs/index.html:164,187-188` and `README.md:288-300` already point only at the releases page / `ChatMailSync-<version>.apk`; Obtainium has no filter configured and will keep working as long as each release carries exactly one `.apk`. The one thing to keep true: never attach a second APK (e.g. an unsigned or debug build) to a release, or Obtainium users get a prompt to choose.

**(f) Repo settings (read-only view).** Description: "Archive WhatsApp chat exports into a mailbox you own, over IMAP - each chat becomes a threaded email conversation. Windows + Android." → stale. Topics: android, chat-export, email-archive, gmail, imap, kotlin, python, whatsapp, whatsapp-backup, whatsapp-export, windows → drop `windows` now, `python` at phase 5, consider dropping `gmail`. Website: chatmailsync.ambani.tech (Pages from `docs/`, CNAME present). Dependabot: a "Dependabot Updates" workflow exists but no `.github/dependabot.yml` has ever been committed, so pip updates are GitHub-side (security updates / grouped). No CODEOWNERS, no issue/PR templates, no FUNDING.

**(g) Python dependency files.** `requirements.txt`: `python-dateutil>=2.8.2` — Android core only. `requirements-lock.txt`: `python-dateutil==2.9.0.post0` + `six==1.17.0` with hashes — Android core only, regenerated in #69. `requirements-dev.txt`: `pytest>=8.0.0` — tests only. No pyproject/tox/setup.cfg. Nothing Windows-only remains; all three files disappear at phase 5.

**Ordered finish-off checklist** (each step is one small PR that keeps main green):

1. Fix the `app_version` leak: pass `BuildConfig.VERSION_NAME` into the sync path (or drop `app_version` from the index JSON), delete `src/app_version.py` and `tests/test_app_version.py`, update `mail_index.py`. pytest and JUnit stay green; this is the only step with user-visible effect (index attachments stop saying "development build").
2. Delete the four NTFS-ACL helpers in `mail_client.py:76-156` and the now-unused `getpass`/`subprocess` imports; rewrite `config.py:12-31, 52-63` comments; fix `requirements-dev.txt:1`; prune `*.ps1`/`*.exe` from `.gitattributes`; fix stale comments in `tests.yml` and `ConnectionStatusTest.kt:11`.
3. Decide (H) and, if agreed, move `Chat-Mail-Sync-Password-Storage.docx` and `WhatsApp Gmail sync icon.zip` under `Completed/` or out.
4. GitHub settings, no PR needed: edit description to drop "Windows + Android"; remove topic `windows`; delete the stale remote feature branches; close any Windows-tagged issues (after listing them, H).
5. PrivacyScreen.kt + docs/privacy.html DPAPI wording: leave until the 2.1.5 Windows install base is judged gone (owner call, H). When changed, change both in one PR with the parity test.
6. Leave `Linux (3.13, matches Chaquopy)` and `Analyze (python)` alone until phase 5.

---

## D. Target architecture per module

Kotlin/JVM, `com.chatmailsync.app.core.*`, zero Android-only classes except SQLite and file I/O adapters.

**config** → a Kotlin `object Config` with the same constants (chunk defaults, `PROVIDER_MAX_MESSAGE_BYTES`, safety factor 0.90, `MAX_ZIP_DECOMPRESSED_BYTES`, `MAIL_SOCKET_TIMEOUT`, provider presets, retired-provider map, system-phrase lists, `TIMESTAMP_PATTERNS`). Root layout becomes a `Paths(root: File)` value passed explicitly (Python's `set_root` global mutation goes away; Kotlin already owns `pythonRoot`). Keep `IMAP_PROVIDERS` order as the picker order (`ProviderPickerTest` already pins it).

**parser** → the fragile one. Port the five regexes verbatim but with `Pattern.UNICODE_CHARACTER_CLASS` — Python's `\s` matches U+202F (narrow NBSP, which iOS inserts before AM/PM) and U+00A0, Java's `\s` does not by default; Python's `\d` also matches non-ASCII digits, so decide explicitly (recommend ASCII digits only and assert the difference in a negative test). Keep `_UNICODE_ARTIFACTS` stripping of U+200E, U+200F, U+FEFF, U+200B before matching. Replace `dateutil` with a hand parser for exactly what the regexes admit: `H:MM`, `H:MM:SS`, optional ` AM/PM` (case-insensitive), then Python's rule `12 AM → 0`, `12 PM → 12`, `1–11 PM → +12`; pin the open-ended cases (`14:05:33 PM`, `13:00 PM`, `0:30 PM`) by running dateutil once and recording its answer as the expected value. Date: split on `/` (or `-` for `dash_24h`), 2-digit year `<50 → 2000+`, `≥50 → 1900+`, 3-digit passes through unchanged (replicate, don't "fix"); DMY/MDY resolution with the three-step rule and 50-message scan window (`parser.py:313-372`); invalid day/month must raise the same way (`ValueError` → `IllegalArgumentException`) so the anomaly logging path matches. Output `timestamp_iso` with an explicit `"yyyy-MM-dd'T'HH:mm:ss"` formatter — `LocalDateTime.toString()` drops `:00` seconds and would silently change every hash. `extract_chat_info` regex chain verbatim. File reading: ZIP magic-byte detection, bomb guard, `_chat.txt` preference, utf-8-sig → utf-8 → latin-1 fallback order.

**state** → `SQLiteOpenHelper` as argued in B, DDL verbatim, `user_version` logic verbatim, `compute_message_hash` byte-exact (`MessageDigest("SHA-256")` over `chat_id\u0000ts\u0000sender\u0000body` UTF-8, lowercase hex). Row results as data classes. Keep `RUN_NATURAL_KEY` and the dedupe sweep.

**self_sender, progress** → pure Kotlin, direct ports (no I/O). `progress` becomes the source for `progress_state()`'s fields; SyncWorker's polling stays.

**html_renderer, media_extractor** → pure Kotlin; MD5 sender-colour bucketing (`html_renderer.py:173`) must use the same modulo so shadow diffs stay clean; `mimetypes.guess_type` → a fixed table mirroring Python's answers for the extensions WhatsApp emits (Python's table differs from Android's `MimeTypeMap` for e.g. `.opus`, `.webp`, `.vcf`; pin them). `encoded_part_bytes` arithmetic verbatim. `java.util.zip.ZipFile` with the same bomb guard; CID via `UUID`.

**mail_index** → pure Kotlin JSON serialisation matching `index_bytes` line format exactly (one key per line then one message per line — port the formatter, don't use a generic JSON pretty-printer).

**mail_client (IMAP + MIME) — recommend hand-rolled over `SSLSocket`, not Jakarta/Angus Mail.** Reasoning: the app uses six commands (greeting/CAPABILITY, LOGIN, LIST, CREATE, SUBSCRIBE, APPEND literal, LOGOUT), no SELECT/FETCH, no IDLE, no SASL beyond LOGIN, no STARTTLS. That is ~400 lines including tagged-response parsing, `{n}` literal handling with continuation, `APPENDUID` and `APPENDLIMIT` parsing, modified-UTF-7 and mailbox quoting — all of which already exist as Python functions with tests (`_parse_list_response`, `_encode_imap_utf7`, `_quote_imap_mailbox`, `_extract_appenduid`, `_appendlimit`), so parity is line-by-line. Jakarta Mail 2.x/Angus Mail on Android is a known rough edge (ServiceLoader/`META-INF/services` needs R8 rules, `jakarta.activation` MIME-type resolution, the historic need for the `com.sun.mail:android` fork), pulls ~1.5 MB pre-shrink, and its IMAP implementation makes choices you would then have to match (its APPEND date/flag formatting, its own reconnect logic, its UTF-7). Licence is not the blocker — Angus Mail is EPL-2.0 OR GPL-2.0-with-Classpath-exception, which F-Droid accepts and which links cleanly into a GPL-3.0 app — but there is nothing to gain. TLS: `SSLContext.getDefault()` with an explicit `enabledProtocols` floor of TLSv1.2 (`imap_tls_context` parity), hostname verification via `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")`, socket timeouts 180 s sync / 8 s per stage for the connection test. MIME: hand-built too — `multipart/mixed` + `multipart/related`, base64 with 76-char lines, RFC 2047 for Subject/From, RFC 2231 for filenames (Python's `add_header` output should be captured as golden bytes for a few non-ASCII names), `Date` in the same `%a, %d %b %Y %H:%M:%S +0000` shape with `Locale.ROOT`, `To: me`, `Message-ID: <wa-sync-<hex>@local>`. Keep `MailTransport` as an interface so the connection test, sync and JUnit fakes share it (`test_mail_transport.py`/`test_imap_transport.py` already test through a fake IMAP4 — port those fakes as a scripted `ImapServerFake` over a `PipedInputStream`).

**migration** → `java.util.zip` for the bundle, `org.json` for manifest/settings (already used in Migration.kt), the same allow-list and `_FORBIDDEN_SUBSTRINGS` tripwire, `_with_derived_host`, the additive `_merge_db` with `INSERT OR IGNORE` and natural-key run matching, `imported_bundles` ledger. Snapshot via checkpoint+copy (see B). `created_at_epoch` semantics (local time, seconds) unchanged.

**sync_manager** → straight port; `ProgressSyncManager`'s event vocabulary (`files_total / syncing / file_done / log / done / error`) unchanged since Kotlin's `progress` port consumes it; `stop_event` becomes a `@Volatile` flag honoured between files.

**android_api** → becomes a Kotlin `object CoreApi` with the same 30 function names and the same result data classes; every existing `callAttr` site swaps to a direct call. Keep the *shapes* (`ok`, `error`, `needs_confirmation`, `settings_json`…) so Migration.kt/SyncWorker.kt change minimally and their existing JUnit tests keep passing. Do the swap in one PR per screen after the core is complete, not incrementally per function, so no build ever has both a Python and a Kotlin implementation live on the same data.

**APK size.** Chaquopy's runtime + stdlib + dateutil is the bulk of the 20.5 MB; the retired x86_64 experiment noted ~9 MB per ABI of Python payload. Expect roughly 8–11 MB after removal with a hand-rolled IMAP client (tens of KB); Jakarta Mail would add ~0.5–1 MB post-R8.

---

## E. Phased sequence

**Phase 0 — Windows finish-off + CI hygiene** (checklist C, PRs 1–2; settings changes). Also add `dependenciesInfo { includeInApk = false; includeInBundle = false }` and `distributionSha256Sum` now — both are harmless on every channel and remove two F-Droid scanner findings early. Ship nothing; or fold into the next patch release.

**Phase 1 — mail_client IMAP + MIME spike.** New Kotlin package, no wiring. Port `_encode/_decode_imap_utf7`, `_quote_imap_mailbox`, `_parse_list_response`, `_extract_appenduid`, `_appendlimit`, the MIME builders, `chunk_messages`, `effective_budget/media_budget`, then `ImapTransport` and `check_connection`. JUnit parity for `test_imap_transport` (31), `test_mail_transport` (8), `test_connection_check` (34), `test_mail_index` (14) against a scripted fake server. One manual live test on the Yahoo account: create folder, APPEND one small and one near-limit message, confirm threading in the client. Exit criterion: bytes produced for a fixed fixture match Python's, modulo Message-ID/CID/boundary tokens.

**Phase 2 — dependency-ordered ports behind the facade, one module per PR**, in this order: config → progress → self_sender → parser (+ date parser, with the dateutil-pinned negative cases) → state → media_extractor → html_renderer → mail_index → mail_client (from phase 1) → migration → sync_manager → CoreApi. Each PR carries: the JUnit twin of its pytest file with the same test names, plus negative tests ("a 2-digit year 49 is 2049 and 50 is 1950", "a `:00` second is printed", "`\s` matches U+202F", "an unknown provider key does not become gmail on restore", "a bundle with `imap_host` in settings.json does not set the host", "user_version 1 triggers the dedupe sweep and 2 does not"). Python stays the live implementation throughout phase 2; the Kotlin core is compiled and tested but not called.

**Phase 3 — shadow verification.** A debug-only build flag runs both cores on the same inputs: parse every fixture and every `make_demo_exports.py` output through both parsers and diff `(chat_id, timestamp_iso, sender, body, attachment, hash)` per message; render both MIME trees and diff after normalising Message-ID/CID/boundaries; export a bundle from Python, import with Kotlin and vice versa on a throwaway root, diff table dumps; run `check_connection` through both against Yahoo and diff the stage list. Add real-world exports (redacted, kept out of git) from Android and iOS in at least DMY and MDY locales and both 12h/24h. Exit criterion: zero diffs on the corpus, and the JUnit count ≥ 374 minus the tests that are meaningless in Kotlin (`test_app_version`, `test_progress_output`), each such exclusion listed.

**Phase 4 — cutover as 2.9.x to existing channels** (GitHub/Obtainium, Xiaomi GetApps, Indus, Huawei): swap every `callAttr` site to `CoreApi`, delete the Chaquopy *calls* but keep the plugin and `src/` in the build for one release so a rollback is a one-line revert. Rewrite `CutoffDateTest`'s SyncWorker source-scan for the new call. Update-over-install test on a real device from 2.2.0: settings, stored password, chat list, sync history, cutoffs, self-sender, last-backup stamp all intact; restore a 2.2.0 `.cmsbackup` on 2.9.0 and a 2.9.0 bundle on a 2.2.0 install. Same signing key, versionCode continues from 42.

**Phase 5 — removal.** Delete `src/`, `tests/`, `requirements*.txt`, the `syncPythonCore` task, `chaquopy {}`, the plugin lines, `https://chaquo.com/maven`, `android/app/src/main/python/`; re-home `test_faq_parity`, `test_privacy_parity`, `test_chat_detail` and the `test_android_api.py:445` MainActivity guard into JUnit *before* deleting pytest; remove `Linux (3.13, matches Chaquopy)` and `Analyze (python)` and the "Set up Python" steps, in the same PR as the required-check edits described in C(d); drop the `python` topic; update README/index.html ("embedding a Python sync engine" at docs/index.html:156) and CodeQL comments. Ship as 2.9.x.

**Phase 6 — F-Droid as 3.0.0**, kept separate from phase 4/5 releases: fix `short_description.txt` to ≤80 chars, add `changelogs/<versionCode>.txt`, confirm a clean offline `./gradlew assembleRelease --offline` from a fresh clone with a populated Maven cache and no other network, file the fdroiddata MR with `Builds: subdir: android/app, gradle: [yes]`, and decide the signing question in H before the MR (reproducible-builds `Binaries:` path vs F-Droid key).

---

## F. Risk register

| Risk | Consequence | Mitigation |
|---|---|---|
| Frozen-identifier regression (prefs name, keystore alias, `filesDir/chatmailsync`, `data/sync_state.db`, applicationId, WorkManager names/class names, AppPrefs keys, app_state keys) | Highest: silent settings reset, password loss, or a fresh DB that re-mails every chat into every user's mailbox | FrozenIdentifiersTest already covers four; extend it to the WorkManager names, worker class names, `data/` layout, AppPrefs key strings and app_state keys in phase 0; the Kotlin `Paths` class must produce exactly `filesDir/chatmailsync/data/sync_state.db` |
| Hash drift (`timestamp_iso` seconds formatting, UTF-8 vs default charset, separator byte, sender/body cleaning order) | Every previously sent message looks new → duplicates | Golden-hash tests generated from Python for the corpus; shadow diff in phase 3 |
| Date-parsing fragility (`\s`/`\d` semantics, U+202F, AM/PM edge hours, 2-digit year pivot, DMY/MDY heuristic on ≤12 samples, 3-digit years, `latin-1` fallback) | Wrong timestamps → wrong chunking, wrong hashes, wrong cutoff decisions | Pin dateutil's answers before porting; corpus across locales; negative tests |
| Schema drift (Room, or a "tidied" DDL, or `onUpgrade` bumping `user_version`) | Existing DBs fail to open or get re-migrated | Raw helper, DDL verbatim, `user_version` semantics verbatim, test opens a DB file created by Python 2.2.0 |
| Backup format both directions (2.2.0 bundle → 2.9.x and 2.9.x → 2.2.0; `already_imported` ledger; `imap_host` derivation; `RUN_NATURAL_KEY` without `messages_cutoff`) | Restore failure or duplicated runs | Keep `schema_version = 1`; round-trip tests with real 2.2.0 bundles in phase 3/4 |
| Provider divergence (APPENDLIMIT parsing, iCloud/Fastmail delimiter, Gmail's non-advertised limit, `[TOOBIG]` mapping, SUBSCRIBE refusals) | Chats block mid-sync on one provider | Yahoo live tests only per policy; provider caps stay as data; keep `_SIZE_REJECTION_MARKERS` |
| Required-check merge lockout | Every PR blocked | Sequence in C(d); read protection first (H) |
| SQLite snapshot without backup API on API 24 | Backup missing WAL commits → re-send after restore | checkpoint(TRUNCATE) + copy under lock; test asserts counts match |
| Signing-key / channel switch for F-Droid users | Users leaving GitHub/GetApps for F-Droid must uninstall → lose state unless they back up first | Prefer reproducible builds so F-Droid ships the same signature; otherwise document the backup-first path prominently |
| Android Developer Verification (from 30 Sep 2026) interacting with an F-Droid-signed APK | F-Droid build may be uninstallable on certified devices in affected regions | Open question H |
| Test-harness change (SQLite in plain JVM tests) | Either Robolectric enters the build (contradicts `build.gradle.kts:173-177`) or DB tests run only on device | Decision in H |
| Scope creep (renaming `gmail_*` columns, "improving" chunking, dropping legacy OAuth detection, reordering providers) | Every improvement is a parity break | Rule for phases 1–5: behaviour-preserving only; improvements queue for 3.1 |
| `app_version` leak already in the field | Index attachments say "development build" | Phase 0 fix; harmless to old mail |

---

## G. Acceptance criteria

1. `./gradlew assembleRelease --offline` succeeds from a fresh clone with only Maven Central/Google Maven artefacts pre-cached; `settings.gradle.kts` lists no other repository; no Python on the build host.
2. JUnit contains a named twin for every pytest case (374 minus the explicitly listed Windows/console-only ones), plus the negative tests, all green in `Android unit tests (Kotlin)`.
3. Update-over-install from 2.2.0 on a real device: same prefs values, password decrypts, chat list, sync log, per-chat cutoffs, self-sender and last-backup stamp unchanged; a sync of an already-synced export reports 0 messages sent.
4. Backup round-trip: 2.2.0 bundle restores on 2.9.x with matching counts; 2.9.x bundle restores on a 2.2.0 install; same bundle twice → `already_imported`.
5. Real sync on the Yahoo test account only: new folder, threaded chunks, an oversized day splits, APPENDUID captured; Gmail never used.
6. fastlane metadata complete: summary ≤ 80 chars, changelog for the shipped versionCode, screenshots present.
7. F-Droid metadata MR filed for 3.0.0 with the build passing fdroidserver's `build` and `scanner` locally.
8. Required checks on `main` list only jobs that exist after phase 5.

---

## H. Open questions needing a human decision or a live test

1. Branch protection / rulesets: run `gh api repos/harshalambani/ChatMailSync/branches/main/protection` and `gh api repos/harshalambani/ChatMailSync/rulesets` and paste the required-check names — the C(d) sequencing depends on them.
2. Open issues/PRs mentioning Windows: `gh issue list --search windows --state all` and `gh pr list --state open` (neither was reachable).
3. Dependabot: is there a GitHub-side pip config (Settings → Code security)? It will start failing/noising once `requirements*.txt` are deleted; disable at phase 5.
4. Signing for F-Droid: attempt reproducible builds (F-Droid `Binaries:` pointing at the GitHub APK, so users keep one signature across channels) or accept F-Droid's key and a documented backup-first switch?
5. Android Developer Verification vs an F-Droid-signed APK: confirm with F-Droid's current guidance whether their signing key must be registered under your developer account, and whether that changes the answer to 4.
6. Test harness for the SQLite port: Robolectric (Maven Central, test-only), sqlite-jdbc behind a tiny interface, or device-only instrumentation tests? Current stance in `build.gradle.kts:173-177` is "no Robolectric".
7. Privacy text: when is the 2.1.5 Windows install base considered gone so the DPAPI sentences in PrivacyScreen.kt/privacy.html can go?
8. `mail_index` `app_version`: pass `BuildConfig.VERSION_NAME` through (changes future index JSON to a real version) or drop the field?
9. dateutil edge cases (`14:05 PM`, `13:00 PM`, `0:30 AM`, `12:00 AM`): run them through Python once and record the answers before the Kotlin date parser is written.
10. Corpus for phase 3: can you supply redacted real Android and iOS exports in DMY and MDY locales, 12h and 24h, with and without media? The three committed fixtures total 16 lines.
11. Repo-root `Chat-Mail-Sync-Password-Storage.docx` and `WhatsApp Gmail sync icon.zip`: move under `Completed/`, delete, or keep?
12. The 14 stale squash-merged remote branches: delete now, or keep?
13. `gradle-wrapper.jar` for Gradle 9.4.1: confirm fdroidserver's known-hash list includes it (otherwise the scanner rejects the jar and the metadata needs `rm: android/gradle/wrapper/gradle-wrapper.jar`).
14. Whether 2.9.0 (phase 4) and 2.9.1 (phase 5) ship as two releases or one — two is safer for rollback but costs a GetApps/Indus/Huawei review each.
