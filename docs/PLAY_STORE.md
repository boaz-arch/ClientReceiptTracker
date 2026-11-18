# Google Play listing

A checklist and ready-to-paste text for publishing Support Visit on Google Play.

## Before you start

- [ ] Google Play developer account (one-time USD 25). Use an **organization** account with the
      company's details and a D-U-N-S number if you sell to businesses.
- [ ] The server is deployed with HTTPS, SMTP works, and the privacy policy and terms are live:
      `<PUBLIC_BASE_URL>/legal/privacy` and `<PUBLIC_BASE_URL>/legal/terms`
      (the deploy scripts copy `docs/PRIVACY_POLICY.md` and `docs/TERMS.md` there).
- [ ] A lawyer reviewed the privacy policy and terms, and all `[placeholders]` are filled in.
- [ ] A signed release built from a tag (see [RELEASE.md](RELEASE.md)).
- [ ] Play App Signing turned on (the default). You upload with your upload key; Google signs for users.
- [ ] Change the application id from `com.company.supportvisit` to one you own (for example
      `com.livnelavan.supportvisit`) **before the first upload**. It can never change afterwards,
      and changing it means existing phones must reinstall.

## Store listing

**App name:** Support Visit (up to 30 characters)

**Short description (EN, up to 80):**
Visit reports with client signatures, photos and PDFs, for IT and field teams.

**Short description (HE):**
דוחות ביקור עם חתימת לקוח, תמונות ו־PDF, לצוותי שירות ו־IT.

**Full description (EN):**

> Support Visit replaces paper visit forms for IT support and field-service teams.
>
> • Record each visit: client, times, visit type, work done and parts used
> • The client signs on the phone; add photos and a technician signature
> • A branded PDF report is emailed to the client automatically
> • Works offline: visits are sent as soon as there's a connection
> • Client list with contacts, hourly rates and support packages
> • Monthly reports per client (PDF, and CSV that opens in Excel), ready for billing
> • Create invoice drafts in Green Invoice (Morning)
> • Dashboard with hours, visits and clients at a glance
> • Teams: invite technicians, managers and admins; everyone sees what their role allows
> • Shared to-do list for the team
> • Hebrew and English, light and dark mode, optional fingerprint/face lock
>
> Sign in with your work email; no passwords. Your company's data is kept separate from every
> other company and is never sold.

**Full description (HE):**

> Support Visit מחליף את טופסי הביקור בנייר לצוותי תמיכה ושירות שטח.
>
> • תיעוד כל ביקור: לקוח, שעות, סוג ביקור, העבודה שבוצעה וחלקים
> • הלקוח חותם בטלפון; אפשר להוסיף תמונות וחתימת טכנאי
> • דוח PDF ממותג נשלח ללקוח בדוא"ל באופן אוטומטי
> • עובד גם בלי חיבור: הביקורים נשלחים ברגע שיש קליטה
> • רשימת לקוחות עם אנשי קשר, תעריפים ובנקי שעות
> • דוחות חודשיים לכל לקוח (PDF ו־CSV שנפתח ב־Excel), מוכנים לחיוב
> • יצירת טיוטות חשבונית בחשבונית ירוקה (Morning)
> • לוח בקרה עם שעות, ביקורים ולקוחות במבט אחד
> • צוותים: הזמנת טכנאים, מנהלים ואדמינים; כל אחד רואה את מה שהתפקיד שלו מאפשר
> • רשימת משימות משותפת לצוות
> • עברית ואנגלית, מצב בהיר וכהה, נעילה אופציונלית בטביעת אצבע או זיהוי פנים
>
> הכניסה עם דוא"ל העבודה, בלי סיסמאות. המידע של כל חברה נפרד מחברות אחרות ולעולם לא נמכר.

**Category:** Business · **Tags:** field service, reports, invoicing
**Contact email:** [support email] · **Website:** [website] · **Privacy policy:** `<PUBLIC_BASE_URL>/legal/privacy`

## Graphics checklist

- [ ] App icon 512 × 512 PNG (the app still uses the default launcher icon; add a real one first,
      Android Studio → New → Image Asset)
- [ ] Feature graphic 1024 × 500
- [ ] Phone screenshots, at least 2 (ideally 4–8), 16:9 or 9:16, in English **and** Hebrew:
  1. Home screen with today's summary
  2. New visit form with a client picked
  3. Signature pad
  4. The generated PDF report
  5. Past visits with sync status
  6. Monthly report for a client
  7. Insights charts
  8. Team members
- [ ] Use a demo company with invented clients. **No real client names, emails or signatures.**

## App content (Policy → App content)

### App access

Sign-in needs a code sent by email, which reviewers can't receive. Before submitting:

1. On the server, set `REVIEW_LOGIN_EMAIL` (e.g. `play-review@yourdomain`) and a 6-digit
   `REVIEW_LOGIN_CODE` in `~/SupportVisit/.env`, then redeploy. No email is sent to that address; the
   fixed code works instead. It locks after 20 wrong codes.
2. Sign in once with it yourself, create a demo company, and add a few invented clients and visits.
3. In Play Console choose *All or some functionality is restricted* and enter:
   > Enter the email `<REVIEW_LOGIN_EMAIL>` and tap "Send code". Then enter the code `<REVIEW_LOGIN_CODE>`.
   > The account is the admin of a demo company with sample clients and visits.
4. After approval, empty both settings and redeploy.

### Ads
No ads.

### Content rating
Questionnaire category *Utility, productivity, communication or other*; answer **No** to everything. The
expected rating is Everyone / 3+.

### Target audience
18 and over only. Not designed for children.

### Data safety form

Data is encrypted in transit: **Yes**. Users can request deletion: **Yes** (in the app: Settings →
Delete account; also by email). Data shared with third parties: **No**. Sending reports to addresses the
user enters is user-initiated, and Google doesn't count it as sharing. If you consider the invoicing
integration sharing, declare *Financial info → Purchase history* as shared for app functionality.

| Data type (Play category) | Collected | Optional? | Purpose |
|---|---|---|---|
| Personal info → Email address | Yes | Required | Account management, App functionality |
| Personal info → Name | Yes | Required | App functionality (client and company names) |
| Personal info → Phone number | Yes | Optional | App functionality (client contact) |
| Personal info → Address | Yes | Optional | App functionality (client and company address) |
| Personal info → Other info | Yes | Required | App functionality (signatures, work descriptions) |
| Financial info → Other financial info | Yes | Optional | App functionality (hourly rates, billing amounts) |
| Photos and videos → Photos | Yes | Optional | App functionality (visit photos, company logo) |
| Files and docs | Yes | Required | App functionality (PDF reports) |
| App info and performance → Crash logs | Yes | Optional (Settings → Privacy) | Analytics |
| App activity → Other actions | Yes | Optional (Settings → Privacy) | Analytics (anonymous daily counts) |

Not collected: location, contacts, messages, health, web browsing, device or other IDs.

The app is not "processed ephemerally": data is stored on the server.

### Other declarations
- **Government app:** No · **Financial features:** No (invoice drafts only; no payments, loans or trading)
- **News app:** No · **Health:** No
- **Permissions:** internet and network state only. Photos come from the system camera and photo picker,
  so the app doesn't ask for camera, storage or location permission.

## Release tracks

1. **Internal testing:** upload the AAB, add your own Google accounts, install from the Play link.
   Check sign-in, a visit with photos and signature, the PDF email, and offline sending.
2. **Closed testing:** new personal developer accounts must run a closed test with at least 12 testers
   for 14 days before production. Organization accounts don't have this requirement.
3. **Production:** staged rollout (e.g. 20% → 100%).

## Microsoft publisher verification

Not needed any more: since phase 5 the app signs in with email codes, not Microsoft accounts. If
Microsoft sign-in comes back, verify the publisher in Microsoft Entra (App registrations → Branding &
properties → *Publisher domain* and *MPN ID*) so customers don't see an "unverified" warning.
