# Planning task: demote the Gmail OAuth backend to a hidden advanced option

**Context for the planning session — read this whole file, then produce a phased implementation plan (not code yet).**

## Precondition (CONFIRMED, 2026-08-12)
The OAuth verification request for the restricted `gmail.insert` scope has been
**cancelled/rejected** — we declined the recurring paid CASA AL1 assessment, and Google
confirmed there is no sponsorship or workaround for restricted scopes. We also declined their
"Brand Verification" (scopeless) offer, because a scopeless OAuth flow can't insert mail and
buys nothing. The OAuth client therefore now sits in **"Testing" publishing status**. This is
fine — the app already defaults to an IMAP backend and IMAP is fully live and verified on both
Windows and Android. The rejection is reversible (a new verification request can be submitted
later) but we do not intend to.

## The decision to implement
**Demote, don't delete.** Stop presenting "Google sign-in (OAuth)" as a peer choice next to
IMAP in the shipped UI. Keep the entire `gmail_oauth` backend as live, working code behind a
**dev/advanced gate**, so the maintainer + family keep using it in Testing status.

Do **NOT**:
- delete the OAuth transports, `setup_auth.py`, `GMAIL_SCOPES`, or the Google deps;
- delete or alter the Google Cloud project / OAuth client;
- change the default backend (IMAP stays default);
- touch anything above the transport Protocol.

## Why (so the plan makes the right trade-offs)
A stranger who picks OAuth hits: the "Google hasn't verified this app" interstitial → a
refresh token that **expires 7 days after consent** (restricted scope on an unverified app,
overriding any longer setting) → eventually the **100-test-user cap**. None of that is
fixable without paying annual CASA. So the path must simply not be *offered* to ordinary
users — while staying reachable for the maintainer, for whom it works fine.

## Repo
`C:\Users\inabm\Documents\Cowork Playground\WAMailSync`
Windows / PowerShell 5.1. Python core shared across a Windows desktop (pywebview GUI + CLI)
and an Android app (Chaquopy Python + Kotlin/Compose UI). **`gh` is PowerShell-PATH only.**

## Hard constraint: platform parity
This is a user-visible change, so per `PLATFORM-PARITY.md` and the standing rule, **desktop
(Settings UI + CLI) and Android (Compose settings UI) must be changed in the same batch.**
Hiding OAuth on desktop but leaving it a visible button on Android is exactly the parity
failure that doc exists to prevent. The plan must cover both front-ends explicitly.

## What "hidden advanced option" should mean (validate/refine during planning)
- **Default shipped UI:** OAuth is not shown as a selectable backend. IMAP (with provider
  presets) is the only visible choice for a new/normal user.
- **The escape hatch for the maintainer:** OAuth becomes reachable only when an advanced
  flag is set — decide the cleanest mechanism by reading the code. Candidates to weigh:
  an env var (e.g. `WAMAILSYNC_ENABLE_OAUTH=1`), a hidden key in the persisted settings
  file, or a build/debug flag. It must work on **both** platforms (Android has no env var
  the user can set, so the mechanism likely has to be a settings-file/pref key, or a
  debug-build-only gate — resolve this).
- **Existing OAuth users must not break.** There is already an upgrade guard
  (`config.resolve_mail_backend`, mirrored by `AppPrefs.resolveMailBackend` on Android)
  that pins a user with a saved `token.json` / connected OAuth account to `gmail_oauth`.
  A user who is *currently* on the OAuth backend must keep working after this change even
  though the option is hidden from the picker — the plan must state exactly how (e.g. the
  gate auto-opens if OAuth is already the active/connected backend).

## Files the plan should locate and address (anchors, verify by reading)
- `src/config.py` — `resolve_mail_backend`, `MAIL_BACKEND_*`, `DEFAULT_MAIL_BACKEND`,
  `IMAP_PROVIDERS`, `is_gmail_mailbox`; add whatever advanced-gate constant/reader is chosen.
- `src/gmail_client.py` — the OAuth transports (`DiscoveryTransport`, `RestTransport`),
  `build_service`/`build_transport`/`get_credentials`; leave live, just not surfaced.
- Desktop UI: `gui.py` (Settings backend picker, ~the OAuth-limited warning), `gui_worker.py`
  (`connect_gmail`, `check_auth_status`, `build_transport_for_active_backend`,
  `resolve`/`_load_mail_backend_settings`), `cli.py`.
- `setup_auth.py` — keep, but it's a maintainer tool now.
- Android: the Compose Settings/backend-selection screen, `AppPrefs.resolveMailBackend`,
  `MainActivity.kt` (OAuth via Play Services), `SecretStore.kt`, help/`HelpScreen.kt` copy.
  Confirm where the backend picker lives and how to conditionally hide the OAuth entry.
- Docs/help copy: any string that offers OAuth as a normal choice (desktop + Android +
  README + docs site) should be reframed so IMAP is the presented path and OAuth reads as
  advanced/maintainer-only.

## Deliverables I want from the planning session
1. A phased plan (suggest: (P-a) pick + implement the cross-platform advanced-gate mechanism
   in shared config; (P-b) desktop UI + CLI hide-unless-gated + keep-existing-users-working;
   (P-c) Android UI parity; (P-d) copy/docs pass). Refine phasing if better.
2. Per phase: exact files touched and the change in each, verified against the repo.
3. The **gate mechanism recommendation** with reasoning, including how it works on Android
   where there's no user-settable env var.
4. The **"existing OAuth user keeps working while the option is hidden"** design, spelled out
   for both `resolve_mail_backend` (Python) and `AppPrefs.resolveMailBackend` (Kotlin) —
   these two must change together or one is silently wrong.
5. Test strategy: what proves (a) a fresh user never sees OAuth, (b) a gated maintainer does,
   (c) an already-connected OAuth user is unaffected — on both platforms.
6. A parity checklist mapping each desktop change to its Android counterpart.
7. Open questions for me before implementation.

## Notes
- IMAP is default and must stay so; nothing above the transport Protocol changes.
- Hand the actual build to a Sonnet subagent; main session coordinates and reviews.
- Do not start until the Google rejection/cancel is actually confirmed — this prompt assumes
  it has been.
