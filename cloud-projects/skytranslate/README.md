# Sky Translate (ML Kit OCR) – Android project

App name: **Sky Translate** · Short name: **Sky** · Brand: **SkyNex Apps** · Package: `com.sky.translate`

Android Studio project rebuilt from three files: `translate2.html`, `OcrService.kt`, `OcrBridge.kt`.
The HTML runs unchanged inside a WebView; Google ML Kit does the OCR natively through `window.MLKitOCR`.

## Build status (read this first)

This project was generated in an environment **without the Android SDK and without access to Google/Maven
hosts, so no Gradle sync, no release build and no APK were produced there.** Verified there:

| Check | Result |
|---|---|
| `translate2.html`: only the API-key/network layer changed (diff vs. your original is limited to that); page loads without JS errors; provider/key failover tested with mocked network in Chromium | verified |
| `release-apk.yml` parses as valid YAML | verified (syntax only, not run on GitHub) |
| All Android XML files are well-formed | verified |
| Kotlin files free of syntax errors (Kotlin 2.0.21 parser) | verified |
| Voice/clipboard JS shims tested in Chromium against a simulated Android bridge (recognition partial/final/error/abort, TTS end/cancel, paste/copy) | verified (simulation only) |
| Real SpeechRecognizer / TextToSpeech / mic permission / Pollinations voices on a phone | **NOT tested** |
| Kotlin type-check / compile, R8, Gradle sync, APK, Camera/Gallery/OCR on a device | **NOT tested** |

Expect to fix small issues on first build. The GitHub workflow below produces the real APK.

## What is in the project

- `app/src/main/assets/translate2.html` – your page. Changes: loads `ai-keys.js`, and the AI/Tavily request code uses key failover. No UI changes. `ai-keys.js` is a placeholder; the workflow overwrites it from the `AI_API` secret.
- `OcrService.kt` – your ML Kit engine (Latin, Devanagari, Chinese, Japanese, Korean). Only change: a guard so a call after `close()` returns an error instead of crashing.
- `OcrBridge.kt` – your bridge. Added: validation of `id`, language length, image size and null values, and a closed-state guard.
- `MainActivity.kt` – new. Secure WebView, asset loader, camera permission, gallery picker, back handling, lifecycle cleanup.
- Gradle (Groovy DSL): AGP 8.9.3, Kotlin 2.0.21, Gradle wrapper 8.14.3, compileSdk/targetSdk 35, minSdk 24, JDK 17.

How the flows work:
- **Camera**: the page uses `getUserMedia`; `MainActivity` grants only `VIDEO_CAPTURE` for the app's own origin after the Android CAMERA permission is granted. Frames go to `MLKitOCR.recognize` and results return through `window.__ocrDone`.
- **Gallery**: the page's file input opens the system image picker (`GetContent("image/*")`).
- **Loading/error states** shown to the user are the page's own messages (nothing added to the UI).
- **Offline**: the ML Kit artifacts bundle their models, so supported scripts work offline. A "model" error still falls back to the page's Tesseract path as in your original code.
- **Tamil and other unsupported languages**: **not supported by these five ML Kit dependencies.** `scriptFor()` returns null, and the page falls back to its existing Tesseract.js path (downloaded from a CDN, so it needs internet).

## Things that could NOT be reconstructed or are limited

- Your original Android project (other native classes, icons, signing config, package/app id, version, Firebase config, etc.) was not provided. Package is `com.sky.translate`; change it in `app/build.gradle` and the Kotlin `package` lines if your real app id differs. **An APK signed with a different key or id cannot update an installed original app.**
- **Voice (added):** Android WebView has no `SpeechRecognition` and no working `speechSynthesis`, so `assets/native-speech.js` maps them onto the new native `SpeechBridge.kt` (Android `SpeechRecognizer` for the mic / live conversation, `TextToSpeech` for the device-voice fallback). The HTML's own voice code is unchanged. Needs the microphone permission (asked on first use) and, for recognition, a speech service on the phone (Google app / Speech Services). The 31 AI voices still come from Pollinations (needs its key + internet) and fall back to the device voice if that fails. Device voice only speaks languages installed in the phone's TTS engine; otherwise it stays silent for that language.
- **Clipboard / Paste button:** WebView blocks `clipboard.readText()`, so `ClipBridge.kt` provides it natively.
- Handwriting input calls Google's input-tools endpoint from the page; its browser access could not be tested here.
- The app launcher icon is a new placeholder (two speech bubbles); replace it with yours.
- **API keys:** the hard-coded keys were removed from `translate2.html`; keys now come from the `AI_API` secret at build time (see below). `translate2.html` changed only in its key/network layer (failover across providers and Tavily keys); no UI, layout, color, icon or animation was touched.
- The APK includes five bundled models and four CPU ABIs, so it will be large. You can add `ndk { abiFilters 'arm64-v8a' }` in `app/build.gradle` to shrink it.
- R8 minification is disabled (the bridge needs `@JavascriptInterface` names preserved).

## Build in Android Studio

1. Install Android Studio (Ladybug or newer) with JDK 17+ and SDK Platform 35.
2. File > Open > select this folder, let Gradle sync finish.
3. Debug on a device: Run ▶. Release: Build > Generate Signed App Bundle / APK > APK, or from a terminal:

```bash
./gradlew assembleRelease            # unsigned unless the RELEASE_* properties below are set
```

Local signing (put in `~/.gradle/gradle.properties`, never in the repo):
`RELEASE_KEYSTORE_FILE`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
Output: `app/build/outputs/apk/release/`.

## Upload to GitHub (ZIP is extracted by the workflow)

You do **not** need to extract the ZIP. In a new GitHub repository, upload only:

```
SkyTranslate.zip
.github/workflows/release-apk.yml
```

(Add file > Upload files; for the YAML create the folders `.github/workflows/` first, or drag the `.github` folder.)
The workflow unzips the project itself, injects your API keys, builds and publishes the APK.

## GitHub Secrets (5 in total)

Repository > Settings > Secrets and variables > Actions > New repository secret.

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | base64 of your `release.jks` (one long line) |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |
| `AI_API` | **all API keys in one secret, one `provider:KEY` per line** (below) |

`AI_API` format: **one `provider:KEY` per line** (see `AI_API.template.txt`; paste the whole thing into the one secret):

```
groq:PASTE_KEY        (x4)
gemini:PASTE_KEY      (x4)
openrouter:PASTE_KEY  (x3)
pollinations:PASTE_KEY
tavily:PASTE_KEY      (x2)
```

- Providers: `groq`, `gemini`, `openrouter`, `pollinations`, `tavily`. Any number of lines per provider. Blank lines and `#` comments are fine.
- **The line order is the fallback order** for the AI providers: line 1 is tried first, then line 2, and so on. Reorder the lines to change priority.
- Lines still containing `PASTE_KEY` are skipped with a warning (no failure).
- Optional lines: `groq_model:...`, `gemini_model:...`, `openrouter_model:...`, `pollinations_model:...` to override the model names
  (defaults: `openai/gpt-oss-20b`, `gemini-3.6-flash`, `openrouter/auto`, Pollinations `openai`), and `timeout:8000` (milliseconds per attempt, default 15000).
- The older JSON format is still accepted.
- Every key is masked in the workflow logs, and an unreadable secret fails with a clear message.
- Automatic fallback: the page's Advanced translation tries your AI keys in the order of the AI_API lines.
  It moves to the next key/provider on **any** error (401/402/403/404/429/400/5xx, network failure), when a provider does not answer within 15 seconds
  (the `timeout:` line in the secret can change this), or when a provider returns an empty answer. A failed key is skipped for 60 seconds; the one that works is kept.
  If every key fails, the app shows its normal "Translation failed" message. Tavily web search rotates between its keys on any error. Pollinations' key is also used for its voice (audio) endpoint.
- The old hard-coded keys were **removed** from `translate2.html`. Put the ones you still want to use into `AI_API`.
- Honest limit: the keys are written into the APK's assets at build time. That keeps them out of your repository and ZIP,
  but anyone with the APK can still extract them. Only a server-side proxy fully protects them.
- Provider CORS/browser access and model names could not be tested against the live services here (no network); the failover logic was tested with mocked responses in Chromium.

Do not commit `release.jks` or `keystore.b64` (both are in `.gitignore`).

## Run the workflow and download the APK

- **Manual**: Actions > "Release APK" > Run workflow (optionally set a version name).
- **Version tag**: `git tag v1.0.0 && git push origin v1.0.0`.
- Either way the signed APK is published as a **direct `.apk` file** (no zip) on the repository's **Releases** page. Open it on your phone and tap the APK to download.
  Manual runs appear as a pre-release named "Build N"; tag runs as the tag name.
- A missing signing secret stops the build with a clear error (an unsigned APK cannot be installed).
- On failure the job summary says so and the `build-reports` artifact holds the Gradle reports.
- Install: allow "install unknown apps" for your browser or file manager.
