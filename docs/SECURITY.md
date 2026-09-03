# Security

WeightTrack keeps your data on your phone. There is no account, no server of ours, and nothing to
breach at our end. The risk that matters for a sideloaded app is different: you have to be sure the
APK you downloaded is the one we built, and that an update is signed by the same key as the install
it replaces.

This page tells you how to check both, and it is the permanent record of which signing keys are ours.

## Verifying a download

Every release ships a SHA-256 list beside the APKs. It's called `SHA256SUMS.txt` from v0.4.1
onward; v0.3.1 and v0.4.0 published the same thing as `SHA256SUMS`, and v0.1.0 and v0.2.0 published
nothing of the kind.

### 1. Check the file you downloaded is the file we published

Linux or macOS, from the directory holding the APKs and the checksum file:

```sh
sha256sum -c SHA256SUMS.txt
```

Windows PowerShell:

```powershell
Get-FileHash -Algorithm SHA256 .\WeightTrack-v0.5.0-play-release.apk
```

Compare the hash against the matching line in `SHA256SUMS.txt`. Case doesn't matter; the digits do.

### 2. Check who signed it

A checksum only proves the file arrived intact. The signature proves we built it. Use `apksigner`
from the Android SDK build tools:

```sh
apksigner verify --verbose --print-certs WeightTrack-v0.5.0-play-release.apk
```

Two things in that output matter. `Verified using v2 scheme` (or v3) has to say `true`, and the
line reading `Signer #1 certificate SHA-256 digest:` has to match a fingerprint listed below.

If you don't have the SDK, `keytool` prints the same fingerprint from the APK's signature block,
though it's more work. Android itself does the check on every install and refuses an update signed
by a different key, which is the backstop if you skip this step.

## Signing identities

WeightTrack is distributed only through GitHub Releases. One key signs the phone APKs, both
flavors, and the watch APK.

### Current: GitHub Releases channel

```
SHA-256: db:a1:aa:88:e3:7b:90:15:5f:ca:31:35:ca:3b:78:1d:e9:2c:22:51:07:e4:7c:98:06:e7:5b:f8:80:55:fd:d8
```

Unspaced, which is the form `apksigner` prints:

```
dba1aa88e37b90155fca3135ca3b781de92c225107e47c9806e75bf88055fdd8
```

In use since v0.3.1. Every release from v0.3.1 onward carries it, and every future release will
until a rotation is recorded here.

### Retired

```
12c8e4a91664fa0cab3f025b5fb8b2311e218f9e9e4eb1ca3c0925c3a6c9add3
```

Signed v0.1.0 and v0.2.0 only. It was replaced before v0.3.1 shipped. If you still have v0.1.0 or
v0.2.0 installed, Android will refuse to update it in place, because the key changed. Export your
data first (Settings, then Export), uninstall, install the current release, and import the file
back.

## Key rotation policy

A signing key is only useful as a promise if the promise is written down before it's needed. So:

- Both the old and the new fingerprint are published on this page before an update signed with a
  new key is released.
- The retired fingerprint stays on this page permanently. It is how somebody holding an old APK
  works out what they have.
- A rotation means existing installs cannot update in place. We will say so in the release notes
  along with the export-and-reinstall steps, not leave people to discover it from a failed install.

`tools/release-trust.json` is the machine-readable copy of the same facts. The release gate reads
it, and then refuses to pass unless this page publishes the current fingerprint and every retired
one, so the two cannot drift apart.

## How releases are checked before they go out

`tools/prepare-release.ps1` builds the three APKs from a clean module state, names them, writes
`SHA256SUMS.txt`, and then hands the directory to `tools/check-release-artifacts.ps1`. That gate
refuses to pass unless, for every APK:

- the package is `com.weighttrack`
- the version name matches `gradle.properties` exactly, suffixes included
- the version code matches the expected phone or watch band
- there is exactly one signing certificate and it's the fingerprint above
- the SHA-256 matches its `SHA256SUMS.txt` line, with no missing, extra or duplicate entry
- the v2 or v3 signature scheme verified, not v1 alone
- the archive is aligned for 16 KB memory pages
- any native library present includes a 64-bit ABI

It also refuses if this page has stopped naming the fingerprints it enforces.

`tools/test-release-artifact-gate.ps1` proves the gate can fail. It runs the checker against a
prepared release four more times with a wrong package, a wrong signer, a wrong version and a
tampered checksum, and fails if any of those is accepted.

## Trusting your own server's certificate

Sync talks to a WebDAV server you choose. Plain `http://` is refused: everything the app sends
carries either a password or a record of what you eat.

A server on your own network usually has a certificate it signed itself, which nothing on the
phone has any reason to believe. Rather than asking you to turn certificate checking off, the app
lets you pick the certificate your server presents, and then accepts that exact certificate.

Exact means exact. The bytes the server presents are compared against the bytes you picked, with
no chain building and no delegation. Picking a certificate authority's file gains an attacker
nothing: nothing that authority signs is trusted, only the file itself, and only for the host you
configured. The certificate's own expiry is checked, so a key taken from a server years ago does
not keep working. Everything the public authorities already vouch for is checked the ordinary way,
so pinning your own server never weakens the check on a hosted one.

If the app cannot read the certificate you stored, it stops using it rather than guessing, and
says so in the activity log.

## Reporting a vulnerability

Open a GitHub issue at https://github.com/SysAdminDoc/WeightTrack/issues for anything that isn't
sensitive: a crash, a permission that looks wrong, a check that isn't doing what this page says.

For something that puts a user's data at risk, use GitHub's private reporting instead. On the
repository, open the Security tab and choose "Report a vulnerability". That keeps the report
between us until there's a fix.

Please include the version, the build (play or foss), the Android version, and the steps you took.
There's no bounty. There is a fix and credit in the changelog if you want it.

### What is in scope

The app, the release tooling in `tools/`, and the sync and export formats. Anything that lets one
app on the phone read WeightTrack's data, lets a sync peer write outside its own records, or gets a
malformed import or archive to execute or overwrite something it shouldn't.

### What is not

Attacks that need an unlocked phone already in the attacker's hands, a rooted device, or a build
you compiled with the checks turned off. Those are real, but they're not something the app can
defend against.
