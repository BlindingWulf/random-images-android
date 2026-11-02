# Random Images (Android)

Android port of [Random Image Viewer](https://github.com/BlindingWulf/random-images),
the Windows desktop app. Type a tag, press **Start**, and it pulls random posts
from e621 and FurAffinity one at a time — either full screen in the app, or in a
small window that floats above everything else.

It is built to run on phones that are old. The minimum is **Android 5.0
(API 21)**, and that floor is enforced by the build rather than by hope:
`gradlew lintDebug` fails the build if any call is newer than API 21, and the
network layer carries its own certificate anchors because the root stores on
5.x–7.x cannot validate FurAffinity's certificate chain.

## Installing

Download `RandomImages-v1.1.apk` from the
[latest release](../../releases) and open it on the phone. Android will ask you
to allow installs from this source (the APK is signed by the author but not
published through Google Play, so the warning is expected). Nothing else to
install, no account needed to start, and no ad or tracking SDKs.

The release build is signed with a 2048-bit RSA key whose SHA-256 certificate
fingerprint is:

```
9E:18:C5:91:1C:6C:AB:9B:AE:F8:77:DC:1A:48:F8:73:E1:13:A7:E2:41:67:B6:62:3D:CB:2E:AA:EE:AA:BD:A6
```

Both APK Signature Scheme v1 (for Android 5) and v2 are applied. Updating from
an older APK only works if it was signed with the same key, so that key is kept
offline and never committed here.

## What it does

- **Tag search** against e621, FurAffinity, or both mixed
- **Rating filter** — SFW, Mature, or Explicit, each mapped strictly to that
  one rating (SFW shows `s` only, Mature `q` only, Explicit `e` only). Explicit
  asks for an adult confirmation the first time, and needs a logged-in
  FurAffinity cookie for that site's adult results
- **Filter chip** (next to the ratings, on by default): silently excludes a list
  of blocked terms from every search at every rating — minors, scat, gore and
  friends. Nothing appears in the search box and nothing shows in the status
  line; the exclusions are added to the request behind the scenes, and toggling
  the chip takes effect on the next image. Turn it off, or edit the list under
  the overflow menu → **Edit blocked terms** (one term per line; leaving the
  list empty restores the built-in defaults)
- **Interval slider**, 1–300 seconds per image
- **Pop-out window**: puts the current image in a draggable window on top of
  other apps with previous / pause / next controls, sized in Settings. It only
  appears when you ask for it — starting a slideshow never opens it, and Android
  gates it behind the separate "display over other apps" permission
- **No repeats** (on by default): an image already in the cache is skipped, so a
  search keeps drawing until something new turns up. Clearing the cache allows
  images to appear again
- **Cache** of everything it has shown, with Save to Gallery, Share, Open
  original, and Clear cache. In the full-screen viewer, fling left/right at fit
  scale for previous/next; once zoomed (pinch or double-tap) drag to pan
- **Keeps running** as a foreground service with notification controls, and an
  optional keep-screen-on and Wi-Fi-only mode
- **Dark theme** option

Both sites throttle anonymous visitors, so if images stop loading, wait a
while, or add credentials — logged-in requests get much higher limits and that
is the most reliable fix.

## Credentials (both optional)

Open the overflow menu → **Credentials**.

- **e621** uses its official JSON API. Anonymous already works for every rating;
  a key only raises the rate limit. Logged in at e621.net: Account → *View your
  API key*.
- **FurAffinity** has no public API and its login form is captcha-gated, so the
  app reads the site the way a browser does and takes a session cookie from one
  you already have: log in with a browser, then Developer tools → Application →
  Cookies → `furaffinity.net` → copy **FAC** and paste it here. Cookies expire;
  if *Test connections* reports adult results are not visible, copy a fresh one.

Values go to the app's private storage, sealed with a key from the phone's
hardware keystore where one exists (Android 6+ with StrongBox/TEE); on the rare
device without one they stay unencrypted in app-private storage and the screen
says so in plain words. **Test connections** tells you what each site will
actually serve before you rely on it.

## Building from source

You need a JDK, the Android SDK, and nothing else — Gradle comes with the
wrapper.

```bat
git clone https://github.com/BlindingWulf/random-images-android.git
cd random-images-android
copy local.properties.example local.properties   &rem then set sdk.dir to your SDK
gradlew.bat assembleDebug
gradlew.bat installDebug                          &rem with a device or emulator attached
```

Verified toolchain used for this build:

| Component | Version |
| --- | --- |
| Gradle | 9.6.0 (wrapper) |
| Android Gradle Plugin | 9.5.0 (built-in Kotlin) |
| JDK | Temurin 25 |
| compileSdk / buildTools | 37 / 37.0.0 |
| targetSdk / minSdk | 36 / 21 |

`gradlew lintDebug` is the API-21 gate and `assembleRelease` runs R8 with
resource shrinking (release APK ≈ 1.4 MB, debug ≈ 6.4 MB).

Debug builds install beside release ones: applicationId
`com.blindingwulf.randomimages.debug` versus `com.blindingwulf.randomimages`.

### Signing a release

Copy `keystore.properties.example` to `keystore.properties` and point it at your
own keystore. Without it `assembleRelease` still succeeds but emits an unsigned
APK. The keystore and `keystore.properties` are gitignored on purpose — they are
never committed and never shipped.

## Layout

```
app/src/main/java/com/blindingwulf/randomimages/
  MainActivity.kt          viewer screen, controls, menu
  SettingsActivity.kt      overlay, slideshow, display, network settings
  CredentialsActivity.kt   optional logins + Test connections
  BlacklistActivity.kt     blocked-terms editor
  Blacklist.kt             default term list + silent query composition
  SlideshowEngine.kt       fetch/show/wait loop, provider fallback, retry policy
  SlideshowService.kt      foreground service + notification controls
  OverlayController.kt     the floating window
  E621Provider.kt          e621 JSON API
  FurAffinityProvider.kt   FurAffinity search-page scraper
  ImageCache.kt            shown-image cache; its file names double as the
                           "already seen" set behind the no-repeat option
  SecureStore.kt           AndroidKeyStore-sealed credential storage
  Net.kt / TrustBundle.kt  HTTP client + bundled CA anchors for old Android
  ImageLoader.kt / ZoomableImageView.kt / OverlayGestureLayout.kt
```

## Privacy

The app talks only to e621.net and furaffinity.net. No analytics, no ad SDKs, no
crash reporting. Downloaded images live in the app's own external files
directory (`Android/data/<package>/files/image_cache`) and are deleted by
**Clear cache**.

## Third-party notices

`app/src/main/res/raw/mozilla_cacert.pem` is the Mozilla included-roots
certificate store, bundled so Android 5–7 devices can complete TLS to both
sites; it stays under Mozilla's own terms (MPL 2.0), not this repository's.
Everything else here is unlicensed and copyright the author.

## Status

Version 1.1 runs on a moto g (2025, Android 16); the API-21 floor is enforced by
`lintDebug` in every build. The pop-out window's gestures (drag, corner pull,
two-finger pinch resize) are implemented, but multi-touch cannot be driven from
an automated test, so the pinch resize has not been confirmed by hand on a real
device — please report how it behaves.
