# Team server setup

Visits, clients, PDF reports and photos are shared between technicians through the
**Support Visit server** (`/server` in this repo). Each phone keeps its own SQLite database
(Room) and works offline; the server is where the team's data comes together.

```
Phone (Room/SQLite) ──HTTPS + Microsoft token──▶ Apache (TLS) ──▶ supportvisit-server (Docker, 127.0.0.1:8090)
                                                                     └─ /data: SQLite DB + files
Phone ──Microsoft Graph──▶ Outlook (the report email is sent from the technician's own mailbox)
```

* **Sign-in:** Microsoft only. The app gets an access token for the server's own API scope;
  the server checks the token's signature (Microsoft's public keys), audience, issuer, expiry
  and scope.
* **Companies are isolated by Microsoft tenant ID.** Every row and file is stored under the
  caller's tenant; one company can never read another's data.
* **Files** are served only to signed-in users, or through 15-minute signed links (used when
  opening a PDF or photo from the app).

## 1. Azure app registration (once)

In the Azure portal → **Microsoft Entra ID → App registrations →** your app:

1. **Expose an API**
   * *Application ID URI* → **Add** → accept `api://<application-client-id>` → Save.
   * **Add a scope**
     * Scope name: `access_as_user`
     * Who can consent: **Admins and users**
     * Admin consent display name: `Use Support Visit server`
     * Admin consent description: `Lets the app sync visits and clients with the Support Visit server as the signed-in user.`
     * State: Enabled
   * **Add a client application** → Client ID = the same application (client) ID →
     tick the `access_as_user` scope. (The app is then pre-authorized, so users don't get a
     second consent screen.)
2. **API permissions** → *Add a permission* → *My APIs* → your app → Delegated →
   `access_as_user` → Add. Then **Grant admin consent** for your organization.
   The app also uses Microsoft Graph `User.Read`, `Mail.Send` and `Files.ReadWrite`
   (unchanged from before). No SharePoint permissions are needed.
3. *(Optional)* **Manifest** → set `"requestedAccessTokenVersion": 2`. The server accepts
   both v1 and v2 tokens, so this is not required.

The app derives the scope `api://<client-id>/access_as_user` from `msal_config.json`
automatically. To use a different URI, set `API_SCOPE=...` in `local.properties`.

## 2. Server (once)

Requirements: Linux with Docker + Compose, a domain with HTTPS (any reverse proxy), ~1 GB RAM.

On the server:

```bash
mkdir -p ~/supportvisit-server/deploy && cd ~/supportvisit-server/deploy
# copy .env.example here (deploy.sh does this) and create the real .env:
cp .env.example .env
nano .env        # fill in the values below
chmod 600 .env
```

| Variable | Value |
|---|---|
| `AZURE_CLIENT_ID` | Application (client) ID of the app registration |
| `PUBLIC_BASE_URL` | e.g. `https://apps.example.com/supportvisit` (no trailing slash) |
| `LINK_SECRET` | output of `openssl rand -base64 48` |
| `ALLOWED_TENANT_IDS` | your Microsoft tenant ID while testing; empty later to allow any customer company |

From your PC (Git Bash), build and deploy:

```bash
server/deploy/deploy.sh user@your-server
```

It runs the server tests, builds the server, copies it over SSH and runs
`docker compose up -d --build`, then checks `/health`. Re-run it for every update; the data
in `deploy/data/` is kept.

### Alternative: pm2 (no Docker)

On hosts that run their apps with pm2:

```bash
server/deploy/deploy-pm2.sh user@your-server
```

It installs into `~/SupportVisit/` (`server/`, a private Java 17 runtime in `jre/`, `data/`,
`.env`, `ecosystem.config.js`, `start.sh`), registers the `supportvisit` pm2 process
(`pm2 save` keeps it across reboots), keeps the previous build in
`~/supportvisit-previous-server/` for rollback, and adds a nightly encrypted backup to cron
(`scripts/backup.sh` → `~/supportvisit-backups/`, key in `~/.supportvisit_backup_key`:
keep a copy of that key somewhere safe). The first run creates `.env` with a random
`LINK_SECRET` and stops so you can fill in the other values.

Useful commands: `pm2 logs supportvisit`, `pm2 restart supportvisit`.
Rollback: `cp -a ~/supportvisit-previous-server/server ~/SupportVisit/ && pm2 restart supportvisit`.

### Reverse proxy (Apache example)

Inside the `<VirtualHost *:443>` of the site, **above** any `ProxyPass /` line:

```apache
ProxyPass        /supportvisit/ http://127.0.0.1:8090/
ProxyPassReverse /supportvisit/ http://127.0.0.1:8090/
```

```bash
sudo apachectl configtest && sudo systemctl reload apache2
curl https://apps.example.com/supportvisit/health     # → ok
```

## 3. The app

The server address is built into the app from `SUPPORT_SERVER_URL` in `local.properties`
(or the environment in CI). It is not shown in the app and users can't change it.

If no server is configured, the app still works: reports are uploaded to the technician's
OneDrive and emailed as before, but nothing is shared with the team.

## Backups

Everything is in `~/supportvisit-server/deploy/data/` (`supportvisit.db` + `files/`).
Back it up daily, for example:

```bash
sqlite3 data/supportvisit.db ".backup data/backup-$(date +%F).db"
tar czf supportvisit-$(date +%F).tgz data/
```

Keep at least one copy off the server. This data includes client names, emails, signatures
and photos: treat backups as personal data (encrypt them, limit access).
