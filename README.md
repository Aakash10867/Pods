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

## Features
- **Camera and microphone:** a site asks, you tap Allow or Block (remembered until you close the tile).
- **Downloads:** saved to your Downloads folder, using that tile's own login. Blob downloads
  (like WhatsApp Web media) go through a bridge that only exists on the tile's own site.
- **Notifications:** the page's Notification API becomes real Android notifications. Tiles set to
  "Stay connected in background" (on by default for WhatsApp Web) keep running behind a small
  ongoing notification, so messages still arrive. Back button sends such a tile to the
  background; the X closes it. Locked tiles show no message text in notifications.

## Known limits
- Notifications work only while the tile is open or staying connected. Android may still stop
  it on phones with aggressive battery savers; exclude Pods from battery optimization.
- Real web push (notifications with the site fully closed) isn't possible inside a WebView.
- Google sign-in inside a WebView may be blocked; the app presents itself as Chrome, which
  usually helps, but it isn't guaranteed. Email/password logins are unaffected.
- Only tested by compiling so far; not yet run on a device.
