# Penny — Private Android Financial Tracker

Penny is an offline-first Android app that reads financial SMS alerts only after the phone owner grants permission. It detects likely transactions, stores them locally, calculates monthly income and spending, and exports a CSV backup.

## Version 0.3
- Adds **Import email receipt**: copy receipt text from Gmail, paste it into Penny, then review the merchant, total (including fees), income/expense type and original transaction date before saving.
- Identical pasted receipts are deduplicated, including whitespace changes. SMS/email cross-source duplicates are not automatically reconciled.
- Email import is manual. This version does not connect to Gmail or monitor the inbox. Automatic Gmail sync requires a Google Cloud OAuth app configured for Penny and an authenticated Gmail integration.
- Amount detection requires an explicit UGX/USh/Sh currency marker to avoid treating phone numbers and transaction references as amounts. Check every imported result against its source.
- Reads existing financial SMS after permission is granted.
- Tracks new incoming transaction alerts automatically.
- Ignores likely OTP, PIN and verification messages.
- Shows monthly income, spending and net movement.
- Keeps transaction data in a local SQLite database.
- Exports all detected records to CSV.
- Includes a GitHub Actions workflow that runs parser tests and builds an installable debug APK without Android Studio.

## Privacy model
- No INTERNET permission.
- No contacts, location, camera, microphone or SEND_SMS permission.
- SMS content and financial records remain on the phone.
- Android backups are disabled.

## Build the APK using GitHub
1. Use the extracted source project at the repository root, preserving its folder structure.
2. Open the repository's **Actions** tab.
3. Select **Build Penny APK**.
4. Tap **Run workflow**.
5. When the build finishes, open it and download the **Penny-debug-apk** artifact.
6. Extract the downloaded artifact and install `app-debug.apk` on your Android phone.

Android may ask you to allow installation from your browser or file manager. This build is for private testing and is not a Play Store release.

## Important limitation
Financial SMS formats differ by bank and mobile-money provider. The generic parser will need adjustment using anonymised examples from the exact MTN, Airtel and bank alerts used on your phone.

Only UGX transactions are supported. SMS imports retain the existing automatic-save behavior; email imports require review. Never add another currency to a UGX total. An imported email's total should include any fee. Receipt text is processed locally; the database stores transaction fields and a receipt fingerprint, not the full pasted body. SMS inbox import currently runs on the main thread and may pause the interface for large inboxes.

For automatic Gmail sync, configure Google OAuth for Android package `com.karic.penny` and the signing certificate used by the installed app. Gmail read access and user consent must be configured separately; connecting Gmail to Codex does not configure Penny. Do not commit tokens, credentials, or real receipts to this public repository.
