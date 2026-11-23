# Smooth Zoom

A minimal camera app for phones like the Galaxy A56 that expose the ultrawide and main
cameras as separate Camera2 devices (no native lens switching). It hides the 0.5x ⇄ 1x
lens switch by freezing the last frame, keeping it zoom-matched while the other lens starts,
and crossfading once exposure has settled.

## Get the APK (no Android Studio needed)
1. Create a new GitHub repo and upload everything in this folder (keep `.github/`).
2. Open the **Actions** tab. The "Build APK" workflow runs on every push.
3. When it finishes, download the `SmoothZoom-apk` artifact, unzip it and install
   `app-debug.apk` on the phone (allow "Install unknown apps" for your browser/file manager).

Or open the folder in Android Studio and press Run.

## Using it
- Pinch anywhere on the preview, or tap 0.5 / 1 / 2.
- The top line shows which camera IDs were found. If it says the ultrawide isn't exposed,
  Samsung isn't letting third-party apps use it on your firmware.
- **Calibrate:** tap the top line and use `UW FOV − / +` until the image doesn't jump in
  size during the switch (Samsung's ultrawide distortion correction can crop the frame a bit).
  The value is saved.
- Shutter saves JPEGs to `DCIM/SmoothZoom`.

## Limits
- Video isn't supported: a recording would still show the ~0.3–0.5 s gap.
- Photo quality is plain Camera2 output, without Samsung's processing.
