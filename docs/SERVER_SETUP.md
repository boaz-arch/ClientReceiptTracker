# Team server setup

Visits, clients, tasks, PDF reports and photos are shared between technicians through the
**Support Visit server** (`/server` in this repo). Each phone keeps its own SQLite database
(Room) and works offline. The server is where a company's data comes together, and it sends
all the email.

```
Phone (Room/SQLite) ──HTTPS + session token──▶ Apache (TLS) ──▶ supportvisit-server (127.0.0.1:8090)
                                                                   ├─ data/: SQLite DB + files
                                                                   └─ SMTP ──▶ sign-in codes, invitations, visit reports
```

* **Sign-in: email and a one-time code.**
  * The app asks for an email address, and the server emails a 6-digit code (valid 10 minutes,
    5 tries). The code is swapped for a session token that lasts 180 days and is renewed while
    the app is in use.
  * Customers need no Microsoft, Google or Azure account.
  * Codes and tokens are stored only as HMACs.
  * Limits: one code every 30 s and 5 per hour per email, plus 20 sign-in emails per hour per
    IP address.
* **Companies:**
  * A person who signs in without an invitation creates a company and becomes its **admin**.
  * Admins invite people by email (Settings → Team members). An invited person joins that
    company automatically the first time they sign in.
  * Every row and file is stored under the company id, so one company can never read
    another's data.
* **Report emails:**
  * The server sends every visit report from one address (`SMTP_FROM`), with the customer
    company's name as the sender name. Replies go to the company's email (or the technician's).
  * Each company can send `MAIL_DAILY_LIMIT` report emails a day.
* **Files** are served only to signed-in members, or through signed links: 15 minutes when
  opened from the app, 30 days inside an email when the PDF is too big to attach.

## 1. Email account (once)

You need an SMTP account for the address the emails come from, for example
`reports@yourdomain`. Any provider works.

| Provider | Settings |
|---|---|
| Microsoft 365 | `smtp.office365.com`, port 587. Turn on *Authenticated SMTP* for that mailbox (Microsoft 365 admin → Users → Mail → Manage email apps). |
| Google Workspace | `smtp.gmail.com`, port 587, with an app password. |
| Brevo / Mailgun / Amazon SES | Use the SMTP credentials from their dashboard. These are better for high volume. |

So reports don't land in spam, set up **SPF and DKIM** for your domain with the provider
(and ideally DMARC).

## 2. Server (pm2, like the other apps)

From your PC (Git Bash):

```bash
server/deploy/deploy-pm2.sh user@your-server
```

What the script does:
* It runs the server tests, builds the server, and installs it into `~/SupportVisit/` with a
  private Java 17 runtime.
* It registers the `supportvisit` pm2 process and keeps the previous build for rollback.
* It adds a nightly encrypted backup at 01:45.
* The first run creates `~/SupportVisit/.env` with a random `LINK_SECRET`, then stops so you
  can fill in the rest. Later runs add any new settings (empty) to an existing `.env`.

| Variable | Value |
|---|---|
| `PUBLIC_BASE_URL` | for example `https://apps.example.com/supportvisit` (no trailing slash) |
| `LINK_SECRET` | created for you. Changing it signs everyone out. |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `SMTP_FROM`, `SMTP_TLS` | your email account (step 1) |
| `APP_NAME` | the product name used in emails |
| `MAIL_DAILY_LIMIT` | report emails per company per day (default 200) |
| `PRO_TENANT_IDS` | optional: company ids with free Pro |

Useful commands, run on the server:

```bash
pm2 logs supportvisit
~/SupportVisit/start.sh companies                  # every company: id, members, name
~/SupportVisit/start.sh grant-pro <company-id> [note]
~/SupportVisit/start.sh revoke-pro <company-id>
~/SupportVisit/start.sh grants
~/SupportVisit/start.sh crashes                    # latest crash reports from the app
```

Rollback: `cp -a ~/supportvisit-previous-server/server ~/SupportVisit/ && pm2 restart supportvisit`.

### Reverse proxy (Apache)

Inside the `<VirtualHost *:443>` of the site, **above** any `ProxyPass /` line:

```apache
ProxyPass        /supportvisit/ http://127.0.0.1:8090/
ProxyPassReverse /supportvisit/ http://127.0.0.1:8090/
```

```bash
sudo apachectl configtest && sudo systemctl reload apache2
curl https://apps.example.com/supportvisit/health     # → ok
```

Docker is also supported (`server/deploy/docker-compose.yml`, same `.env`).

## 3. The app

The server address is built into the app from `SUPPORT_SERVER_URL` in `local.properties`
(or the environment in CI). Users never see or change it.

## Moving from Microsoft sign-in

People who used the server when sign-in was through Microsoft keep their company, role and
visits: the first time they sign in with the same email address, they get their old account
back.

## Backups

Everything is in `~/SupportVisit/data/` (`supportvisit.db` and `files/`). The nightly backup
is encrypted with the key in `~/.supportvisit_backup_key`. Keep a copy of that key somewhere
safe, off the server. The data includes client names, emails, signatures and photos, so treat
backups as personal data.
