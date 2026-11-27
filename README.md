# AI Command Center

Native Kotlin Android app for composing reusable AI instructions and inserting them into existing chats. Android 8.0+ (API 26), compile/target API 35. No AI accounts or API keys required. AndroidX Core provides secure APK sharing. Internet access is used for checking and downloading app updates only.

## Setup
1. Install a built APK. Open AI Command Center and select the AI apps under **AI apps**. Any installed launchable app may be selected explicitly; do not select browsers or unrelated apps unless you want the overlay in them.
2. Under **Projects**, add an `owner/repository` name (or GitHub repository URL). Projects are local command profiles; the app does not access GitHub.
3. Under **Commands**, add a title and instruction text. Select neither restriction for universal, an AI app only for app-specific, a project only for shared project commands, or both for an app/project pair. `{{repo}}` and `{{repo_url}}` are substituted when a project is selected.
4. Under **Setup**, read the disclosure and enable the accessibility service in Android Settings. Depending on OS/installation method, Android may require enabling restricted settings from the app info page first. This is a user-granted system setting, not something the app bypasses.
5. Open an allowed AI app. The floating AI button appears when its application window is active and the device is unlocked. Drag vertically to reposition; tap to expand. Choose a project for the current app, tap the message field in your AI app to focus it, then choose an individual command or a group. The service appends the instructions to the existing draft; it does not submit the message. Review the result and press the AI app's send button yourself.
6. If the app's text field does not expose a readable, focused, editable accessibility node with SET_TEXT support, Android 13+ can try the active accessibility input connection without replacing Gboard. This fallback requires a supported non-sensitive multiline editor, complete readable draft, cursor at the end and no active composition. Finish the current word (e.g. press space) and retry; otherwise use the explicit copy fallback and paste manually. Some Android versions or OEMs restrict clipboard access from a nonfocused overlay: use the standalone Composer instead if necessary. The composer can preview, copy, share, or copy and launch a selected AI app. Sharing availability depends on each receiver.

## Permissions and privacy
- `BIND_ACCESSIBILITY_SERVICE` on the service: only Android can bind the service. User must enable it in Settings after disclosure. Window events and window-content capability are necessary to identify the active app and edit the focused input. The service does not capture screenshots, record keystrokes, read conversation history, or store/transmit target-app text. It reads the focused field only when a command button is tapped or the user-enabled shortcut runs. The Android 13+ editor bridge tracks selection and composition positions (not text); it requests draft text only after an explicit command tap.
- `TYPE_ACCESSIBILITY_OVERLAY`: draws the small nonfocusable overlay using the enabled accessibility service. No `SYSTEM_ALERT_WINDOW`, usage access, foreground service, notification, all-files, contacts, camera or microphone permission is requested. The updater requests INTERNET, ACCESS_NETWORK_STATE and REQUEST_INSTALL_PACKAGES; installation requires user confirmation. Launcher-intent package queries provide the app picker without `QUERY_ALL_PACKAGES`.
- Local preferences hold selected packages, projects, command text, and standalone draft. Manual JSON import/export uses Android's system file picker. Import replaces profiles after confirmation and disables the overlay until manually enabled. There is no automatic backup or cloud synchronization. Do not store credentials in instructions.
- The floating button hides when an unselected app becomes active or the screen locks. Split-screen is tied to the active window, not all visible apps. A selected browser would show it on all sites in that browser; there is no per-site detection. AI app changes, protected fields and OEM restrictions may prevent direct insertion.
- Android may stop the service if the user disables it, force stops the app, or the OEM restricts it. On Vivo/OriginOS, inspect app battery/background restrictions and auto-start options if the service fails to stay available; setting names vary. No blanket battery exemption is requested. Google Play distribution of a non-accessibility-tool app using this API requires reviewing applicable disclosure and Accessibility API policies.

## Build
Install JDK 17, Android SDK platform 35/build-tools 35.0.0, and Gradle 8.11.1. Open the root folder in Android Studio and sync, or set `ANDROID_HOME` and run:

```sh
gradle :app:assembleDebug :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Gradle wrapper binaries are not bundled; Android Studio can use an installed Gradle distribution or you can run `gradle wrapper --gradle-version 8.11.1` once with Gradle installed.

## Create the repository and publish releases

From a freshly extracted source directory, on a computer with Git, GitHub CLI (`gh`), JDK 17, and OpenSSL installed, run:

```sh
gh auth login
bash scripts/create-repo.sh
```

The script asks before creating a public `ai-command-center` repository under your authenticated account (or pass `owner/repository` as its argument). It creates a permanent PKCS12 signing key outside the repository, configures repository secrets before pushing, and pushes the source to `main`. Keep encrypted backups of the key and password in `~/.ai-command-center-signing`. Never commit them. If setup fails midway, inspect the existing repo and secrets before resuming manually; the script refuses to overwrite an existing repository. Git must have your commit identity configured, and the CLI account must have permission to create public repositories and set Actions secrets. Public releases are necessary for token-free updates. For an already-created repository with no published releases, use the one-time recovery script instead:

```sh
gh auth login
bash scripts/configure-signing.sh topgun1111/Ai-Command-Center
```

It validates the repository and release history, creates or reuses a local permanent signing key, uploads the four Actions secrets, and starts the release workflow. It refuses to bootstrap after any release exists or replace existing signing secrets without a local key backup. Keep the backup private and encrypted. Run on your own authenticated computer, not in an untrusted CI environment.

CI builds and lints a signed release APK on `main` pushes or manual runs; it publishes only GitHub Releases, no Actions artifact uploads. The version code is `1000 + GITHUB_RUN_NUMBER`; the displayed version is `1.3.<versionCode>-<7-character commit SHA>`. Do not reset the workflow run numbering or lower the version code if migrating repositories. Reruns do not overwrite already published APKs or replace a newer release. A missing signing secret fails the build instead of publishing an incompatible APK. The release keystore is configured through `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. If you already installed an APK signed with another key, export profiles first; Android will not allow a differently signed update. The first official signed release establishes the upgrade chain.

### In-app updater
Open **Updates** → **Check for updates**. The repository is embedded at build time from `GITHUB_REPOSITORY` (local override: `-PupdateRepo=owner/repository`). The updater checks the public latest release, offers newer numeric versions only, and uses the Android DownloadManager so a download is independent of the activity. Use Refresh download status after returning to the app. It verifies SHA-256, byte length, package ID, version code and matching signing certificate before opening Android's installer. Enable **Install unknown apps** for AI Command Center when prompted and tap Verify and install again. Android confirms installation; no silent install or accessibility-based installer interaction is performed. A successful same-key upgrade retains local profiles, drafts and settings. Downloads may be reclaimed by Android or interrupted by force-stop/OEM restrictions; cancel and retry if needed. Local debug builds use the local debug certificate and cannot upgrade an official release. The updater is intended for sideloaded GitHub releases, not Google Play self-updates. No AI prompts or profile content are included in update requests.

## Verification
Run `bash tests/run.sh` with Kotlin compiler and Java installed. It checks scopes, ordering, repo substitutions, duplicate blocks, empty and Unicode inputs, and repo validation. The Android UI/service need an SDK build and real-device testing. See `DEVICE_TESTS.md`.

### Partial (delta) updates — v1.2
The updater recommends a complete APK on unmetered Wi-Fi, and a compatible delta on mobile or metered connections. Both choices are shown with sizes when available. The user confirms the download; selection is based on the connection at check time. Downloads continue through DownloadManager. A delta is used only for an exact SHA-256 match of the installed monolithic APK, and only when the patch is more than 10% smaller. Split APK installs and skipped releases outside the available patch history fall back to the full APK with confirmation.

CI creates binary patches from up to five preceding stable releases using `scripts/apk_delta.py`, attaching them to the new GitHub Release along with the complete signed APK, never Actions artifacts. The Kotlin decoder reconstructs the exact new signed APK, with bounded reads and writes, verifies base and patch digests and final APK size/digest, and then runs the normal package/version/signing checks. Android installation still requires user confirmation and the same signing key. If a patch fails, cancel/clear it and check again, selecting Full APK. The first install and upgrade from an updater that does not understand deltas require the full APK. No native binary or third-party patch library is required. The delta generator/decoder cross-language tests require Python 3.9+ and Kotlin/JDK.

### Integration methods — v1.3
Open **Integrations** and choose a method to enable or configure it. The Setup master switch and AI apps allowlist apply to all six methods. Floating button remains on by default for backward compatibility; all new methods are opt-in. Gboard remains unchanged. In **AI apps**, assign an active GitHub project even when no overlay is enabled.

- Floating button: independent switch, draggable position, reset position.
- Edge swipe: independent switch, left/right side and handle width; swipe inward on the inset handle. It is an accessibility overlay, not a system-wide gesture.
- Text shortcuts: editable unique triggers (e.g. `#all;`, `#uni;`, `#app;`, `#project;`), scope or individual command targets, and a 450 ms debounce. The semicolon and final-caret requirement reduce unintended expansion. No network or persistent recording of target drafts; text-change events are enabled only when shortcuts are enabled with consent. Password and unrelated app fields are rejected. Some editors or Gboard composition behavior may prevent expansion.
- Text selection: select editable text and choose AI Commands; select scope/command and return the expanded selection. Android must supply a verified selected caller and writable selection. Apps that do not expose PROCESS_TEXT are unsupported. The component is enabled only when this method is enabled.
- Quick Settings: manually add AI Commands tile. Choose menu or append-all mode. Tap from the shade while a selected app was active, and return within eight seconds; Android 12+ attempts to close the shade. No insertion into other apps.
- Gboard clipboard: explicitly select app and scope/command, copy a block or copy and launch the app, and paste using Gboard. Active project is read from app settings. Existing composer and clipboard fallback remain available independently.

All methods share command profiles and local preferences. Android updates preserve integration settings; the existing JSON export covers command/project/app profiles, not the new integration preferences. No new dangerous permission, keyboard service, or automatic message submission.
