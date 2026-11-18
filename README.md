# Support Visit

Android app for IT support and field-service teams to record client visits, collect signatures and
send PDF reports, with a small server that syncs a company's phones. Made for Livnelavan LTD; it was
built with the help of AI.

## What it does

- **Visits:** client, date and times, visit type, work description, parts, photos, client and
  technician signatures → a branded PDF emailed to the client.
- **Offline first:** everything is saved on the phone first and sent when there's a connection.
- **Clients:** contacts, hourly rates and monthly support packages.
- **Billing:** hours × rate, monthly client reports (PDF, CSV), invoice drafts in Green Invoice.
- **Insights:** dashboard and charts by client, week, visit type and technician.
- **Teams:** sign in with an emailed code; companies with admin, manager and technician roles;
  invitations; shared to-do list; company branding on every report.
- **Privacy & security:** HTTPS only, per-company data separation, optional app lock, no Google
  backup of local data, anonymous crash reports that can be turned off, account deletion with a
  14-day restore window.
- Hebrew and English (RTL), light and dark mode.

## Architecture

```
app/      Android app (Kotlin, ViewBinding, minSdk 26)
  data/         Room database (visits, clients, tasks, drafts) — the phone's source of truth
  repository/   VisitRepository, ClientRepository, TaskRepository, merge of server changes
  sync/         WorkManager worker: upload files, have the server email reports, push/pull changes
  remote/       ServerApi (HTTP client for the server) and JSON mapping
  auth/         Email-code sign-in session
  team/         Company, role and branding (TeamSession, TeamRepository)
  billing/      Billing, monthly reports, InvoiceProvider (Green Invoice)
  dashboard/    Statistics behind the Insights charts
  plans/        PlanManager: Free/Pro limits (off by default)
  telemetry/    Crash reports and anonymous usage counters (opt-out)
  ui/, *Activity.kt   Screens and ViewModels
server/   Kotlin/Ktor + SQLite server: sign-in, companies, sync, files, report email
docs/     Server setup, release, Play Store, privacy policy and terms
```

Each phone keeps working on its own database; the server is shared by the company's phones and holds
files behind short-lived signed links.

## Getting started (development)

1. Install Android Studio (JDK 17+).
2. Create `local.properties` in the project root (Android Studio adds `sdk.dir`) and point the app
   at a server:
   ```properties
   SUPPORT_SERVER_URL=https://apps.example.com/supportvisit
   ```
   Debug builds also accept `http://` for a test server on your network; release builds need https.
3. Run the `app` configuration.

Server: see [docs/SERVER_SETUP.md](docs/SERVER_SETUP.md). Run its tests with
`./gradlew -p server test`.

## Setting up a new customer company

Nothing to configure on the server per customer:

1. They install the app and sign in with their work email.
2. The first person creates the company and becomes its admin.
3. In Settings → Company profile: name, logo, address, company number, email template.
4. In Settings → Team members: invite technicians and managers by email.
5. Optional: Settings → Billing for rates and the invoicing connection.
6. To give the company Pro: `~/SupportVisit/start.sh grant-pro <company-id>` on the server
   (`start.sh companies` lists the ids).

## Working on the code

- Branch from `main`, open a pull request, merge when CI is green. Don't push to `main` directly.
- CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs lint, unit tests, the debug build,
  the shrunk release build and the server tests on every push and pull request.
- Database changes need a new Room version and migration; schemas are kept in `app/schemas/`.
- Every user-facing string goes in `values/strings.xml` **and** `values-iw/strings.xml`.

## Releases

Tag `vX.Y.Z` on `main` and push the tag; GitHub Actions builds the signed AAB/APK and creates a
GitHub Release. One-time signing setup and details: [docs/RELEASE.md](docs/RELEASE.md).
Publishing on Google Play: [docs/PLAY_STORE.md](docs/PLAY_STORE.md).

## Legal

Drafts to be reviewed by a lawyer: [privacy policy](docs/PRIVACY_POLICY.md) and
[terms of use](docs/TERMS.md). The server publishes them at `/legal/privacy` and `/legal/terms`, and
the app links to them from Settings → Privacy.
