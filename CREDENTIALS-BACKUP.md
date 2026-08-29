# Credential backup: what is replaceable and what is not

**Standing reference, not a one-off task.** This project holds several secrets and
they are not equally valuable. Treating them all as precious wastes effort and
spreads private keys around; treating them all as disposable loses the one that
cannot be replaced.

## The rule

> Back up a credential if it is **identity-bound or purchased**.
> Do not back up one that is **self-issued and regenerable** - recreate it instead.

Backing up a regenerable secret is not neutral. It puts a private key in another
location, in another backup, on another disk, for a benefit you could have had by
running a script. The only credentials worth that exposure are the ones where no
script can help you.

## The inventory

| Credential | Where | Class | What to do |
| --- | --- | --- | --- |
| **Android release keystore** | `android/app/release.jks` | **Irreplaceable** | Back up off-machine. See below. |
| **Keystore passwords** | `android/keystore.properties` | **Irreplaceable** | Back up **separately** from the `.jks`. |
| Windows dev signing cert | `CN=ChatMailSync Dev`, `Cert:\CurrentUser\My` | Regenerable | Do not back up. Re-run the create-and-trust script. |
| IMAP app password | `auth/imap_credentials.json` (Windows), Android Keystore | Regenerable | Revoke at the provider and issue a new one. |
| GitHub token | Windows credential manager / `gh` keyring | Regenerable | Re-authenticate `gh`. |

`auth/` and `data/` are gitignored and hold live credentials and real chat
exports. Nothing in them belongs in the repository, in a paste, or in a log.

Version 2.0.0 removed Google sign-in, so `auth/credentials.json` (the OAuth
client secret) and `auth/token.json` are no longer created or read. If a machine
set up before 2.0.0 still has them, they are dead files: delete them, and revoke
the leftover grant at <https://myaccount.google.com/permissions>. Restoring the
feature is a source change, documented in `docs/RESTORING-OAUTH.md`, not a
backup you need to hold.

## Why `release.jks` is the one that matters

Android will only update an installed app with a package signed by **the same
key**. Not a key with the same name - the same key. There is no reissue, no
support ticket, and no recovery. Lose it and the only path forward for every
existing user is uninstall and reinstall, which wipes app data: their settings,
their watched folder, and their Keystore-encrypted IMAP password.

`2026-08-02-android-store-distribution-phase.md` §3 says this key must be backed
up "before first submission". **That is already too late.** The v0.2.x beta APKs
published on GitHub Releases are signed with this key, so anyone who has
sideloaded one is already depending on it. The key became load-bearing the first
time a build left this machine, not at store submission.

It also constrains a future decision: see §3 of that document on why adopting
Play App Signing later would fork the update path for anyone who installed from
elsewhere.

## How to back it up

Two files, two destinations. That separation is the point - storing a keystore
next to its passwords reduces two secrets to one.

1. **`android/app/release.jks`** - copy to encrypted storage you control and can
   still reach if this laptop is gone. An encrypted archive on a second drive, or
   a password manager that accepts file attachments. Not an unencrypted cloud
   sync folder, and not the repository.
2. **`android/keystore.properties`** - 123 bytes of plaintext passwords for the
   keystore above. These belong in a password manager as fields, not as a file
   sitting beside the key.

Then confirm the backup actually works, because an untested backup is a belief
rather than a fact:

```
cd "C:\Users\inabm\Documents\Cowork Playground\ChatMailSync"
keytool -list -v -keystore android\app\release.jks
```

It will prompt for the store password. A correct password prints the alias and
the certificate fingerprints. Do the same against the restored copy before
trusting it, and record the SHA-256 fingerprint somewhere outside this machine -
it is how you verify later that a recovered file is the right key.

## Rebuilding the disposable ones

If this machine is lost or rebuilt, none of the following needs a backup:

- **Windows signing cert.** Create a new self-signed cert with the parameters in
  `sign_exe.ps1` and install it to `LocalMachine\TrustedPublisher` from an
  elevated shell. The new thumbprint differs from the old one and nothing breaks,
  because the certificate is self-signed: no user ever trusted it. It suppresses
  the publisher warning **on the build machine only** and makes the exe
  tamper-evident. It buys no SmartScreen reputation - only a purchased OV/EV
  certificate from a CA does that.
- **IMAP app password.** Revoke the old one at the mail provider and issue a new
  one. Revoking is the correct move on a lost machine regardless of backups.
- **Everything in `auth/`.** Reconnecting the app recreates all of it.

## If the laptop is lost

In order:

1. **Revoke the IMAP app password** at the mail provider. On Windows the
   password is protected only by an NTFS ACL - see `PLATFORM-PARITY.md` on the
   accepted per-platform divergence - so assume it is readable by anyone holding
   the disk.
2. **Confirm you can restore `release.jks`** and that its fingerprint matches the
   one you recorded. If you cannot, the Android app's update path is broken and
   that is a product decision to make deliberately, not a thing to discover at
   the next release.
3. Rebuild the disposable credentials above as needed.
4. Nothing needs doing about the signing certificate.
