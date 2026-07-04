# WAGMailSync — PII & Secrets Audit + Removal Plan

**Date:** 2026-06-25
**Repo:** `github.com/harshalambani/WAGMailSync`  → **PUBLIC** (confirmed live)
**Remote:** `origin = https://github.com/harshalambani/WAGMailSync.git`
**Branches:** `main` only (local `main` == `origin/main` == `cc0d9a9`)
**Commits in history:** 4 — `cc0d9a9` (merge) ← `02dd792` + `ae014c2` ← `2def82e`
**Status:** Audit complete. **Nothing changed.** Awaiting your approval before any rewrite.

---

## TL;DR

- **No secrets/credentials were ever committed.** `auth/` (credentials.json, token.json), `data/`, and `Issues/` are all gitignored and **never appear in any commit or blob**. The code correctly loads OAuth creds from an external `auth/` folder. No API keys, client secrets, tokens, private keys, or `.env` files in the tree or history.
- **Real PII IS exposed in the public repo**, both at HEAD and in history:
  1. A personal Gmail address (`HarshalAmbani@gmail.com`) — in history.
  2. Real third-party contact names (`Bijal Ambani`, `Gautam Patel`) used as test data — at HEAD **and** history.
- Because the repo is public with only 4 commits and **no PRs / no forks / no releases**, cleanup is relatively clean — but the public-repo gotcha still applies (see Removal Plan, Stage 5).

---

## FINDINGS

### Category A — Secrets / credentials
**None found.** Verified:
- No `auth/`, `token`, `credential`, or `secret` blob in any object (`git rev-list --all --objects`).
- No key prefixes anywhere in history (`AIza`, `ghp_`, `xox*`, `sk-`, `GOCSPX-`, `ya29.`, `-----BEGIN ... PRIVATE KEY-----`, `*.apps.googleusercontent.com`).
- Code matches for `client_secret` / `refresh_token` / `access_token` are **variable names and comments only** (`gui.py`, `gui_worker.py`, `src/gmail_client.py`) — not values.
- A prior handoff note even records a clean secret scan.

➡️ **No secret rotation required** — nothing was leaked. (If you ever *did* commit `auth/` in a repo not covered here, rotate immediately. Not the case in this repo.)

### Category B — Personal PII (REMOVE)

| # | Value | Where | At HEAD? | In history? | Severity |
|---|-------|-------|:--------:|:-----------:|----------|
| B1 | `HarshalAmbani@gmail.com` | `Issues/Access blocked WA Sync App has not.txt` (commit `2def82e`) | No (now gitignored) | **Yes — reachable from `origin/main`, publicly extractable** | High — personal email tied to the mail app's Google account |
| B2 | `Bijal Ambani` (+ `Bijal Ambani_11`) | `2026-05-31-session-handoff.md:57`, `Completed/2026-05-31-session-handoff.md:57`, `src/sync_manager.py:507` (comment) | **Yes** | Yes | High — real person's name used as WhatsApp test data |
| B3 | `Gautam Patel` | `2026-05-31-session-handoff.md:80`, `Completed/2026-05-31-session-handoff.md:80` | **Yes** | Yes | High — real person's name used as WhatsApp test data |

> B1 is the critical "removed from tracking ≠ removed from history" case. The `Issues/` folder was gitignored in commit `ae014c2`, but the file (with the Gmail address) lives forever in commit `2def82e`, which is an ancestor of the public `origin/main`. Anyone can extract it.

### Category C — Ambiguous (your call)

| # | Value | Where | Note |
|---|-------|-------|------|
| C1 | `harshal.subscribe@hotmail.com` | `2026-06-09-git-init.ps1:36` + git commit-author email on all 4 commits | This is your **git-config commit email** = the "public project-owner identity" you said to LEAVE. It's the same address you used to message me. **Recommend: LEAVE** (scrubbing the file won't remove it from commit metadata anyway, and you said keep commit email). Flagging only because it also sits inside a tracked script. |
| C2 | `inabm` (local Windows username, in paths `C:\Users\inabm\...`) | `2026-06-09-git-init.ps1`, `build_portable.ps1`, `sign_exe.ps1`, several `Completed/*.md` | Low sensitivity — reveals your local OS account name. Common in committed scripts. Optional to scrub. |

### Category D — Leave (public owner identity / placeholders, NOT private PII)
- `Harshal` / `Publisher=Harshal` (`portable/App/AppInfo/appinfo.ini`, git-init script) — project-owner name.
- GitHub username `harshalambani`, repo URL, remote — public.
- Placeholder names in `2026-05-27-architecture.md`: `John Doe`, `Family Group`, and the phone-format examples `+91 98765 43210` / `919876543210` in `src/gmail_client.py` — generic illustration, not real data.

### Committed personal-data FILES (mailbox dumps, CSVs, DBs, images)
**None.** No `.mbox/.eml/.msg/.pst/.csv/.xlsx/.db/.sqlite/.png/.jpg/.pdf/.zip/.json` ever entered history. The only data-ish artifacts were the 3 `Issues/*.txt` debug logs in `2def82e` (B1 above + two harmless ones: a pip/PowerShell build log and a Python traceback — these contain the `C:\Users\inabm\...` path (C2) but no secrets/contacts).

---

## REMOVAL PLAN (do not execute yet — for you to run on your machine)

Goal: rewrite history to scrub B1–B3 (and optionally C2), then fully purge the old commits from GitHub. Since the repo is public, removing from your local history and force-pushing is **not enough** on its own — GitHub caches old commits via `refs/pull/*` and forks. With **0 PRs and 0 forks** here, you're in the easy case, but the bullet-proof fix is the delete-and-recreate in Stage 5.

### Stage 0 — Backup (mandatory)
Zip the entire folder *including* `.git` before any rewrite:
```powershell
cd "C:\Users\inabm\Documents\Cowork Playground\WAGmailApp"
Compress-Archive -Path ".\WA Chat Sync to Gmail App" -DestinationPath ".\WAGMailSync-BACKUP-2026-06-25.zip"
```

### Stage 1 — Install git-filter-repo
```powershell
pip install git-filter-repo
```
**Gotcha:** `git filter-repo ...` may fail with "not a git command" even when installed. If so, run it via its script path:
```powershell
python -c "import git_filter_repo, os; print(git_filter_repo.__file__)"
# then: python <that path> <args>
```

### Stage 2 — Build the replacement map
Use the provided **`2026-06-25-replacements.txt`** (draft below / saved alongside this report). Format is `literal==>replacement`. Review the fake values and change them if you prefer different aliases.

### Stage 3 — Rewrite history (text scrub)
From inside the repo:
```powershell
cd "C:\Users\inabm\Documents\Cowork Playground\WAGmailApp\WA Chat Sync to Gmail App"
git filter-repo --replace-text "<path>\2026-06-25-replacements.txt"
```
This rewrites every commit, replacing the literals in all blobs across all of history. (No `--invert-paths` needed — there are no whole files to delete; the `Issues/` file already isn't at HEAD and its content gets scrubbed in `2def82e` by the text replacement.)

> Optional: if you'd rather also drop the `Issues/` blob entirely rather than scrub its text, add to the same run: `--invert-paths --path-glob "Issues/*"`.

**Gotchas after filter-repo:**
- It **removes the `origin` remote** — re-add it: `git remote add origin https://github.com/harshalambani/WAGMailSync.git`
- On a Windows-mounted repo it can leave a stray NUL/whitespace line in `.git/config`. If git complains, open `.git/config` and keep only the valid lines.

### Stage 4 — Verify locally (before pushing)
```powershell
git log --all -S "HarshalAmbani@gmail.com" --oneline      # expect: nothing
git log --all -S "Bijal Ambani" --oneline                 # expect: nothing
git log --all -S "Gautam Patel" --oneline                 # expect: nothing
git grep -nI "Ambani\|Gautam Patel\|HarshalAmbani@gmail" $(git rev-list --all)   # expect: only allowed "Harshal"/"Ambani" owner refs if you kept them
git fsck --full                                            # expect: clean
```

### Stage 5 — Purge from GitHub (public-repo bullet-proof path)
Force-push alone leaves old commits reachable via GitHub's `refs/pull/*` and any forks. Here there are **0 PRs and 0 forks**, so a force-push would likely suffice — but the **100% clean** route is:
1. Make the repo **Private now** (interim, stops new exposure).
2. **Rename** `WAGMailSync` → `WAGMailSync-deprecated` (safety net).
3. Create a fresh **private** repo named `WAGMailSync`, push your scrubbed local history to it, verify it's clean (and CI green if you add any).
4. Once satisfied, **delete** `WAGMailSync-deprecated`.
5. Re-add origin to the new repo if needed and confirm `git log` on GitHub shows only scrubbed commits.

> Rename / making-private ≠ removal of data already pushed/cloned. Deletion of the old repo is what actually removes GitHub's cached old commits.

### Stage 6 — Secrets
No secrets were exposed → **no rotation needed** for this repo. (General rule still holds: any secret ever committed must be revoked, not just scrubbed.)

---

## Open questions for you (reply by number)
1. Confirm the fake aliases in `replacements.txt` (default: `Bijal Ambani`→`John Doe`, `Gautam Patel`→`Jane Roe`, `HarshalAmbani@gmail.com`→`owner@example.com`), or give me your preferred replacements.
2. Scrub the local Windows username `inabm` from paths too (C2), or leave it?
3. Keep `harshal.subscribe@hotmail.com` and `Harshal` as public owner identity (recommended), or scrub those as well?
4. For the `Issues/` debug file: text-scrub only, or fully drop the blob with `--invert-paths`?
5. Stage 5: do the full delete-and-recreate, or (given 0 PRs/0 forks) accept a simple force-push?
