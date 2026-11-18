# TaskBite

An Android task manager with a sarcastic, friendly sidekick. Made by Zeeshan Mirza (Dracula).

This is a separate project from `../QuickMsg`. Android 8.0+ (API 26), Kotlin, Jetpack Compose, Room, WorkManager. The first release uses application ID `com.dracula.taskbite`, version code `1`, version `1.0`.

## What works

- Today view with overdue tasks, daily progress, all pending tasks, and completed tasks.
- Add and edit tasks with notes, deadline, estimated duration, and priority.
- Start, complete, reopen, postpone by one calendar day, archive, and restore tasks.
- Dictation through the installed speech recognizer. The transcript opens a draft for review; dates and times are selected in the editor, not inferred from speech.
- Month calendar with task indicators and daily agenda. Export individual tasks to the phone's calendar through its event editor. The user saves the event; this is not two-way calendar synchronization.
- Permanent action history, including completed and archived tasks.
- Original short roasts for overdue tasks, postponing, app abandonment, inactivity, and tiny tasks; encouragement for starting and finishing.
- Optional English text-to-speech reactions, spoken due reminders, quiet hours, and notification actions.
- JSON backup export/import using Android's document picker. Import validates the entire file before an atomic merge, deduplicates IDs, and keeps newer local edits.
- A local release signing key, excluded from source control, to keep future APKs installable over this release.

## Install and use

Install the signed release APK. Complete onboarding, add a task using **New task**, or tap the microphone to dictate it. A speech recognition app and an English TTS voice must be installed for speech features.

Open **Sidekick** to enable in-app speech or background spoken reminders. For spoken reminders, allow notifications and (Android 12+) **Alarms & reminders** when prompted. If alarm access is declined, notification reminders still work. Voice follows the phone's current audio output, so connected headphones/Bluetooth may receive it instead of the speaker. It never forces speaker routing, raises volume, or overrides silent mode or Do Not Disturb.

Quiet hours default to 22:00–08:00 in the phone's timezone; you can change them. Matching start/end hours mute automatic reminders all day. The voice test intentionally ignores quiet hours but respects system sound settings.

## Reminder behavior

- Normal notifications use WorkManager: the next deadline plus a 15-minute periodic recovery check. Timing is approximate; Doze and manufacturer battery management can delay it.
- Opt-in spoken due reminders use exact alarms and a short-lived media playback foreground service. Its notification includes **Stop speaking**. This allows audio focus on Android 15+, where background apps without a foreground service cannot request it.
- Each delivery rechecks whether a task is still pending, enabled notifications, quiet hours, and cooldowns. At most one nudge per 30 minutes overall and one per task per four hours.
- Phone restarts, app updates, clock/timezone changes, and alarm permission grants reschedule spoken reminders.
- App abandonment: leaving Today after at least 10 seconds without starting/completing a task schedules a notification nudge after 30 seconds. Returning cancels it. Opening voice input, calendar, backup pickers, or settings does not count. App-exit nudges are text notifications, not spoken alarms.
- Idle: five minutes without interaction on Today, with pending work and no task started or completed during the current visit, produces an in-app roast. Tiny tasks are tasks estimated below five minutes.
- Force-stopping the app prevents background delivery until it is opened again. On-device behavior depends on notification permissions, installed voice engine, audio routing, and the OS.

Android references: [WorkManager scheduling](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), [exact alarms](https://developer.android.com/develop/background-work/services/alarms), [foreground service launch rules](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [audio focus](https://developer.android.com/media/optimize/audio-focus).

## Data safety and future updates

Tasks and history live in `taskbite.db`. App preferences use `taskbite.xml`. Neither is reset on launch or app version changes. Database schemas are exported under `app/schemas/`. There is deliberately **no destructive migration fallback**.

Install future signed updates over the existing app. Do not uninstall or clear storage first. Preserve all three identities: application ID, database name, and signing key. Increase `versionCode` for every release. If changing the database schema, increase its version, add an explicit Room migration, and test old databases containing tasks and history before releasing. A missing migration fails instead of deleting data; the automated guard test verifies this behavior. See [Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions).

Keep private, independent backups of **both `taskbite-release.jks` and `signing.properties`**. Losing the signing key prevents normal updates of existing installations. These files contain sensitive signing material and must not be committed or shared with the APK.

Use **Sidekick → Export tasks & history** before uninstalling, resetting, or changing phones. Backups contain readable task names, notes, and history. Store them privately. Android system backup/device transfer includes the app's data when enabled by the device, but it is not a guarantee against device loss. Manual exports are portable; voice preferences are intentionally not imported.

There is no automatic release discovery, cloud account, or multi-device synchronization in v1. Future APKs can be installed manually without losing local tasks.

## Build

Use JDK 17 or 21 and Android SDK platform 35:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew testDebugUnitTest lintDebug assembleRelease
```

For a fresh source checkout, use `assembleDebug` for development. Create release signing material **only for a brand-new distribution** with `python3 tools/create_signing_key.py`. To update this existing distribution, restore its original signing files instead. The creation script refuses to overwrite existing files.

Release APK: `app/build/outputs/apk/release/app-release.apk`.

## Validation

Automated checks cover persistence after closing/reopening the database, completion idempotency, stale-editor protection, archive/restore, calendar-day snoozing across daylight saving changes, lossless backup round trips, duplicate/malformed backup rejection, stale-backup merging, the missing-migration data-preservation guard, quiet-hour boundaries, reminder eligibility, word limits, and app UI flows.

Before public distribution, check on a physical phone:

1. Add a typed and a spoken task. Review the transcript and deadline.
2. Start, finish, reopen, postpone, archive, and restore a task; inspect History.
3. Export a backup, change a task, and restore the old backup; confirm the newer edit survives.
4. Grant notification/alarm access, disable quiet hours temporarily, and set a task due soon. Lock the phone and verify the spoken reminder, Stop speaking action, Done action, and Tomorrow action.
5. Check silent mode, DND, headphones, permission revocation, and reboot. Declining permissions must leave task management usable.
6. Save a calendar event. Editing it in the calendar must not imply a synced change in TaskBite.
7. Install a higher-version APK signed with the same key over an existing installation; check tasks, history, and preferences remain.

Device access is disabled in the current build environment. Automated JVM/Android-simulation checks do not replace physical-device speech, permission, background scheduling, or APK upgrade testing.
