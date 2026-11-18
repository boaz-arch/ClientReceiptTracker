# Building and releasing

## Day to day: branches, pull requests and CI

- Work on a branch, open a pull request into `main`, merge when **CI** is green.
- CI (`.github/workflows/ci.yml`) runs on every push and pull request:
  - **Android app:** lint, unit tests, debug APK, and a release build with R8 code shrinking (unsigned),
    which catches missing keep rules early. The debug APK is attached to the run under *Artifacts*.
  - **Server:** all server tests.

### Recommended branch protection for `main`

GitHub → Settings → Branches → *Add branch ruleset* (or *Add rule*) for `main`:

- Require a pull request before merging
- Require status checks to pass: **Android app** and **Server**
- Block force pushes

## One-time setup for signed releases

### 1. Create the upload key (once, keep it forever)

On your computer (Android Studio includes `keytool`):

```bash
keytool -genkeypair -v -keystore supportvisit-upload.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

Store the `.jks` file and both passwords in a password manager **and** a second safe place. If you
lose it you can ask Google Play to reset the upload key, but outside Play (installing the APK directly)
you can never update the app again without uninstalling it, which loses unsent visits.

Never commit the keystore (`*.jks` is in `.gitignore`).

### 2. Local release builds (optional)

Create `keystore.properties` in the project root (it's in `.gitignore`):

```properties
storeFile=supportvisit-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Then `./gradlew :app:bundleRelease -PversionName=1.0.0 -PversionCode=10000`.
Without `keystore.properties` the release build is simply unsigned.

### 3. GitHub secrets

GitHub → Settings → Secrets and variables → Actions → *New repository secret*:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | the keystore as base64: `base64 -w0 supportvisit-upload.jks` (Linux), `base64 -i supportvisit-upload.jks` (macOS), or in PowerShell `[Convert]::ToBase64String([IO.File]::ReadAllBytes("supportvisit-upload.jks"))` |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | `upload` |
| `SIGNING_KEY_PASSWORD` | key password |
| `SUPPORT_SERVER_URL` | the live server, e.g. `https://apps.example.com/supportvisit` (must be https) |

## Releasing a version

```bash
git checkout main && git pull
git tag v1.0.0
git push origin v1.0.0
```

`.github/workflows/release.yml` then runs the tests and builds:

- `SupportVisit-1.0.0.aab`: upload this to Google Play
- `SupportVisit-1.0.0.apk`: for installing directly (or for the server's update channel)
- `mapping-1.0.0.txt`: **keep it**: it turns crash reports from this version back into readable
  class and method names

They're attached to a GitHub Release with notes generated from the merged pull requests.

Versions: `v1.2.3` → version name `1.2.3`, version code `10203`. Every release needs a higher number
than the last one, so always increase the tag.

## Before switching phones from a debug build to the release build

Debug and release builds are signed with different keys, so Android won't install one over the other.
On each phone: make sure **Past visits** shows nothing waiting to send, then uninstall the debug app and
install the release. Everything synced comes back after signing in.

## Plans (Free / Pro)

Plan limits are built in but **off**. To turn them on for a build, add `-PenforcePlans=true` to the
Gradle command (or to the release workflow). With limits on, a Free company has one member and no
Insights, billing settings or monthly reports. Give a company Pro on the server with
`~/SupportVisit/start.sh grant-pro <company-id>`. Google Play Billing can later be added in
`app/.../plans/PlanManager.kt` without changing any screen.

## Crash reports

Phones send crash reports to the server (unless the user turned it off in Settings → Privacy).
Read the latest on the server:

```bash
~/SupportVisit/start.sh crashes
```

Release builds rename classes, so de-obfuscate a stack trace with the matching mapping file. In the
Android SDK, `cmdline-tools/latest/bin/retrace`:

```bash
retrace mapping-1.0.0.txt stack.txt
```
