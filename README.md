# Support Visit App

Android application my dad asked me to make for managing customer visits. The app creates visit reports, stores visit history, and tracks completed field-service activities.
The app was build with the help of AI.

## Overview

Support Visit App helps service technicians and field representatives document customer visits, maintain service records, and access historical reports from a mobile device.

The application enables users to:
- Create service visit reports
- Record customer information
- Document work performed
- Store visit history locally
- View previous visits
- Search visit records
- Open detailed visit information
- Associate PDF reports with visits


## Features

  ### Visit Management
  - Create new service visit records
  - Record visit date and time
  - Record customer details
  - Record work descriptions
  - Store customer email information
  
  ### Visit History
  - View all previous visits
  - Search visits by:
    - Client name
    - Visit date
    - Work description
  - Refresh visit records
  - Open detailed visit information
  
  ### Visit Details
  Each visit contains:
  - Client Name
  - Visit Date
  - Start Time
  - End Time
  - Work Description
  - Client Email
  - PDF Report Link
  
  ### Local Storage
  Visit data is stored locally as JSON:
    visits.json
  
  ### PDF Support
  Each visit has a PDF report link for easy access to service documentation.


## Technology Stack
  ### Language
  - Kotlin
  
  ### Frameworks & Libraries
  - Android SDK
  - AndroidX
  - RecyclerView
  - ViewBinding
  - Kotlin Coroutines
  - Lifecycle Components
  
  ### Data Storage
  - JSON
  - Internal App Storage
  
  
  ## Data Models
  
  ### Visit
  ```kotlin
  data class Visit(
      val clientName: String,
      val visitDate: String,
      val startTime: String,
      val endTime: String,
      val workDescription: String,
      val clientEmail: String,
      val pdfLink: String
  )
  ```
  
  ### VisitRequest
  ```kotlin
  data class VisitRequest(
      val clientName: String,
      val visitDate: String,
      val startTime: String,
      val endTime: String,
      val workDescription: String,
      val clientEmail: String
  )
  ```
  

## Microsoft sign-in setup

`app/src/main/res/raw/msal_config.json` holds your Azure app registration IDs, so it is **not** committed.
To build the app:

1. Copy `msal_config.example.json` (repo root) to `app/src/main/res/raw/msal_config.json`.
2. Fill in `client_id`, `tenant_id` and `redirect_uri` from your app registration in the Azure portal
   (Microsoft Entra ID > App registrations > your app > Authentication > Android platform).
3. The build fails with a clear message if the file is missing.

> **Upgrading an old clone:** the file used to be committed. When you pull the commit that
> stopped tracking it, git deletes your local copy. Restore it with
> `git show f750465:app/src/main/res/raw/msal_config.json > app/src/main/res/raw/msal_config.json`
> (or recreate it from the example).

## Team server

Visits, clients, PDFs and photos are shared through the Support Visit server in [`server/`](server/).
See [docs/SERVER_SETUP.md](docs/SERVER_SETUP.md) for the Azure, server and reverse-proxy setup.

## User Workflow

  ### Create Visit
  1. Open the application
  2. Enter customer information
  3. Enter visit details
  4. Save the visit
  5. Generate or attach a report
  
  ### View Past Visits
  1. Open "Past Visits"
  2. Browse visit records
  3. Search by customer, date, or description
  4. Open a visit to view details
  5. Access the associated PDF report
  

## Example JSON Storage
```json
[
  {
    "clientName": "ABC Industries",
    "visitDate": "2026-07-28",
    "startTime": "09:00",
    "endTime": "11:00",
    "workDescription": "Performed printer maintenance and replaced toner cartridge.",
    "clientEmail": "support@abc.com",
    "pdfLink": "https://example.com/report.pdf"
  }
]
```


## Error Handling
The application includes safeguards against:

- Missing storage files
- Empty JSON files
- Invalid JSON data
- Missing visit properties
- Corrupted visit records

This helps ensure the application remains stable when loading historical records.

## Company
Was made for Livnelavan LTD

