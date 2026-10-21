<div align="center">
<img src="assets/icon.png" width="100" alt="SwiftBackupPrem Logo"/>
<h1> SwiftBackupPrem </h1>

<p>An advanced Xposed / LSPosed / LSPatch module for Swift Backup that unlocks Premium features, enables cloud backups for local accounts, and supports isolated personal Firebase backends.</p>

<a href="https://github.com/s1ddhants1/SwiftBackupPrem/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/s1ddhants1/SwiftBackupPrem/ci.yml?style=for-the-badge&logo=githubactions&logoColor=white&label=CI" alt="CI Status" /></a>
<a href="https://github.com/s1ddhants1/SwiftBackupPrem/releases"><img src="https://img.shields.io/github/v/release/s1ddhants1/SwiftBackupPrem?style=for-the-badge&color=6366f1&logo=android" alt="Release Version" /></a>
<a href="https://t.me/SwiftBackupPrem"><img src="https://img.shields.io/badge/Telegram-Join%20Chat-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white" alt="Telegram Support Group" /></a>
<a href="https://github.com/s1ddhants1/SwiftBackupPrem/blob/main/LICENSE"><img src="https://img.shields.io/github/license/s1ddhants1/SwiftBackupPrem?style=for-the-badge&color=10b981" alt="License" /></a>
<img src="https://img.shields.io/badge/Android-8.1%2B%20(API%2027--37)-f59e0b?style=for-the-badge&logo=android" alt="Android API Support" />
<img src="https://img.shields.io/badge/Framework-LSPosed%20%7C%20Xposed%20%7C%20LSPatch-8b5cf6?style=for-the-badge" alt="LSPosed / Xposed / LSPatch" />
<img src="https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-06b6d4?style=for-the-badge&logo=jetpackcompose" alt="Jetpack Compose Material 3" />

</div>

---

## Table of Contents

- [Features](#features)
  - [Core Features](#core-features)
  - [Cloud & Migration Capabilities](#cloud--migration-capabilities)
  - [Companion App & Usability](#companion-app--usability)
- [How it Works](#how-it-works)
- [Compatibility & Prerequisites](#compatibility--prerequisites)
- [Installation & Activation](#installation--activation)
  - [Method 1: Obtainium (Recommended)](#method-1-obtainium-recommended)
  - [Method 2: Manual Download](#method-2-manual-download)
  - [Rooted Activation (LSPosed / LibXposed)](#rooted-activation-lsposed--libxposed)
  - [Rootless Setup (LSPatch)](#rootless-setup-lspatch)
  - [Android TV / Leanback Devices](#android-tv--leanback-devices)
- [Choosing Your Cloud Workflow](#choosing-your-cloud-workflow)
- [Local Account Cloud Features Guide](#local-account-cloud-features-guide)
  - [How It Works Under the Hood](#how-it-works-under-the-hood)
  - [Encryption Keys & Custom UID](#encryption-keys--custom-uid)
- [Custom Firebase Setup Guide](#custom-firebase-setup-guide)
  - [Why Use Your Own Firebase Instance?](#why-use-your-own-firebase-instance)
  - [Step 1: Create a Firebase Project](#step-1-create-a-firebase-project)
  - [Step 2: Set Up Realtime Database & Security Rules](#step-2-set-up-realtime-database--security-rules)
  - [Step 3: Configure Authentication](#step-3-configure-authentication)
  - [Step 4: Register Android App & OAuth Client](#step-4-register-android-app--oauth-client)
  - [Step 5: Enable Google Drive API & OAuth Scopes](#step-5-enable-google-drive-api--oauth-scopes)
  - [Step 6: Import or Enter Credentials in SwiftBackupPrem](#step-6-import-or-enter-credentials-in-swiftbackupprem)
- [Migrating & Accessing Backups from Default Firebase](#migrating--accessing-backups-from-default-firebase)
- [Backup Migration](#backup-migration)
  - [Local Migrator (Decrypt, Convert & Re-encrypt)](#local-migrator-decrypt-convert--re-encrypt)
  - [Universal Cloud Discovery & Snapshot Injection](#universal-cloud-discovery--snapshot-injection)
  - [Firebase Realtime Database Metadata Sync](#firebase-realtime-database-metadata-sync)
- [LSPatch Rootless Integration & In-App Settings](#lspatch-rootless-integration--in-app-settings)
- [Configuration Export & Migration](#configuration-export--migration)
- [Building from Source](#building-from-source)
- [Community & Support](#community--support)
- [Frequently Asked Questions (FAQ)](#frequently-asked-questions-faq)
- [Credits & Acknowledgements](#credits--acknowledgements)
- [License & Disclaimer](#license--disclaimer)

---

## Features

### Core Features

- **Unlocks Premium Features**: Unlocks all Swift Backup Premium features (batch operations, scheduling, cloud backups, app data backup/restore, custom folder backups) without requiring Google Play Store licensing checks.
- **Privacy & Telemetry Suppression**: Blocks Firebase Analytics, Crashlytics, Sessions, Installations, and Google DataTransport calls to keep your backup operations private.
- **Root & Rootless Environments**: Works in rooted environments (Magisk, KernelSU, APatch with LSPosed or Vector) and non-rooted environments via [JingMatrix/LSPatch](https://github.com/JingMatrix/LSPatch).

### Cloud & Migration Capabilities

- **Local Account Cloud Features (Zero-Firebase Mode)**: Enables cloud backup creation, cloud file uploads, and cloud backup restores for offline/local accounts without requiring a Google account or Firebase project. Intercepts Realtime Database calls to persist catalog metadata locally and to cloud providers directly, with optional Custom Encryption UID for backups.
- **Universal Cloud Discovery & Snapshot Injection**: Multi-cloud scanner (Google Drive, Microsoft OneDrive, Dropbox, Box, pCloud, Amazon S3, WebDAV / Nextcloud / ownCloud) that discovers cloud backups without prior database records, injecting synthetic Firebase `DataSnapshot` objects directly into Swift Backup's native restore screens.
- **Fast-Path Remote Discovery & Cloud Cache**: Caches catalog metadata locally for instant offline navigation across restore screens, and synchronizes canonical cache files to cloud remotes for instant discovery on new devices.
- **Remote APK Manifest Parsing via HTTP Range Requests**: Uses `ApkRangeManifestParser` for cloud discovered backups to fetch only the remote APK ZIP Central Directory and parse `AndroidManifest.xml` via HTTP byte ranges in seconds, displaying exact app labels, package names, and version codes without downloading gigabytes of data.
- **Google Drive Full OAuth Scope Expansion**: Upgrades runtime OAuth scopes from restricted `drive.file` to full `drive` access so Swift Backup can query and restore backups uploaded from other devices or Swift Backup accounts on different Firebase projects.
- **Local Backup Migrator & Re-encryptor**: On-device cryptographic engine to decrypt archives, repair missing metadata, switch encryption keys (Shared Anonymous Key vs Custom Firebase UID), or export unencrypted portable archives (`.apk`, `.tar.gz`, `.json`).

### Companion App & Usability

- **Modern Material 3 UI**: Clean user interface built with Jetpack Compose, Material 3 guidelines, dynamic color adaptation, and edge-to-edge support.
- **Android TV / Leanback Support**: Features a custom Leanback banner, native D-pad focus traversal, and big-screen friendly layouts for Android TV devices.
- **In-App Settings Integration**: When running in LSPatch Integrated Mode, module settings inject natively into Swift Backup's settings menu (**Settings → SwiftBackupPrem**).
- **Guided Setup & 1-Tap Helpers**: In-app Setup Wizard with clipboard helpers (Package Name, SHA-1 signing fingerprint) and one-tap import for `google-services.json` files.
- **Configuration Export & Import**: Export your active configuration to `sbp_config.json` and restore it instantly on fresh ROM installs or secondary devices.
- **Quick Process Controls**: One-tap Root Force Stop and Launch shortcuts directly within the app.

---

## How it Works

- **Dynamic DexKit Bytecode Scanning**: Utilizes [DexKit](https://github.com/LuckyPray/DexKit) to dynamically locate obfuscated classes, methods, and fields at runtime across versions, ensuring robust compatibility with newer app releases without requiring fragile hardcoded ProGuard symbols.
- **Native JNI Protection**: Leverages a companion C native library (`libnativelib.so`) to intercept `JNI_OnLoad` calls, ensuring 16 KB kernel page-size alignment for Android 15+ devices.

---

## Compatibility & Prerequisites

| Requirement                 | Details                                                                                                                                                                                                                                                                                                                                               |
| :-------------------------- | :---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Root Solution**           | [Magisk](https://github.com/topjohnwu/Magisk), [KernelSU](https://github.com/tiann/KernelSU), or [APatch](https://github.com/bmax121/APatch)                                                                                                                                                                                                          |
| **Rootless Solution**       | [LSPatch](https://github.com/JingMatrix/LSPatch) (Recommended: [JingMatrix fork](https://github.com/JingMatrix/LSPatch); supports both Manager Mode and Integrated Mode)                                                                                                                                                                              |
| **Xposed / Hook Framework** | Modern [LibXposed](https://github.com/libxposed) (API 101 / 102+) compatible frameworks:<br>• [LSPosed](https://github.com/LSPosed/LSPosed) (v2.0.0+)<br>• [Vector](https://github.com/JingMatrix/Vector)<br>• LSPosed variants (LSPosed-Irena, etc.)<br>• [LSPatch](https://github.com/JingMatrix/LSPatch) (rootless loader; JingMatrix recommended) |
| **Legacy Xposed Support**   | Available as the `legacy` build variant (`SwiftBackupPrem_${version}-legacy-release.apk`) for older Xposed frameworks (API 82–93 / EdXposed / classic LSPosed <= 1.11)                                                                                                                                                                                |
| **Android Version**         | Android 8.1 (API 27) up to Android 17 (API 37+)                                                                                                                                                                                                                                                                                                       |
| **Form Factors**            | Phones, Tablets, Foldables, and Android TV (Leanback)                                                                                                                                                                                                                                                                                                 |
| **Target Application**      | [Swift Backup](https://play.google.com/store/apps/details?id=org.swiftapps.swiftbackup) (`org.swiftapps.swiftbackup`)                                                                                                                                                                                                                                 |
| **Supported App Versions**  | v4.0.0 (525) through v5.0.8 (603), v5.1.0 (620), and newer releases                                                                                                                                                                                                                                                                                   |

---

## Installation & Activation

### Method 1: Obtainium (Recommended)

Automatically download and receive updates by adding SwiftBackupPrem to [Obtainium](https://github.com/ImranR98/Obtainium):

<p>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https%3A%2F%2Fgithub.com%2Fs1ddhants1%2FSwiftBackupPrem">
    <img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="80">
  </a>
</p>

Or add the repository URL manually in Obtainium:

```
https://github.com/s1ddhants1/SwiftBackupPrem
```

### Method 2: Manual Download

1. Download the latest APK from the [Releases](https://github.com/s1ddhants1/SwiftBackupPrem/releases) page.
2. Install the APK on your device.

---

### Rooted Activation (LSPosed / LibXposed)

1. Open **LSPosed Manager** (or your active LibXposed framework manager).
2. Navigate to the **Modules** tab and tap **SwiftBackupPrem**.
3. Toggle **Enable module**.
4. Ensure the scope includes **Swift Backup** (`org.swiftapps.swiftbackup`).
5. Force stop Swift Backup or reboot your device.
6. Open **SwiftBackupPrem** to configure your preferred cloud mode (see [Choosing Your Cloud Workflow](#choosing-your-cloud-workflow)).

---

### Rootless Setup (LSPatch)

SwiftBackupPrem fully supports rootless installation via [LSPatch](https://github.com/JingMatrix/LSPatch):

- **Manager Mode**:
  1. Patch Swift Backup with LSPatch in **Manager Mode**.
  2. Install SwiftBackupPrem APK as a separate companion application.
  3. Enable SwiftBackupPrem in LSPatch Manager under Swift Backup's module list.
- **Integrated Mode (Standalone APK)**:
  1. Patch Swift Backup with LSPatch in **Integrated Mode**, selecting the SwiftBackupPrem APK as an embedded module.
  2. Install the patched Swift Backup APK.
  3. Module settings will appear natively inside Swift Backup under **Settings → SwiftBackupPrem**! (No manager app required).

---

### Android TV / Leanback Devices

SwiftBackupPrem includes native Android TV metadata:

- Sideload the APK onto your Android TV or Google TV box via ADB or a file manager.
- The app appears directly on your Leanback home screen launcher with a dedicated banner.
- All interface controls, tabs, and buttons are navigable via the standard D-pad remote control.

---

## Choosing Your Cloud Workflow

SwiftBackupPrem provides two ways to manage cloud backups. Pick the workflow that fits your needs:

- **[Workflow A: Local Account Cloud Features](#local-account-cloud-features-guide)**  
  Use Swift Backup with a **Local Account** (no Google Sign-In). Enables full cloud backup creation, file uploads, and restores across any supported provider (WebDAV, Nextcloud, S3, OneDrive, Google Drive, etc.) with **zero Firebase or Google Cloud setup**.

- **[Workflow B: Custom Firebase Backend](#custom-firebase-setup-guide)**  
  Connect your own personal Firebase project to sign in with your **Google Account**. Ideal if you want backups linked to your Google identity and want metadata persisted to your private Realtime Database.

---

## Local Account Cloud Features Guide

Swift Backup normally restricts cloud storage and cloud backups to users signed in with a Google account via Firebase. Selecting "Local Account" disables cloud access.

**Local Account Cloud Features** completely removes this restriction, enabling full cloud backup creation, cloud file uploads, and cloud backup restores for offline and local accounts.

### Quick Setup

1. Open **Swift Backup** and choose **Local Account** (skip Google Sign-In).
2. Open **SwiftBackupPrem** and toggle on **Local Account Cloud Features**.
3. _(Optional)_ Under **Encryption Key**, select **Shared Anonymous Key** (offline-restorable on any device) or **Custom Key** (enter any custom UID).
4. Return to **Swift Backup** > **Settings** > **Cloud Storage** and configure your cloud provider (WebDAV, Nextcloud, OneDrive, Dropbox, Box, pCloud, S3, or Google Drive).
5. Perform backups, view cloud sync, and restore directly!

### How It Works Under the Hood

- **Identity Spoofing**: `LocalCloudUnlockHook` transparently intercepts `isAnonymous()` calls during cloud operations, making Swift Backup recognize the local user as fully authorized.
- **Database Virtualization**: `CloudDatabaseManager` virtualizes Firebase Realtime Database reads and writes in memory and keeps a canonical cache at `/sdcard/SwiftBackup/cloud_discovered_cache.json`.
- **Direct Cloud Uploads**: Application metadata manifests are automatically written and synchronized directly to your connected cloud storage remotes.
- **Dialog Suppression**: `FirebaseConnectionWatcher` intercepts backend connectivity loops and suppresses network error dialogs.

### Encryption Keys & Custom UID

Swift Backup encrypts backup data using Facebook Conceal (AES-256-GCM) keyed to the account UID:

- **Shared Anonymous Key (`d58b0944415a4889d7f11aa95fbeca50`)**: Standard anonymous key that stores backups under account folder hash `8690a48a4fcc72f1`. These backups can be restored offline on any device running Swift Backup.
- **Custom Key**: Enter a custom string or UID. The hook sets your custom UID into memory so that encryption key derivation (`deriveConcealKey`) and directory hashes (`computeAccountHash`) match your private key.

---

## Custom Firebase Setup Guide

### Why Use Your Own Firebase Instance?

By default, Swift Backup authenticates against the app developer's Firebase project. If the developer blocks or bans your account on their Firebase instance, access to the account will be revoked, and you will not be able to restore your backups.

Connecting your own personal Firebase project gives you complete isolation, ensures data privacy, and prevents remote bans.

---

### Step 1: Create a Firebase Project

1. Go to the [Firebase Console](https://console.firebase.google.com/).
2. Click **Add project** (or **Create a project**).
3. Enter a project name (e.g., `SwiftBackup-Personal`) and continue.
4. Google Analytics can be disabled (optional) to speed up creation.
5. Click **Create project** and wait for provisioning to finish.

<details>
<summary>View Step 1 Screenshots</summary>
<br>
<p align="center">
  <img src="Screenshots/step1_01_project_name_prompt.webp" alt="Enter project name" width="700" /><br>
  <em>1. Enter project name</em><br><br>
  <img src="Screenshots/step1_02_project_name_entered.webp" alt="Confirm project name" width="700" /><br>
  <em>2. Confirm project name</em><br><br>
  <img src="Screenshots/step1_03_google_analytics_toggle.webp" alt="Google Analytics option" width="700" /><br>
  <em>3. Configure Analytics and click Create project</em><br><br>
  <img src="Screenshots/step1_04_project_provisioning.webp" alt="Provisioning project" width="700" /><br>
  <em>4. Provisioning Firebase project</em><br><br>
  <img src="Screenshots/step1_05_project_ready.webp" alt="Project ready" width="700" /><br>
  <em>5. Project is ready</em>
</p>
</details>

---

### Step 2: Set Up Realtime Database & Security Rules

1. In your Firebase project sidebar, go to **Databases & Storage > Realtime Database**.
2. Click **Create Database**, select a region close to you (e.g., `United States` or `Belgium`), and choose **Start in locked mode**.
3. Once created, switch to the **Rules** tab at the top.
4. Replace the existing rules with the following user-isolated security rules:

```json
{
  "rules": {
    "users": {
      "$uid": {
        ".read": "$uid === auth.uid",
        ".write": "$uid === auth.uid"
      }
    }
  }
}
```

5. Click **Publish** to save the rules.
6. Copy your **Realtime Database URL** from the _Data_ tab (e.g., `https://your-project-id-default-rtdb.firebaseio.com/`).

<details>
<summary>View Step 2 Screenshots</summary>
<br>
<p align="center">
  <img src="Screenshots/step2_01_navigate_realtime_database.webp" alt="Navigate to Realtime Database" width="700" /><br>
  <em>1. Select Databases &amp; Storage &gt; Realtime Database</em><br><br>
  <img src="Screenshots/step2_02_database_location.webp" alt="Select Database Location" width="700" /><br>
  <em>2. Choose database region / location</em><br><br>
  <img src="Screenshots/step2_03_start_locked_mode.webp" alt="Start in locked mode" width="700" /><br>
  <em>3. Select Start in locked mode and Enable</em><br><br>
  <img src="Screenshots/step2_04_rules_tab_default.webp" alt="Rules tab default" width="700" /><br>
  <em>4. Switch to the Rules tab</em><br><br>
  <img src="Screenshots/step2_05_rules_paste_and_publish.webp" alt="Paste security rules" width="700" /><br>
  <em>5. Replace rules and click Publish</em><br><br>
  <img src="Screenshots/step2_06_rules_published_success.webp" alt="Rules published" width="700" /><br>
  <em>6. Security rules published successfully</em><br><br>
  <img src="Screenshots/step2_07_database_url_copy.webp" alt="Copy Database URL" width="700" /><br>
  <em>7. Copy your Realtime Database URL from the Data tab</em>
</p>
</details>

---

### Step 3: Configure Authentication

1. In the Firebase sidebar, navigate to **Security > Authentication**.
2. Select the **Sign-in method** tab.
3. Under _Additional providers_, select **Google**.
4. Toggle **Enable**, enter a **Public-facing name for project** of your choice, choose a **Project support email**, and click **Save**.

<details>
<summary>View Step 3 Screenshots</summary>
<br>
<p align="center">
  <img src="Screenshots/step3_01_navigate_authentication.webp" alt="Select Google Sign-in provider" width="700" /><br>
  <em>1. Navigate to Authentication &gt; Sign-in method and select Google</em><br><br>
  <img src="Screenshots/step3_02_google_provider_dialog.webp" alt="Configure Google Sign-in dialog" width="700" /><br>
  <em>2. Google Sign-in provider configuration dialog</em><br><br>
  <img src="Screenshots/step3_03_enable_google_provider.webp" alt="Enable Google provider" width="700" /><br>
  <em>3. Toggle Enable, enter public-facing name and support email, then Save</em><br><br>
  <img src="Screenshots/step3_04_google_provider_enabled.webp" alt="Google Sign-in Enabled" width="700" /><br>
  <em>4. Google provider enabled successfully</em>
</p>
</details>

---

### Step 4: Register Android App & OAuth Client

#### 1. Register Android App in Firebase Console

1. In Firebase Console, go to the **Project Overview** page from the sidebar.
2. Under "Select a platform to get started", click the **Android** icon (Add app).
3. Enter the package details:
   - **Android package name**: `org.swiftapps.swiftbackup` _(or tap "Copy Package" in SwiftBackupPrem's Custom Firebase setup screen)_
   - **App Nickname**: `SwiftBackupPersonal` _(or any name you like)_
4. Click **Register app**.
5. Click **Download google-services.json** to save the configuration file.
6. Click **Next** through the remaining setup steps, then click **Continue to console**.
7. On the Project Overview page, click the newly registered app card and select the **Gear icon (Project Settings)**.
8. Scroll down to the **Your apps** section, click **Add fingerprint**, paste your **SHA-1 fingerprint** _(tap "Copy Fingerprint" in the Custom Firebase setup screen in the module)_, and click **Save**.
9. _(Optional)_ Click the **Data privacy** tab on Project Settings and uncheck **Firebase Service Data Sharing**.

<details>
<summary>View Firebase App Registration Screenshots</summary>
<br>
<p align="center">
  <img src="Screenshots/step4_01_add_android_app.webp" alt="Add Android App" width="700" /><br>
  <em>1. Click the Android platform icon on Project Overview</em><br><br>
  <img src="Screenshots/step4_02_register_app_details.webp" alt="Register Android App" width="700" /><br>
  <em>2. Enter package name org.swiftapps.swiftbackup and nickname</em><br><br>
  <img src="Screenshots/step4_03_download_google_services_json.webp" alt="Download google-services.json" width="700" /><br>
  <em>3. Download google-services.json configuration file</em><br><br>
  <img src="Screenshots/step4_04_continue_to_console.webp" alt="Continue to console" width="700" /><br>
  <em>4. Skip SDK setup and continue to console</em><br><br>
  <img src="Screenshots/step4_06_project_overview_app_card.webp" alt="App registered on Overview" width="700" /><br>
  <em>5. App is registered on Project Overview</em><br><br>
  <img src="Screenshots/step4_07_open_project_settings.webp" alt="Open Project Settings" width="700" /><br>
  <em>6. Click the gear icon to open Project Settings</em><br><br>
  <img src="Screenshots/step4_08_project_settings_general.webp" alt="Project Settings General Tab" width="700" /><br>
  <em>7. Project Settings Overview (View in Google Cloud)</em><br><br>
  <img src="Screenshots/step4_09_add_sha1_fingerprint.webp" alt="Add SHA-1 fingerprint" width="700" /><br>
  <em>8. Add SHA-1 fingerprint under Your apps</em><br><br>
  <img src="Screenshots/step4_10_disable_data_sharing.webp" alt="Disable Data Sharing" width="700" /><br>
  <em>9. Optional: Disable Firebase Service Data Sharing</em>
</p>
</details>

#### 2. Configure OAuth 2.0 Client in Google Cloud Console

1. Open the Google Cloud API Credentials Console by clicking **View in Google Cloud** on the Firebase Project Settings page.
2. Ensure your Firebase / Google Cloud project is selected in the top project dropdown.
3. In the sidebar, navigate to **APIs & Services > Credentials**.
4. Under **OAuth 2.0 Client IDs**, edit the auto-generated **Android client for org.swiftapps.swiftbackup**.
5. Under **Advanced settings**, check **Enable custom URI scheme** (click **Yes** in the confirmation popup).
6. Click **Save**.
7. Copy the generated **Client ID** string (e.g., `xxxxxxxxxxxx-xxxxxxxxxxxxxxxx.apps.googleusercontent.com`).

<details>
<summary>View Google Cloud OAuth Client Screenshots</summary>
<br>
<p align="center">
  <img src="Screenshots/step4_11_gcp_dashboard.webp" alt="Google Cloud Console Dashboard" width="700" /><br>
  <em>1. Google Cloud Console Dashboard</em><br><br>
  <img src="Screenshots/step4_12_gcp_navigate_credentials.webp" alt="Navigate to Credentials" width="700" /><br>
  <em>2. Navigate to APIs &amp; Services &gt; Credentials</em><br><br>
  <img src="Screenshots/step4_13_gcp_edit_oauth_client.webp" alt="Edit Android OAuth Client" width="700" /><br>
  <em>3. Under OAuth 2.0 Client IDs, edit the auto-generated Android client</em><br><br>
  <img src="Screenshots/step4_15_gcp_enable_custom_uri_scheme.webp" alt="Enable Custom URI Scheme" width="700" /><br>
  <em>4. Under Advanced settings, enable Custom URI scheme</em><br><br>
  <img src="Screenshots/step4_16_gcp_copy_client_id.webp" alt="Copy Client ID" width="700" /><br>
  <em>5. Copy your generated OAuth Client ID</em>
</p>
</details>

---

### Step 5: Enable Google Drive API & OAuth Scopes

If you plan to use Google Drive for cloud backups:

#### 1. Enable Google Drive API

1. Visit the [Google Cloud Drive API Console](https://console.cloud.google.com/apis/library/drive.googleapis.com).
2. Select your Firebase / Google Cloud project at the top.
3. Click **Enable** to allow Swift Backup to interact with Google Drive via your project.

#### 2. Add Google Drive OAuth Scope

1. Open the [Google Cloud OAuth Scopes Console](https://console.cloud.google.com/auth/scopes).
2. Ensure your project is selected at the top.
3. Navigate to **Data Access** > click **Add or remove scopes**.
4. In the filter box, search for and enable:
   ```
   https://www.googleapis.com/auth/drive.file
   ```
   _(This provides safe, per-file access for files created or opened by Swift Backup without requiring broad drive permissions)._
5. Click **Update**, then click **Save** (or **Save and continue**).

---

### Step 6: Import or Enter Credentials in SwiftBackupPrem

1. Open **SwiftBackupPrem** on your device.
2. Enable **Custom Firebase app**.
3. Choose either method:
   - **Method A (Automatic)**: Tap **Import google-services.json** and pick the downloaded JSON file. Then paste your **OAuth Client ID** into the Client ID field.
   - **Method B (Manual Wizard)**: Follow the guided setup in the app to review and confirm all fields.
4. Tap **Finish & Save**.
5. Tap **Force Stop** at the bottom to kill any running Swift Backup instances, then tap **Open App**.
6. Sign in to Swift Backup with your Google account.

> [!NOTE]
> **Firebase Cloud Storage is Optional (Skip if on Spark Plan)**
>
> Firebase Cloud Storage now requires a paid **Blaze Plan** (linked Cloud Billing account). **You can safely skip enabling Cloud Storage in Firebase Console.**
> Swift Backup does **not** store your backup files (APKs, app data, call logs) inside Firebase Storage. Firebase is only used for authentication and metadata synchronization. Your actual backups are stored in your configured cloud provider (Google Drive, WebDAV, Nextcloud, SMB, etc.) or local storage.

---

## Migrating & Accessing Backups from Default Firebase

Install Swift Backup with the default Firebase configuration and log in to pull your UID from `/data/data/org.swiftapps.swiftbackup/shared_prefs/com.google.firebase.auth.api.Store.*.xml`:

```bash
su -c 'grep -o "GET_TOKEN_RESPONSE\.[^\"]*" /data/data/org.swiftapps.swiftbackup/shared_prefs/com.google.firebase.auth.api.Store.*.xml | cut -d. -f2'
```

> [!IMPORTANT]
> If your account has been banned by the developer of Swift Backup, you will not be able to fetch your UID if you uninstalled the app after the ban, as the `/data/data/org.swiftapps.swiftbackup/shared_prefs/` directory will be deleted. Without this UID, cloud backups cannot be decrypted and will be permanently lost.
> This is the only way to fetch your UID, so do not uninstall the app until you have your UID saved safely somewhere.

### Verify UID with Backups (Optional)

Swift Backup takes the MD5 hash of your Firebase UID and uses the first 16 characters for the folder name:

```bash
echo -n "example uid" | md5sum | cut -c 1-16
```

**Output**: `example16char` -> `/sdcard/SwiftBackup/accounts/example16char/`  
or in case of cloud folder: `Swift Backup (example16char)`

---

### Create the User with this UID in Your Custom Firebase Project

1. Install Firebase tools using your preferred package manager (e.g., via Node.js):

   ```bash
   npm install -g firebase-tools
   ```

2. Run `firebase login` to connect your account:

   ```bash
   firebase login
   ```

3. Create a `users.json` file:

   ```json
   {
     "users": [
       {
         "localId": "example uid",
         "email": "examplemail@gmail.com",
         "emailVerified": true,
         "displayName": "examplename"
       }
     ]
   }
   ```

4. View your Project ID:

   ```bash
   firebase projects:list
   ```

5. Run the import command:

   ```bash
   firebase auth:import users.json --project exampleprojectID
   ```

6. Log in again to Swift Backup.

---

## Backup Migration

SwiftBackupPrem features a powerful, built-in **Backup Migration Tool** (accessible by tapping the **Backup Migration** card from the main screen).

The Migration Tool bridges the gap between different Firebase environments and cloud storage configurations through two dedicated tabs:

1. **Local Migration**: An on-device cryptographic and metadata reconstruction tool to decrypt, convert, and re-encrypt existing backup folders across different Firebase UIDs, anonymous keys, or unencrypted portable formats.
2. **Cloud Discovery & Injection**: A runtime hooking and scanning engine that discovers backups across diverse cloud providers (Google Drive, OneDrive, Dropbox, Box, pCloud, S3, WebDAV / Nextcloud) and injects them directly into Swift Backup's restore screens without requiring prior database records.

---

### Local Migrator (Decrypt, Convert & Re-encrypt)

Swift Backup encrypts application data (`.dat`, `.extdat`, `.med`), system payloads, and directory archives (`folder-base.fld`) using Facebook Conceal (AES-256-GCM + Zstandard compression) keyed against the user's active Firebase UID. If you switch to a custom Firebase backend, set up a new account, or recover backups after being banned from the official backend, the encryption key changes—making older backups unreadable by the new account.

The **Local Migrator** resolves this entirely on-device without requiring internet access or root shell commands. It recursively inspects backup folders, decrypts archives using the source key, reconstructs missing metadata, and re-encrypts or extracts the data into your chosen target format.

#### 4-Step Migration Workflow

1. **Step 1: Source Folder**
   - Enter the path to your existing backup directory or tap the folder icon to select it via the system Document picker.
   - The engine recursively walks the directory tree (up to 20 levels deep) to identify all app backup directories (containing `.app`, `.apk`, `.dat`, `.splits`, `.extdat`, `.med`, `.xml`, or `.extra`) and folder backups (starting with `Folder-` or containing `folder-base.*` / `metadata.json`).

2. **Step 2: Decryption Key (Source UID)**
   - Enter or paste the Firebase UID that originally encrypted the backups.
   - **Key Presets & Detected UIDs**: Tap the refresh icon to auto-detect candidate Firebase UIDs found in Swift Backup's local configuration, cached tokens (`.sbp_auth_state`), or preferences.
   - **Shared Anonymous Key**: Tap the **Shared Anonymous Key** chip to fill in the built-in anonymous key (`d58b0944415a4889d7f11aa95fbeca50`) if migrating from anonymous offline backups.

3. **Step 3: Target Encryption Mode**
   Choose how you want the migrated backups to be packaged:
   - **Shared Anonymous Key (Recommended)**: Re-encrypts archives using Swift Backup's static anonymous key. Output can be restored in Swift Backup **offline without logging into any Firebase account**.
   - **Custom Firebase UID**: Re-encrypts archives specifically for your custom Firebase project UID.
   - **Unencrypted Backups**: Strips AES-256-GCM Conceal encryption completely from data slices and keeps Swift Backup's directory layout.
     - **Portable Formats Checkbox**: When Unencrypted mode is active, check **Convert to standard portable formats (.apk, .tar.gz, .json)** to extract standard standalone files into `ExtractedBackups/`:
       - `.app` / `.apk` &rarr; `<packageName>.apk`
       - `.splits` &rarr; `<packageName>_splits.tar`
       - `.dat` &rarr; `<packageName>_data.tar`
       - `.extdat` &rarr; `<packageName>_external_data.tar`
       - `.med` &rarr; `<packageName>_media.tar`
       - `.extra` &rarr; `<packageName>_extras.json`
       - `.xml` &rarr; `<packageName>_metadata.json`
       - `.cls` &rarr; `<packageName>_call_logs.json`
       - `.msg` &rarr; `<packageName>_sms_messages.json`
       - `.wfi` &rarr; `<packageName>_wifi.json`
       - `.wal` &rarr; `<packageName>_wallpaper.png`
       - `folder-base.fld` &rarr; `<folderName>.tar`
       - `folder-base.flm` &rarr; `<folderName>_manifest.json`

4. **Step 4: Destination Folder & Start Migration**
   - Enter the output directory (defaults to `/storage/emulated/0/SwiftBackup`).
   - The engine organizes files into Swift Backup's standard hierarchy:
     - `SwiftBackup/accounts/<accountHash>/backups/apps/local/<packageName>/<backupId>/`
     - `SwiftBackup/accounts/<accountHash>/backups/folders/local/<folderName>/`
   - Tap **Start Migration**. The screen displays real-time progress indicators (item count, percentage bar, and current package), along with live execution logs.
   - **Metadata Reconstruction**: If the original `.xml` metadata is missing, the engine automatically extracts metadata from the APK manifest (`versionCode`, `versionName`, app label) and decrypts the `.extra` payload for SSAID, permission states, and notification policy settings.

> [!NOTE]
> **Storage Permission Notice**  
> On Android 11+ (API 30+), the app requires the **Manage External Storage (All Files Access)** permission to scan and write backup folders across storage volumes. If prompted, grant access via system settings.

---

### Universal Cloud Discovery & Snapshot Injection

Swift Backup normally depends strictly on Firebase Realtime Database (RTDB) records to discover and list cloud backups. If an RTDB record is missing (such as when using a Custom Firebase backend, restoring after an account ban, or restoring across devices), Swift Backup cannot see the backups stored in your cloud, even if the files exist in your cloud drive.

The **Cloud Discovery & Injection** option bypasses this limitation entirely. It scans configured cloud storage remotes, extracts metadata on the fly, and injects snapshots into Swift Backup's runtime memory.

> [!TIP]
> Cloud Discovery & Snapshot Injection is functional in **both** setup modes:
>
> 1. **Local Account Cloud Features**: Works completely offline with zero Firebase setup.
> 2. **Custom Firebase App**: Works alongside your private Firebase instance.

#### 1. Google Drive Full OAuth Scope Expansion

- **Default Swift Backup Behavior**: Requests the restricted `https://www.googleapis.com/auth/drive.file` scope, limiting file visibility strictly to files created by the current app session.
- **Expanded Scope**: When **Google Drive Full OAuth Scope** is enabled, the module dynamically intercepts OAuth request builders, URI builders, and authentication intents during sign-in, upgrading the requested scope to:
  ```
  https://www.googleapis.com/auth/drive
  ```
- **Result**: Grants Swift Backup visibility to query and restore backups uploaded from different devices, previous ROMs, or prior accounts.

> [!WARNING]
> **"Google hasn't verified this app" Notice**:  
> Because `https://www.googleapis.com/auth/drive` is classified as a sensitive scope, Google Cloud displays an unverified app warning during Google Sign-In. Click **Advanced > Go to Swift Backup (unsafe)** to proceed. This is standard behavior for personal developer projects.

#### 2. Universal Cloud Discovery

When **Universal Cloud Discovery** is toggled on, SwiftBackupPrem scans across all connected remote storage providers supported by Swift Backup:

| Provider                          | Scanner Details & Capabilities                                                                                                       |
| :-------------------------------- | :----------------------------------------------------------------------------------------------------------------------------------- |
| **Google Drive**                  | Scans Swift Backup root directories, resolves file IDs, and parses nested app directories via Google Drive API v3.                   |
| **Microsoft OneDrive**            | Scans Swift Backup root folders, encodes relative paths, and handles batch Microsoft Graph API pagination with HTTP range downloads. |
| **Dropbox**                       | Scans Dropbox API v2 endpoints to map application and folder backup hierarchies.                                                     |
| **Box**                           | Scans Box storage trees using the Box REST API v2.                                                                                   |
| **pCloud**                        | Scans cloud archives and directory trees via pCloud REST APIs.                                                                       |
| **Amazon S3 / S3-Compatible**     | Scans buckets using AWS S3 API (supports MinIO, Wasabi, Backblaze B2, Ceph, etc.).                                                   |
| **WebDAV / Nextcloud / ownCloud** | Uses WebDAV XML `PROPFIND` queries to traverse remote directories and discover backup slices.                                        |

##### Supported Cloud Components:

- **Applications**: APKs (`.app`/`.apk`), split APKs (`.splits`), app data (`.dat`), external data (`.extdat`), media (`.med`), and metadata (`.extra`).
- **Folders**: Custom folder archives (`.fld`, `.flm`, `metadata.json`).
- **System Data**: Call logs (`.cls`), SMS messages (`.msg`), Wallpapers (`.wal`, `.wal.png`), and Wi-Fi configurations (`.wfi`).

##### Remote APK Manifest Parsing via HTTP Range Requests:

To display accurate app titles, package names, and version codes without downloading multi-gigabyte APKs over mobile data, the discovery engine incorporates `ApkRangeManifestParser`. It performs HTTP Range requests to fetch only the ZIP central directory and parse `AndroidManifest.xml` remotely in seconds.

#### 3. Realtime DB Snapshot Injection

The module includes an in-memory **Firebase Snapshot Synthesizer**:

- Hooks into Swift Backup's internal Firebase queries and `DataSnapshot` handlers (single-app details listeners, batch cloud restore loaders, app filter helpers, cloud sync tab, and folder loaders).
- Synthesizes live `DataSnapshot` objects formatted identically to native RTDB entries under the `cloud_v1` hierarchy.
- **Result**: Discovered cloud backups appear immediately in:
  - **Single App Details** (with all restorable parts: APK, Data, Ext Data, Splits)
  - **Cloud Sync Tab** (with accurate backup timestamps and device tags)
  - **Batch Restore Screen** (allowing one-tap bulk restoration)
  - **Cloud Backup Tags Dropdown** (filtering backups by device model / tag)

#### 4. Cloud Discovery Cache

Discovered cloud backups are saved locally to:

```
/sdcard/SwiftBackup/cloud_discovered_cache.json
```

- Provides instant offline navigation across restore screens without waiting for network scans on every screen launch.
- An in-memory cache TTL (60 seconds) prevents redundant cloud network requests during rapid screen switching.
- Tap **Clear Cloud Cache** in the app at any time to purge cached entries and trigger an immediate fresh scan across your cloud providers.

---

### Firebase Realtime Database Metadata Sync

While Snapshot Injection dynamically provides in-memory restore entries, you can also permanently persist discovered and reconstructed metadata to your private Firebase Realtime Database using the **Sync Metadata to Custom Firebase** feature:

- **Native `cloud_v1` Structure**: The sync engine writes directly to Swift Backup's official RTDB schema:
  ```
  /users/<UID>/cloud_v1/<provider_key>/tags/<device_tag>/apps/<sanitized_pkg>/<backup_id>
  /users/<UID>/cloud_v1/<provider_key>/tags/<device_tag>/folders/<folder_id>
  ```
  _(where `<provider_key>` is resolved in the format `<provider> (<sanitized_email>)`)_.
- **Smart Deduplication**: Before writing, the sync engine queries your existing RTDB `cloud_v1` tree to skip existing records and avoid redundant write operations.
- **Token Resolution & Authentication**: Automatically resolves Firebase Auth ID tokens from Swift Backup's OAuth session or exchanged refresh tokens, ensuring secure authenticated database writes.
- **Sync Now Button**: Tap the **Sync Now** button inside the Cloud Discovery tab to trigger an immediate full sync of all local and cloud metadata records.

---

## LSPatch Rootless Integration & In-App Settings

SwiftBackupPrem provides complete, first-class support for rootless setups using [LSPatch](https://github.com/JingMatrix/LSPatch) (the actively maintained **[JingMatrix/LSPatch](https://github.com/JingMatrix/LSPatch)** fork is recommended):

### 1. In-App Settings Injection (Integrated Mode)

When patched using LSPatch's **Integrated Mode**, SwiftBackupPrem's hook engine embeds directly into Swift Backup:

- **Native Settings Menu**: Module preferences are injected into Swift Backup's settings hierarchy under **Settings → SwiftBackupPrem**.
- **Streamlined Experience**: You can toggle Premium Features, Telemetry Suppression, Local Account Cloud Features, and Custom Firebase directly inside Swift Backup without needing an external manager app.

### 2. Root Service Fix (`RootServiceFixHook`)

Under LSPatch, root daemon processes launched by `libsu` (`RootService`) for SBA root backups would normally crash (`SIGABRT` / `GetObjectField(null)` in `liblspatch.so`) because bare daemon processes lack Android `Application` and `ActivityThread` contexts.

SwiftBackupPrem solves this transparently:

- Intercepts root process startup tasks and wraps execution in an isolated Linux mount namespace (`unshare -m`).
- Inside the private namespace, it bind-mounts the original, unpatched APK over the patched wrapper.
- Root SBA backup and restore daemons start and bind cleanly with zero LSPatch crashes.

---

## Configuration Export & Migration

- **Export Config**: Tap the top-right menu (⋮) > **Export Config** to save your active configuration to a JSON file (`sbp_config.json`).
- **Import Config**: Tap **Import Config** to restore your settings in a single tap on a new device or fresh ROM install.

---

## Building from Source

### Prerequisites

- JDK 17+ (e.g. Adoptium Eclipse Temurin 17 or 25)
- Android SDK with Platform 37 (`compileSdk 37`)
- Android NDK (`29.0.14206865` or higher) & CMake `3.22.1+`

### Build Steps

1. Clone the repository:

   ```bash
   git clone https://github.com/s1ddhants1/SwiftBackupPrem.git
   cd SwiftBackupPrem
   ```

2. Run unit tests:

   ```bash
   ./gradlew testDebugUnitTest
   ```

3. Build debug APK:

   ```bash
   ./gradlew assembleDebug
   ```

4. Build optimized release APK:

   ```bash
   ./gradlew assembleRelease
   ```

The compiled APK will be located in `app/build/outputs/apk/release/app-release.apk`.

### Branch Structure

- **[`main`](https://github.com/s1ddhants1/SwiftBackupPrem/tree/main)**: Modern LibXposed (API 101/102+) implementation for LSPosed v2.0.0+, Vector, and LSPatch.
- **[`legacy`](https://github.com/s1ddhants1/SwiftBackupPrem/tree/legacy)**: Classic Xposed API implementation for older Android environments (EdXposed / classic Xposed).

---

## Community & Support

Join the official Telegram group for discussion, support, release updates, and assistance:

<p align="center">
  <a href="https://t.me/SwiftBackupPrem">
    <img src="https://img.shields.io/badge/Join%20Telegram%20Group-SwiftBackupPrem-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white" alt="Join Telegram Group" />
  </a>
</p>

- **Group Link**: [https://t.me/SwiftBackupPrem](https://t.me/SwiftBackupPrem)
- **Get Help**: Ask troubleshooting questions or share setup tips.
- **Releases & APKs**: Get direct download links and release notifications directly inside Telegram.

---

## Frequently Asked Questions (FAQ)

<details>
<summary><b>Q: Do I need to set up Firebase if I only want cloud backups?</b></summary>
<p><b>No!</b> Simply enable <b>Local Account Cloud Features</b> in SwiftBackupPrem and choose <b>Local Account</b> in Swift Backup. You can then connect your cloud storage provider (WebDAV, Nextcloud, OneDrive, Dropbox, Box, pCloud, S3, etc.) and perform cloud backups and restores completely offline with <b>zero Firebase configuration</b>.</p>
</details>

<details>
<summary><b>Q: What is the difference between Local Account Cloud Features and Custom Firebase App?</b></summary>
<p><b>Local Account Cloud Features</b> is designed for offline/local accounts without sign-in, virtualizing Realtime Database records locally and on your cloud drive. <b>Custom Firebase App</b> is for users who want to sign in with their Google account inside Swift Backup, expand Google Drive OAuth scopes to full access (restoring across previous ROMs), and sync catalog records to their private Firebase Realtime Database.</p>
</details>

<details>
<summary><b>Q: How do I use SwiftBackupPrem with LSPatch (Rootless)?</b></summary>
<p>We recommend using the actively maintained <b><a href="https://github.com/JingMatrix/LSPatch">JingMatrix/LSPatch</a></b> fork (as the original repository is archived). You can use either <b>Manager Mode</b> or <b>Integrated Mode</b>. In Manager Mode, patch Swift Backup with LSPatch and keep SwiftBackupPrem installed as a companion app. In Integrated Mode, embed SwiftBackupPrem directly into the patched APK; module settings will be accessible directly inside Swift Backup under <b>Settings → SwiftBackupPrem</b>.</p>
</details>

<details>
<summary><b>Q: Can I use SwiftBackupPrem on Android TV?</b></summary>
<p><b>Yes!</b> SwiftBackupPrem includes a native Android TV Leanback banner and full D-pad focus traversal. You can sideload the APK onto your Android TV or Google TV box and launch it directly from the TV home screen.</p>
</details>

<details>
<summary><b>Q: I see "LSPosed Module Not Enabled" in the app.</b></summary>
<p>Make sure you have:</p>
<ol>
  <li>Enabled <b>SwiftBackupPrem</b> inside LSPosed Manager.</li>
  <li>Added <b>Swift Backup</b> (<code>org.swiftapps.swiftbackup</code>) to the module scope.</li>
  <li>Force stopped Swift Backup or rebooted your device.</li>
</ol>
</details>

<details>
<summary><b>Q: Why do I need a Custom Firebase project? Will I get banned without it?</b></summary>
<p>By default, Swift Backup authenticates with the official developer's Firebase backend. The official server performs periodic license checks, anti-tampering verification, and telemetry detection. If unauthorized or modified app usage is detected, the developer can disable/ban your account on their Firebase instance, revoking your access to Swift Backup and your backup metadata.</p>
<p>Connecting your own personal Firebase backend gives you <b>100% isolation</b>: authentication and metadata stay on your private cloud where no external server can revoke your account.</p>
</details>

<details>
<summary><b>Q: Do I need a paid Firebase Blaze plan or Cloud Storage?</b></summary>
<p><b>No!</b> The 100% free <b>Firebase Spark plan</b> is completely sufficient. Swift Backup only utilizes Firebase Authentication and Realtime Database for account identity and sync metadata. Your actual backup archives (APKs, app data, etc.) are stored on your personal cloud provider (e.g., Google Drive, WebDAV, Nextcloud), not Firebase Storage.</p>
</details>

<details>
<summary><b>Q: Google Sign-In fails with Error Code 10 or Error Code 12500.</b></summary>
<p>This indicates an OAuth mismatch, incorrect Client ID, or missing API configuration:</p>
<ol>
  <li><b>Check Client ID Type:</b> In SwiftBackupPrem settings, ensure you entered the <b>Android OAuth Client ID</b>, not the Web Client ID.</li>
  <li><b>Verify SHA-1 Fingerprint:</b> Use the <b>Copy Fingerprint</b> helper in SwiftBackupPrem's Guided Setup and ensure it matches the SHA-1 added to your Android OAuth Client and Firebase Android App settings.</li>
  <li><b>Custom URI Scheme:</b> Ensure <b>Enable custom URI scheme</b> is checked in Google Cloud Console > Credentials > Android OAuth Client.</li>
  <li><b>Enable Google Drive API:</b> Verify that <b>Google Drive API</b> is enabled under APIs &amp; Services in Google Cloud Console.</li>
</ol>
</details>

<details>
<summary><b>Q: Google Drive shows "Google hasn't verified this app" during sign-in.</b></summary>
<p>This is expected. When <b>Google Drive Full OAuth Scope</b> is enabled, the module requests the full <code>https://www.googleapis.com/auth/drive</code> scope so Swift Backup can discover and rebuild backups created across past accounts or ROM installs.</p>
<p>Because your Google Cloud project is personal and unverified, Google shows a standard security notice. Click <b>Advanced > Go to Swift Backup (unsafe)</b> to proceed.</p>
</details>

<details>
<summary><b>Q: My account was banned on the default Firebase backend. Can I recover my old backups?</b></summary>
<p><b>Yes, provided you still have your old Firebase UID key.</b></p>
<p>Swift Backup encrypts backup archives (<code>.dat</code>, <code>.extra</code>) using AES-256-GCM + Zstandard with your Firebase <code>UID</code> as the decryption key. If you extract your old UID (see the <a href="#migrating--accessing-backups-from-default-firebase">Migration Guide</a>) and create a user with that exact same UID in your Custom Firebase project, SwiftBackupPrem will be able to decrypt and restore all your previous backups.</p>
</details>

<details>
<summary><b>Q: I uninstalled or wiped Swift Backup after being banned. Can I still recover my old backups?</b></summary>
<p>Unfortunately, <b>no</b>. When you uninstall or clear data for Swift Backup, the local <code>/data/data/org.swiftapps.swiftbackup/shared_prefs/</code> directory containing the cached Firebase authentication token is deleted. Because the official server has disabled your account, you cannot log in to retrieve the original UID. Without the original UID key, the AES-256-GCM encrypted data cannot be decrypted.</p>
<p><i>Recommendation: Always back up your Firebase UID or export your SwiftBackupPrem configuration (<code>sbp_config.json</code>) to safe storage.</i></p>
</details>

<details>
<summary><b>Q: How do I verify that my extracted UID matches my backup folder?</b></summary>
<p>Compute the MD5 hash of your raw UID string (for example, using an online MD5 tool or <code>echo -n "YOUR_UID" | md5sum</code>). Compare the <b>first 16 hexadecimal characters</b> of the MD5 hash with the name of the backup folder on your storage or Google Drive. If they match, you have the exact UID needed to restore those backups.</p>
</details>

<details>
<summary><b>Q: <code>firebase auth:import</code> fails with "No hash algorithm specified" or project error.</b></summary>
<p>Ensure you run <code>firebase projects:list</code> to obtain your exact <b>Project ID</b> (not the display name). Use:</p>
<pre><code class="language-bash">firebase auth:import users.json --project YOUR_PROJECT_ID
</code></pre>
</details>

<details>
<summary><b>Q: Why do uninstalled apps show as package names or missing icons during Cloud Restore?</b></summary>
<p>When backups are indexed directly from cloud metadata for apps not currently installed on your device, Swift Backup falls back to displaying the package identifier recorded in the backup headers. Once restored or installed locally, Android resolves the full display name and application icon normally.</p>
</details>

<details>
<summary><b>Q: How do I access and use the Backup Migration Tool?</b></summary>
<p>SwiftBackupPrem includes a built-in <b>Backup Migration Tool</b> (accessible via the <b>Backup Migration</b> card on the main screen by tapping <b>Open Migrator</b>):</p>
<ul>
  <li><b>Local Migration Tab:</b> Decrypts existing backup folders with your source Firebase UID, rebuilds missing metadata, supports 3 destination encryption modes (Shared Anonymous Key, Custom Firebase UID, or Unencrypted Backups with optional portable extraction), and re-encrypts or extracts them for offline or new account restore.</li>
  <li><b>Cloud Discovery &amp; Injection Tab:</b> Provides granular controls for:
    <ul>
      <li><b>Google Drive Full OAuth Scope:</b> Dynamically expands OAuth scopes to <code>auth/drive</code> to discover backups created across accounts or past ROMs.</li>
      <li><b>Universal Cloud Discovery:</b> Scans and indexes backups across Google Drive, OneDrive, Dropbox, Box, pCloud, S3, and WebDAV / Nextcloud.</li>
      <li><b>Realtime DB Snapshot Injection:</b> Injects discovered cloud backups directly into Swift Backup restore lists on the fly via synthetic <code>DataSnapshot</code> objects.</li>
      <li><b>Sync Metadata to Custom Firebase:</b> Pushes reconstructed local and discovered cloud backup metadata directly into your private Firebase Realtime Database (<code>cloud_v1</code> hierarchy).</li>
      <li><b>Cloud Discovery Cache:</b> Manages the local discovery cache for fast offline access, with a 1-tap Clear Cache button.</li>
    </ul>
  </li>
</ul>
</details>

<details>
<summary><b>Q: Does Cloud Backup Restore support Call Logs, SMS, Wallpapers, Wi-Fi networks, and Folders?</b></summary>
<p>Yes. The Cloud Discovery and Backup Rebuilder engine indexes and restores Call Logs (<code>.cls</code>), SMS messages (<code>.msg</code>), Wallpapers (<code>.wal</code>/<code>.wal.png</code>), Wi-Fi configurations (<code>.wfi</code>), and Folder backups (<code>.fld</code>/<code>.flm</code>) alongside full application archives (<code>.app</code>, <code>.dat</code>, <code>.splits</code>, <code>.extdat</code>, <code>.extra</code>). Folder metadata (<code>metadata.json</code>) is also automatically reconstructed if missing.</p>
</details>

<details>
<summary><b>Q: Can SwiftBackupPrem be used with cloned or work profile instances of Swift Backup?</b></summary>
<p>Yes. Ensure the LSPosed module scope covers the cloned instance or secondary user profile, and verify that root access and storage permissions are properly granted to that profile space.</p>
</details>

<details>
<summary><b>Q: How does DexKit work in this module?</b></summary>
<p>Swift Backup obfuscates its classes with ProGuard/R8 across different releases. Instead of hardcoding static class names and signatures that break on every update, DexKit inspects bytecode structures dynamically at runtime to locate the required hooks automatically and caches the results for optimal performance.</p>
</details>

---

## Credits & Acknowledgements

- **[Juby210](https://github.com/Juby210)** — Original creator and author of SwiftBackupPrem.
- **[s1ddhants1](https://github.com/s1ddhants1)** — Maintainer & Lead Developer.
- **[LuckyPray/DexKit](https://github.com/LuckyPray/DexKit)** — Powerful runtime DEX search and hooking engine.
- **[LSPosed](https://github.com/LSPosed/LSPosed)** — ART hooking framework for modern Android.
- **[JingMatrix/LSPatch](https://github.com/JingMatrix/LSPatch)** — Rootless Xposed hooking framework (actively maintained fork).

---

## License & Disclaimer

This project is licensed under the [MIT License](LICENSE).

**Disclaimer**: This project is intended strictly for personal, educational, and backup management purposes. Swift Backup is developed by SwiftApps. If you enjoy Swift Backup, consider supporting the official developers.
