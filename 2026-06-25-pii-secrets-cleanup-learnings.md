# WAGMailSync — PII/Secrets Cleanup: Learnings

**Date:** 2026-06-25
**Repo:** `github.com/harshalambani/WAGMailSync` (public)
**Outcome:** PII fully removed from working tree + all history; force-pushed to GitHub; verified clean.

---

## What we were cleaning up

- **Secrets:** none were ever committed. Credentials (`auth/credentials.json`, `token.json`), `data/`, and `Issues/` were all gitignored and never entered any commit/blob. The app loads OAuth creds from an external `auth/` folder — good design. **No rotation needed.**
- **PII (the actual leak):**
  - `HarshalAmbani@gmail.com` — in a history-only debug file (`Issues/Access blocked...txt`, commit `2def82e`), still publicly extractable.
  - `Bijal Ambani`, `Gautam Patel` — real contact names used as test data, present at HEAD **and** in history.
- **Left intentionally:** owner identity (`Harshal`, commit email `harshal.subscribe@hotmail.com`), GitHub username, repo URL. Scrubbed the local Windows username `inabm` by choice.

---

## Key lessons

1. **"Removed from tracking" ≠ "removed from history."** Gitignoring `Issues/` (commit `ae014c2`) did nothing to the copy already baked into commit `2def82e`. Anything ever committed is permanently extractable from history until you rewrite it. Always scan full history, not just HEAD.

2. **Scan HEAD *and* every commit.** The Gmail address only existed in history; the contact names existed in both. A HEAD-only grep would have missed the email entirely. Use `git log --all --diff-filter=A --name-only` (every path ever added) and `git log --all -S "<string>"` (pickaxe) / `git grep <string> $(git rev-list --all)`.

3. **Distinguish secrets from PII.** This repo had zero secrets but real PII. Different remediation: secrets → rotate/revoke (scrubbing alone never un-leaks them); PII → scrub history. We needed only the PII path here.

4. **Public + low-activity = easy case, but verify it.** 4 commits, 0 PRs, 0 forks, 0 releases meant a force-push was practically sufficient. The bullet-proof route (delete + recreate) matters when forks/PRs exist, because GitHub keeps old commits reachable via `refs/pull/*` and forks.

---

## Gotchas hit (and fixes) — reusable runbook

| Gotcha | Symptom | Fix |
|---|---|---|
| filter-repo not a git subcommand | `git: 'filter-repo' is not a git command` even after `pip install` | Run the script directly: `python "<site-packages>\git_filter_repo.py" ...` |
| filter-repo refuses to run | `Refusing to destructively overwrite ... not a fresh clone` | Back up first, then add `--force` |
| Windows-mount `.git/config` corruption | `fatal: bad config line 11 in file .git/config` (block of NUL bytes appended after a section) | Rewrite `.git/config` keeping only valid lines (here-string → `Set-Content -Encoding ascii`) |
| origin removed by filter-repo | push has no remote | `git remote add origin <url>` before pushing |
| local gitignored copies persist | `Issues/*.txt` still on disk after history scrub | filter-repo only rewrites history, not ignored working files — delete them manually if you want them gone |

---

## Process that worked (order matters)

1. Map repo + remote, confirm public/private.
2. Scan HEAD for secrets/PII.
3. Scan **full history** (added paths + pickaxe).
4. Categorize: secrets vs PII vs committed data files vs leave-as-is.
5. Decide replacements/aliases with the owner (don't guess).
6. **Backup the whole folder (zip) before any rewrite.**
7. `git filter-repo --replace-text replacements.txt [--invert-paths --path-glob "<dir>/*"]`.
8. Repair `.git/config`, re-add origin.
9. **Verify before push:** pickaxe each literal (expect empty), confirm replacements present, confirm sensitive paths gone, `git fsck --full`.
10. Force-push (or delete+recreate for guaranteed purge).
11. Verify live on GitHub (raw file fetch).

---

## Residual risk

- Force-push route chosen. Old commit SHAs may remain reachable by direct URL until GitHub garbage-collects them. Acceptable here (no forks/PRs). For a guaranteed purge: make private → rename old to `-deprecated` → push scrubbed history to a fresh same-name repo → delete the deprecated one.

## Prevention going forward

- Never commit debug/log dumps (`Issues/`) — keep them gitignored from day one.
- Use placeholder names (`John Doe`) for test data, never real contacts.
- Consider a pre-commit secret/PII scan (e.g., `gitleaks`) or a scheduled periodic scan.
- Keep the two audit deliverable files (`...audit-and-removal-plan.md`, `replacements.txt`) **untracked** — they contain the real values.
