# CourseShelf

Android app for watching your own video courses from an [OfflineU](https://github.com/nickkk333/OfflineU)
server running on TrueNAS.

- Library of every course folder on the server, with "Continue learning" and progress bars
- Course view grouped by section; long-press a lesson to mark it done / not done
- Media3 player: plays MP4, MKV, WebM, HEVC and audio straight from the server, subtitles,
  playback speed, auto-advance to the next lesson
- Background audio with a media notification, picture-in-picture for video
- Progress syncs to OfflineU, so the phone and the web UI agree on where you are
- Files the phone can't decode are converted by the server on the fly (HLS) and retried
- PDFs, text/Markdown, HTML quizzes, Word documents and images open in the app
- Light/dark theme with a matching dark launcher icon

## Using it

1. Install OfflineU on TrueNAS (see `offlineu-truenas.yaml`), port 30500.
2. Open CourseShelf and enter `http://<truenas-ip>:30500`.

OfflineU has no login, so use it on your home network or over Tailscale/WireGuard.

## Installing

Every push to `main` builds the app on GitHub and publishes it under **Releases**. On your phone,
open the latest release, tap the `.apk`, and allow your browser to install apps when asked.
Newer releases install over older ones and keep your settings.

## Building

```
./gradlew testDebugUnitTest assembleRelease
```

Builds are signed with `app/ci-debug.keystore`, a fixed key kept in the repo so every build
(local or GitHub) can update the installed app. To sign with a private key instead, add repo
secrets `KEYSTORE_BASE64` (your `.jks`, base64-encoded, alias `courseshelf`) and
`KEYSTORE_PASSWORD`. Switching keys means uninstalling the old build once.

## How it talks to OfflineU

OfflineU keeps one "active course" for the whole server. The app calls `/load_course` before
opening a course or starting playback, uses `/api/state` for the lesson tree, posts watch time to
`/api/progress` every 10 seconds (and on pause, lesson change and stop), and reads the remembered
course list after `/api/reset_course`. Using the phone and the web UI on different courses at the
same moment can make one of them reload its course.
