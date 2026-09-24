# Aarogya Mandir Reels

Android app: record a voiceover, and the background image switches
**automatically** based on the keyword you're speaking (using Android's
built-in speech recognizer) — then it exports a ready 9:16 MP4 for
Instagram Reels.

## Important — please read first

This project's code was written by Claude, but **the APK could not be
compiled inside the sandbox it was written in** — that environment's
network policy blocks Google's Android SDK servers and Maven/Gradle
download servers entirely (every request to `dl.google.com`,
`maven.google.com`, `repo1.maven.org`, `services.gradle.org` is refused).
So a real Android SDK + Gradle toolchain could not be installed there to
produce and test a signed APK, and none of this code has run on a real
device or emulator yet.

To still get you a ready, installable APK with **zero coding on your
side**, this project includes `.github/workflows/build-apk.yml` — a free
GitHub Actions workflow that builds the APK automatically in GitHub's
cloud (which has full internet access) every time this code is pushed
there.

## How to get the APK (no coding needed, ~5 minutes)

1. Go to [github.com](https://github.com) and create a free account if
   you don't have one.
2. Click **New repository**, give it any name (e.g. `aarogya-mandir-reels`),
   keep it **Private** or Public, don't add a README, and click **Create**.
3. On the new repo's page, click **uploading an existing file**, then drag
   this entire unzipped `AarogyaMandirReels` folder into the browser
   window (GitHub accepts whole folders via drag-and-drop) and commit.
4. Click the **Actions** tab at the top of the repo — a workflow run
   called "Build APK" should already be running (it starts automatically
   on the first push). Wait ~3-5 minutes for it to finish (green tick).
5. Click on that finished run, scroll down to **Artifacts**, and download
   **AarogyaMandirReels-debug-apk** — this is a `.zip` containing
   `app-debug.apk`.
6. Copy `app-debug.apk` to your phone (via WhatsApp to yourself, email,
   Google Drive, or a USB cable), tap it to install. Android will ask you
   to allow "install from this source" once — allow it, then install.

If step 4 shows a red ✗ instead of a green tick, click into the run and
open the failed step's log, then send me the error text — I'll fix the
code.

## Alternative: build it yourself in Android Studio

If you (or someone you know) has [Android Studio](https://developer.android.com/studio)
installed: **File → Open** this folder, let it sync (Studio will offer
to create the Gradle wrapper if it asks), then **Run ▶** on a connected
phone/emulator, or **Build → Build Bundle(s)/APK(s) → Build APK(s)** to
get `app/build/outputs/apk/debug/app-debug.apk` directly.

## How the app works

1. **Step 1 – Images Tag Kara**: pick photos/graphics from your gallery
   (workout poses, your logo, etc.) and type one or more keywords for
   each — e.g. an image of jumping jacks tagged `jumping jacks, jump`.
2. **Step 2 – Record Kara**: press Record and just talk through your
   workout/script normally. The app listens continuously in the
   background; the moment it hears a word matching one of your tags, the
   full-screen image switches automatically. You can also tap the screen
   any time to manually advance to the next image (useful if a word
   isn't recognized correctly).
3. On **Stop**, the app automatically renders everything — your voice +
   the exact sequence of images at the exact times they appeared — into
   one MP4 (1080×1920, Reels-ready), saves it to
   **Movies/AarogyaMandirReels** in your gallery, and gives you a
   **Share to Instagram** button.

## Known limitations (please read before relying on this for something important)

- **Speech recognition needs the internet** (it uses the phone's default
  Google speech service in most cases) and works best in a quiet room
  with clear, simple English/Hindi/Marathi words. Mixed-language
  sentences may not always match — tag each image with a few different
  keyword variants to improve matching (e.g. `jumping jacks, jumping,
  jacks`).
- **Live speech recognition + audio recording share the microphone.**
  Most modern phones handle this fine, but it isn't guaranteed on every
  device. If you ever notice the recorded audio is silent or distorted
  on your specific phone, use manual tap-to-advance instead of relying
  on auto-detection — the exported video works identically either way.
- **Video export (the most complex part of this app) has not been
  tested on a real device**, since it was built without SDK/emulator
  access. It uses a well-established Android pattern (MediaCodec +
  MediaMuxer encoding images to H.264, then muxing in the recorded AAC
  audio), but if it crashes or produces a broken file on your phone,
  copy the exact error text from the Export screen (or `adb logcat`) and
  send it back — that's the fastest way to get it fixed.
- Minimum Android version: 8.0 (API 26).

## Project structure

```
app/src/main/java/com/aarogyamandir/reels/
  MainActivity.kt          – home screen
  ImageTagActivity.kt       – pick & tag images
  RecordActivity.kt         – record + live auto-switching
  ExportActivity.kt         – render + save + share
  model/TaggedImage.kt       – image + keyword list
  model/TimelineEvent.kt     – (timestamp, image) log entry
  store/ImageStore.kt        – saves tagged images as JSON + private files
  speech/SpeechEngine.kt     – continuous-listening wrapper over SpeechRecognizer
  video/VideoRenderer.kt     – images + audio -> MP4 (MediaCodec/MediaMuxer)
```
