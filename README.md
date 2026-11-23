# Smooth Zoom

A minimal camera app for phones like the Galaxy A56 that expose the ultrawide and main
cameras as separate Camera2 devices (no native lens switching). It hides the 0.5x ⇄ 1x
lens switch by snapshotting the last frame on the GPU, keeping it zoom-matched (the zoom never
pauses) while the other lens starts, and crossfading once exposure has settled.

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
- **Video:** switch to VIDEO and tap the red button. Recordings (1080p, 30 fps, H.264 + AAC)
  go to `DCIM/SmoothZoom`. The preview is drawn with OpenGL and the same frames go to the
  encoder, so a lens switch while recording shows the same smooth transition as the preview.

## Limits
- During a lens switch the video shows the frozen, zooming frame for ~0.3 s instead of live
  motion; it never cuts to black or jumps.
- Photo quality is plain Camera2 output, without Samsung's processing.
