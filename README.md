# Pods

An Android app that holds as many websites as you like, each in its own sealed browser
profile. Add "Gmail - personal", "Gmail - work", three WhatsApp Web accounts, two ChatGPT
logins, Zerodha, anything. Each keeps its own cookies and login, and they survive closing
the app. Free, no server, nothing leaves your phone.

## How it works
- Home screen: a grid of tiles. `+` adds one (pick a template or type any address).
- Tap a tile to open it full screen. Long-press a tile to rename, change address,
  require screen lock, switch desktop/mobile site, or delete (erases its login).
- Every tile uses its own WebView profile (androidx.webkit multi-profile), so no tile can
  see another's cookies. If the phone's WebView is too old to do that, the app refuses to
  open tiles rather than mixing accounts.
- Locked tiles ask for your phone's PIN / pattern / fingerprint on open and again after
  60 seconds in the background, and block screenshots and the recents thumbnail.

## Build the APK
**Android Studio:** open this folder, let it sync, Build > Build APK(s). Or from a terminal:
`./gradlew assembleRelease`. The APK is in `app/build/outputs/apk/release/`.

**GitHub Actions (no setup on your PC):** push this folder to a private repo; the workflow in
`.github/workflows/build.yml` builds the APK and attaches it as the `pods-apk` artifact.

Install by opening the APK on your phone (allow "install unknown apps" for your browser or
file manager once). Builds are signed with the standard debug key. Debug keys differ between
machines, so an update built elsewhere may need an uninstall first (which erases saved logins).
For updates that keep logins, sign every build with one permanent key: set the `PODS_KEYSTORE`,
`PODS_STORE_PASSWORD` (and optionally `PODS_KEY_ALIAS`, `PODS_KEY_PASSWORD`) environment
variables, or GitHub secrets for the workflow. Never commit the keystore to a public repo.

## Known limits (v0.1)
- No push notifications (WebView can't receive web push), so WhatsApp only updates while open.
- No camera, microphone or file downloads yet (file uploads work).
- Google sign-in inside a WebView may be blocked; the app presents itself as Chrome, which
  usually helps, but it isn't guaranteed. Email/password logins are unaffected.
- Not yet compiled or run on a device. Expect a round of fixes after the first build.
