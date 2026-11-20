# TaskBite

An Android task manager with a sarcastic, friendly sidekick. Made by Zeeshan Mirza (Dracula).

This is a separate project from `../QuickMsg`. Android 8.0+ (API 26), Kotlin, Jetpack Compose, Room, WorkManager, and Glance. The current release uses application ID `com.dracula.taskbite`, version code `20`, version `1.19`.

## What works

- Today view with overdue tasks, daily progress, all pending tasks, and completed tasks.
- Add and edit tasks with notes, checklists, deadline, estimated duration, and priority. Search tasks, checklist text, and history.
- Start, complete, reopen, postpone until Later today or Tomorrow, archive, restore, and explicitly delete tasks.
- Dictation through the installed speech recognizer. The transcript opens a draft for review; dates and times are selected in the editor, not inferred from speech.
- Month calendar with task indicators and daily agenda. Export individual tasks to the phone's calendar through its event editor. The user saves the event; this is not two-way calendar synchronization.
- Persistent action history, including completed and archived tasks; explicit deletion removes the selected task and its history.
- Original short roasts for overdue tasks, postponing, app abandonment, inactivity, and tiny tasks; encouragement for starting and finishing.
- Optional human voice reactions, spoken due reminders, quiet hours, and notification actions. Three licensed actors plus recording/import for your own roast lines. No text-to-speech engine.
- Resizable, scrollable home-screen widgets with today's tasks, direct completion, pending/overdue counts, quick-add, and voice entry.
- Complete backup export/import for tasks, history, settings, custom recordings, and saved personal packs. Task-only JSON exports remain supported. Task merges are atomic, deduplicate IDs, and keep newer local edits.
- A local release signing key, excluded from source control, to keep future APKs installable over this release.

## Install and use

Install the signed release APK. Complete onboarding, add a task using **New task**, or tap the microphone to dictate it. A speech recognition app is needed for dictation. Human voice playback works offline using the included recordings.

Open **Sidekick** to enable in-app speech or background spoken reminders. For spoken reminders, allow notifications and (Android 12+) **Alarms & reminders** when prompted. If alarm access is declined, notification reminders still work. Voice follows the phone's current audio output, so connected headphones/Bluetooth may receive it instead of the speaker. It never forces speaker routing or raises system volume. Automatic reactions respect silent/vibrate and Do Not Disturb; explicit previews use the media stream, so vibrate mode alone does not block them. Android still applies DND restrictions on media.

Quiet hours default to 22:00–08:00 in the phone's timezone; you can change them. Matching start/end hours mute automatic reminders all day. The voice test intentionally ignores quiet hours and the ringer mode. Press the phone’s volume buttons while TaskBite is open to change media volume. DND media restrictions still apply.

**Sidekick → Spoken alert volume** sets speech loudness from 0–100% in 5% steps. The saved level applies to in-app reactions, background spoken reminders, and **Test voice**. Zero mutes speech; 100% uses the phone's current media volume. Adjusting the slider stops any current speech; tap **Test voice** to hear the new level. Bite suppresses the separate Android notification chime, so only the recorded voice plays. Bundled performances use AudioTrack gain; imported compressed recordings use MediaPlayer gain. Neither changes system volume.

## Everyday improvements in 1.19

**Search** is available in Today and History. Today searches the selected filter across titles, notes, and checklist steps. History also searches action names. Queries survive rotation. **Checklist (optional)** in the editor supports up to 30 steps; checking a step on a task card saves it without completing the parent task. A new recurring occurrence starts with unchecked steps. A stale open editor cannot overwrite newer checklist progress.

**Later today** opens a time picker and rejects a past time instead of silently moving to tomorrow. It supports the existing ten-second Undo and postponement history. Database schema 4 adds the checklist through a data-preserving 3→4 migration; the full 1→2→3→4 path remains supported. JSON format 4 includes steps and still reads versions 1–3.

**Sidekick → Reminder status** shows the next planned reminder, an away-check-in explanation when Bite is open, and relevant notification, volume, alarm, or missing-recording fixes. Times are estimates because Android may delay delivery. **Pause teasing** offers one hour or until tomorrow morning and resumes automatically. **Keep deadline reminders** independently controls due-task notifications during the break; those remaining notifications use plain text without teasing audio. In-app, away, planning, opening and clearing reactions honor the break. Explicit previews and deliberately requested tests remain available.

**Widgets & backups → Back up everything** creates a `.bitebackup` ZIP with tasks, history, deletion markers, portable settings, custom recordings and saved personal packs. It verifies per-file checksums, paths, size limits, metadata and audio before restoration. Existing recordings are kept and duplicate audio is skipped; a merge that exceeds five recordings per situation/style or the 20 MB custom-voice limit is rejected before tasks are merged. Task merges keep newer local edits and saved deletions. Settings replacement has its own confirmation checkbox. Android permissions, pending notifications, delivery timestamps, temporary pauses and cached updates are not imported. Backup files contain private recordings and should be stored privately. Complete archives are limited to 36 MB compressed / 32 MB expanded; task JSON remains limited to 10 MB. The separate **Export tasks & history** produces JSON without recordings or preferences.

**Voice settings** guides you through four recordings for each of 12 situations, with separate Classic and Sweet progress. The files under `voice-pack/` contain **96 scripts, not 96 new recordings**. Record/import real performances to complete the pack; Bite never synthesizes a missing voice or replaces a Sweet line with Classic audio. The existing human voices continue to work.

Personal builds save their installed optional packs into private app storage on first launch. Install and open the personal 1.19 APK once to preserve those packs before subsequent public-only updates. Public builds never upload or distribute those local packs. Complete backups and Android backup/device transfer include saved packs.

At large font sizes, bottom navigation uses its icons with full accessibility labels to avoid breaking words into several lines. Controls remain reachable by scrolling. See [phone-testing.md](docs/phone-testing.md) for the isolated phone-test runner and the listening/accessibility checks still requiring a real device.

## Automatic updates in 1.18

Open **Sidekick → App updates**. Automatic updates are enabled by default: a daily WorkManager check looks for the latest stable release of `dracula74644/TaskBite`, and a newer compatible APK downloads on an unmetered connection. The first background check is scheduled for about 15 minutes after setup; Android can delay it. **Check now** checks sooner. **Download now** can use the current connection, including mobile data. Downloads have progress and cancellation controls, and **Automatic updates** can be switched off without canceling an explicitly requested manual download.

A silent **App updates** notification opens the update screen. It never triggers task-notification teasing. After verification, tap **Install update** and confirm in Android. If needed, **Allow installation** opens Android's setting for trusting Bite as an installation source; return and tap Install update. The updater does not request silent installation privileges. An update notification waits for an open task draft to be saved or closed before navigating away. [Android's installation-source permission](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()) applies.

The updater accepts only HTTPS GitHub release assets from this repository, follows a restricted set of GitHub/CDN redirect hosts, and bounds metadata/download sizes. It checks the manifest, asset size, SHA-256, package ID, exact version, Android compatibility, and the installed app's signing certificate. Old versions, different signers, malformed files, and damaged downloads are rejected. The downloaded APK is verified again immediately before sharing it with Android through a read-only content URI. Only the private `cache/updates/` folder is exposed by the FileProvider; task databases and voice recordings cannot be shared through it. [FileProvider setup](https://developer.android.com/training/secure-file-sharing/setup-sharing) describes this restricted sharing mechanism.

The updater uses the network only for release checks and APK downloads. It sends no tasks, history, recordings, account credentials, or GitHub token. The automatic-update preference survives app updates and Android backups. Cached APKs and release status are excluded from backup, and obsolete downloaded APKs are removed after an upgrade. Task storage, database schema, existing recordings, and signing identity remain unchanged.

Stable public updates are distributed through [GitHub Releases](https://github.com/dracula74644/TaskBite/releases). Each release needs its signed public APK and matching `taskbite-update.json`. The local release command below tests, signs, prepares, uploads to a draft, verifies the uploaded bytes, and only then publishes. Personal High on Life assets are excluded from public release packages.

## Simpler testing in 1.17

**Sidekick → Test & troubleshoot** puts the testing tools in one place, leaving everyday voice and reminder controls easier to reach. Choose **Simulate a day** to practice with sample tasks, or **Send test notification** to check delivery on your phone. The phone test explains how to leave Bite and check opening or clearing the notification. It also identifies muted voice, missing Sweet recordings, and notification/alarm permissions that need attention. **Delivery details** remains available there for troubleshooting.

Phone tests now show a countdown and a saved result: sent, blocked, canceled, or expired. A sent result confirms that Bite posted the notification; the user still checks whether it appeared and sounded on the phone. Repeated taps preserve the original countdown. **Cancel test** stops a pending test while keeping the next regular reminder scheduled. Already-sent notifications are cleared in Android. Test results survive app recreation and stay separate from ordinary reminder diagnostics; they are local runtime data excluded from Android backups.

The simulator puts the current situation and matching reaction before optional controls, uses shorter explanations, and shows progress through the sample day. Run/Pause, Next event, and fast-forward remain within reach. **Simulation options** holds voice/style, the sample nudge gap, and leaving/returning to the sample app. The Back button returns to the testing screen. Sample mode changes no real tasks, history, or saved settings and sends no real notifications.

## Material polish in 1.16

Bite keeps its dark lime palette and four-tab layout, with consistent Material 3 shapes and green surface colors. Today, History, task priority, and teasing style use connected single-choice controls. Task cards have softer corners, tonal completion and Start buttons, and wrapping actions for compact screens. Calendar and settings use matching cards; the task editor uses a rounded bottom sheet.

**New task** is now an extended floating action button in a reserved area above navigation, so it never covers task rows. Selected navigation items have a clearer green indicator. Primary buttons retain minimum 48 dp heights, and connected filters and voice buttons grow together when labels wrap. This is a visual update; it preserves tasks, history, settings, recordings, and reminder behavior.

## Notification-clearing reactions in 1.15

Swiping a Bite reminder or using Clear all now starts the playback service directly through the notification's foreground-service PendingIntent. Notifications posted before the update retain receiver support. A scheduled reminder arriving during clearing playback can no longer cancel that reaction; remaining reminder work is rescheduled after it finishes. Clear all still produces one response, and clearing never completes, deletes, or postpones tasks or resets the nudge gap.

**Voice settings → Notification cleared** is a separate situation with its own saved recording cycle and recording/import slot. The standard actors rotate their existing “[Sigh] No way.” and “Not happening. Let’s go!” performances; the personal game voice rotates its two existing dismissal alternatives. No new audio is generated. Existing custom Leaving without progress recordings remain the fallback until a dedicated clearing recording is added, within the same Classic/Sweet style.

A brief Android text popup shows the exact selected recording's caption. If Sweet has no matching recording, the popup explicitly says **Text only** and shows a written clearing quip. It does not repost the dismissed reminder or create a notification loop. Sound still follows Notification reactions, quiet hours, silent/DND mode, and the volume settings. The popup can provide feedback while sound is muted; Android controls its visibility.

**Sidekick → Test & troubleshoot → Delivery details** preserves **Last clearing reaction** separately from ordinary reminder audio, including muted/blocked/missing-recording results. To test the real path without waiting for the nudge gap, use **Send test notification**, press Home, and swipe away the test card after it arrives. The simulator also includes **Clear a notification**; it changes no real tasks or notifications. The full human recording scripts now have 24 lines across 12 situations, awaiting actual performances. Tasks/history storage and signing identity are unchanged.

## Recording variety in 1.14

Every situation cycles through its available recordings before repeating. Progress is saved per situation, actor, and style, so Android restarting Bite between nudges does not reset the cycle. Clearing a notification has a separate cycle from an empty-list nudge; clearing every reminder cannot consume the next nudge's alternatives. Bite also prefers recordings not selected by the preceding two reactions when choices remain. Replay keeps the exact original clip and does not advance rotation.

Alex, Sean, and Meghan each have five existing recorded reactions assigned to empty-list, idle, and leaving/clearing situations. These are calls to action and playful disapproval, such as “Hmm… Let’s go!”, “[Sigh] No way.” and “Alright. Get ready.” Greetings including “Welcome. Good luck.” are excluded from those recurring situations. The personal High on Life option has two suitable existing recordings per situation. These mappings reuse actual performances; this update adds no new human performances or synthetic speech.

**Voice settings** shows how many recordings are available for the selected situation. Both **Preview this reaction** and **Hear voice** advance the matching cycle. A single custom recording still overrides its situation and can only repeat; record or import more to vary it. Sweet keeps its separate recordings and remains text-only where none exist. The selected voice, nudge gap, tasks, and history are preserved; there is no database migration. Rotation state is local runtime data, excluded from Android backups but preserved through ordinary app updates.

## Silent notification cards and faster dismissal reactions in 1.13

Due-task, planning, away, and test notifications now use [per-notification silence](https://developer.android.com/reference/kotlin/androidx/core/app/NotificationCompat.Builder#setSilent(kotlin.Boolean)), including on upgrades where Android retains an existing channel's sound. New installs also get a silent reminder channel. Notification cards and actions remain available; if voice is off or no matching recording exists, the card is silent. Other apps' notification sounds and system volume are unchanged.

Clearing a reminder now requests the voice service directly inside the dismissal callback, before database or recording preparation. Dismissal audio is ready immediately. If Android rejects that start, the alarm fallback requests playback immediately instead of adding the previous five-second wait; reactions that cannot start within five seconds are discarded rather than spoken long after the gesture. Android still controls process/service startup. Clear-all keeps the first reaction playing once, ignores duplicate requests, and stops the service afterward. Quiet hours, DND, silent mode, and voice volume retain their existing behavior.

Regression checks cover existing channels with a phone chime, fresh silent channels, preserved notification blocks/actions, immediate dismissal fallback, expired interaction audio, direct receiver-to-service startup, actual recorded playback, and service cleanup after clear-all.

## Background delivery and notification reactions in 1.12

The background worker now posts the notification even when spoken alarms are enabled. Previously it could defer everything to the audio service, so a failed service start also hid the notification. Audio receives a short-lived handoff containing the exact caption and recording already selected for that notice. Alarm/service failures leave the text delivery path available, and stale audio is canceled after task changes, notification interactions, or expiry.

**Sidekick → Test & troubleshoot → Send test notification** queues a real notification for about ten seconds later. Press Home or lock the phone after tapping it. This explicit test bypasses quiet hours and the ringer setting, uses media volume, and changes no tasks or history. **Spoken task reactions** must be on for the test voice. Android notification permission/channel settings, DND media rules, app volume, and recording availability still apply. Without alarm access, Android may delay the WorkManager test. **Delivery details** shows the last notification, audio result, background-worker run, and alarm reception, plus links to system settings.

**Notification reactions** adds three behaviors using existing real recordings:

- Tap a reminder: a short starting/greeting reaction plays once, without marking the task started or complete. Planning reminders still open a blank task draft.
- Swipe a reminder away or clear all: a dedicated Notification cleared reaction in 1.15. Clear-all is limited to one recording. Task completion and other programmatic notification removal never trigger this sound.
- Leave a reminder untouched, or open it without taking a task action: **While I'm away** can label the next check-in “No response yet” or “Opened, still waiting,” using the chosen gap. This is an inactivity check, not a read receipt. Bite cannot detect merely viewing a notification. The notification headline describes the situation; the caption remains the actual recorded words.

Automatic reactions retain quiet hours, silent/DND behavior, and the shared volume slider. Sweet situations without recordings remain text-only. Interaction state and audio queues are local runtime data, excluded from Android backups; tasks, history, and saved settings retain their existing persistence rules. No microphone, notification-listener, or read-access permission is added.

Android supplies explicit user dismissal through [notification delete intents](https://developer.android.com/reference/android/app/Notification.Builder#setDeleteIntent(android.app.PendingIntent)); taps open the activity directly. Android can restrict background service starts. Samsung can additionally suspend notifications for sleeping apps: see [Samsung notification troubleshooting](https://www.samsung.com/us/support/troubleshoot/TSG10001486/). The app reports detectable restrictions, but cannot inspect or override every Samsung battery setting. Force-stopping Bite suspends delivery until it is reopened.

## Configurable away nudges in 1.11

**Sidekick → While I'm away → Nudge gap** defaults to **30 minutes**. Choose 15, 30, 60, or 120 minutes, or enter any whole number from **15 to 1,440 minutes**. The setting is saved across restarts and updates; changing it reschedules the next check-in. There is no two-per-day cap, three-hour floor, or fixed 09:00–20:00 window anymore. Your configured **Quiet hours** control when automatic nudges pause.

Bite reassesses the current tasks before every check-in:

| Situation | Notification context |
| --- | --- |
| No pending tasks and no task activity today | An empty-list prompt with **Add a task**; repeats even with **Plan my day** off |
| One to three pending tasks due today or overdue | The number remaining and a task to start; a small list is not treated as failure |
| All those tasks have low priority | A low-priority-list tease naming a task to work on |
| Overdue, repeatedly postponed, or tiny tasks | The relevant deadline, postponement, or quick-win reaction |
| Started work | **Still working on…?**, after its estimate plus the chosen gap |
| Browse Today and leave without any task action | A leaving-without-progress reaction, at the chosen gap |
| Other unfinished work | A progress check-in naming the next task |

The first extra nudge waits a full gap after leaving or the latest task action; subsequent extra nudges wait that same gap after the last nudge or regular reminder. Task actions reset inactivity and clear stale away notifications. Opening Bite pauses extra nudges. Completing or deliberately clearing all tasks suppresses empty-list teasing for that day. Future tasks wait until their day, while completed and archived tasks are excluded. Bite only knows activity in Bite, not whether you are working elsewhere.

Deadline reminders have priority and keep their own timestamps and per-task cooldowns. Extra nudges do not change deadlines, tasks, history, or that cooldown. A delayed background check sends one current nudge rather than a burst of missed intervals. Daily **Plan my day** is still a separate once-per-day prompt.

**Task notifications** and Android notification permission are required. **Spoken task reactions** uses matching human recordings through the existing alarm permission, volume, silent-mode, and DND rules. The notification headline identifies the current situation and task; its caption stays paired with the actual recorded words. Small-list and low-priority situations use the existing human stalling/progress recordings. Missing Sweet recordings remain text-only. No new human pack or database migration is included.

WorkManager and exact alarms use the chosen interval, including 15-minute gaps, but Android battery restrictions can delay delivery. Timestamps and the selected gap persist across process restarts and updates.

## Caption and voice consistency in 1.9

Each reaction selects its caption and recording together. Today, due/planning notifications, Test voice, Voice settings, and the simulator use the selected clip's transcript. **Play reaction** and **Hear clip** replay that exact recording, including after rotation. Test voice shows its caption and any playback explanation inline, without covering controls.

**Sweet teasing only plays recordings assigned to Sweet teasing.** No full Sweet human voice pack is bundled yet. Missing situations show affectionate text with a clear **Text only** explanation; they never play an unrelated Classic or High on Life clip. Classic and Sweet keep separate actor choices and custom recording assignments. Scripts remain scripts until an actual human performance is supplied; there is no generated or cloned speech.

In **Voice settings**, recording a line saves its words as the caption. Importing audio opens **Caption & style**: enter the actual spoken words, or leave the transcript blank. Without a transcript, Bite displays the recording's name as a label, not a guessed quote. Use the edit icon beside a saved clip to correct its caption or move it between Classic and Sweet. Older recordings are preserved under Classic because they contained no style metadata; they can be assigned to Sweet with the same edit action, without copying or deleting their audio. Custom recordings must be enabled to play. A removed recording cannot silently turn into a different voice on replay.

The pack preparation tool now keeps supplied Classic and Sweet performances in separate directories and labels each catalog with its style. Both routes still require supplied human recordings.

The functional review also aligned the internal Undo limit with the visible ten seconds, deferred widget refreshes until recurring/Undo transactions commit, made notification buttons reject stale task revisions, and closed voice settings when a widget opens a task draft. Task database schema, saved tasks/history, and signing identity are unchanged.

## Simulate a day and fast-forward in 1.8

Open **Sidekick → Test & troubleshoot → Simulate a day**. **Run day** walks through 34 events from an empty morning to the following morning, waiting for each recording before continuing. **Pause**, **Next event**, **+5 min**, **+1 hour**, **Jump to situation**, and **Reset** let you explore at your own pace. Fast-forward advances sample timers; it does not perform queued task actions. If you then select Next event, that action happens at the advanced time. Jump rebuilds the sample day up to the selected situation. Only the latest reaction is played after a time jump; earlier reactions remain in the simulation log.

The guided day covers all 12 reaction categories, including empty-day planning, on-time/late completion, three postponements, idle/tiny-task avoidance, leaving and returning, recurrence, deletion, Undo, silent widget completion, and quiet hours. It includes repeated away check-ins using the selected gap, without a daily cap. **Leave sample app / Return to sample app** lets you explore these timers manually while keeping the real app open. The **Jump to situation** chooser also offers isolated **An empty list**, **Just three tasks**, and **Only low-priority tasks** previews. These start at 09:00 with the sample app already away; advance by the nudge gap to see the first reaction and keep advancing for repeats. **Sample nudge gap** starts from your saved value and can be changed for the simulation only, without changing real settings or notifications. Expand **Sample tasks** and **Simulation log** to inspect the results. Undo uses ten seconds of virtual time, so advancing five minutes expires it immediately.

The simulator keeps sample tasks/history and its clock in memory/saved screen state. It never writes tasks, history, deletions, reminder timestamps, or user settings, never changes the phone's clock, and never schedules real notifications. It models enabled reminders/voices, planning at 09:00, and quiet hours at 22:00–08:00, using the app's actual reminder, reaction, and recurrence policies. Existing real reminders continue normally. **Classic / Sweet teasing** selects the simulation's reaction style without changing your saved preference; in 1.9 it selects matching recordings too. Audio uses your saved human voice, custom recordings, and volume; Android media/DND rules still apply. These are explicit previews, so the phone's ringer mode and real quiet-hour settings do not suppress them. Selecting new reactions advances the shared recording rotation; replay keeps the same clip. Full written sentences still need matching human recordings.

Leaving the screen or backgrounding the app stops playback and the guided run. Rotation restores sample progress while keeping autoplay paused. The simulator checks app behavior without waiting all day; actual lock-screen notification delivery, battery restrictions, and physical audio routing still require a phone test. This release keeps the existing database schema and signing identity.

## Daily planning and Sweet teasing in 1.7

**Sidekick → Plan my day** adds a daily prompt when there are no pending tasks at all and no task activity that day. It is enabled by default at 09:00, with a time picker and an off switch. Existing future tasks also count as a plan. Adding, editing, starting, completing, deleting, or restoring tasks suppresses the empty-list nudge for that day, so finishing everything does not earn another demand for work. A separate activity timestamp preserves this behavior even when deletion removes task history. It does not create placeholder tasks or fake history events.

The prompt can appear on Today, or as a notification with **Add a task** that opens an unsaved draft. Foreground and background delivery share one saved daily limit and the existing reminder cooldown. Quiet hours defer delivery; a time inside overnight quiet hours moves to the following morning. The notification follows Task notifications and Android notification permission. Background audio additionally follows Spoken task reactions, Alarms & reminders access, and the existing volume/DND/silent rules. Foreground text still works with notifications or in-app voice disabled. Notification scheduling is subject to Android delays. If a notification arrives while the app is open, it consumes that day's prompt without starting background audio.

**Sidekick → Teasing style → Sweet teasing** selects affectionate written reactions and recording prompts for all 11 situations, including an empty day. Classic remains the default. Examples: “Babe, even your to-do list is playing hard to get.” and “You did it, babe. Proud of you, obviously.” In 1.9, audio is restricted to matching Sweet recordings; missing recordings remain text-only.

For your own voice on your girlfriend's app, select Sweet teasing, open **Voice settings**, choose a situation, and record the suggested line on her phone—or import an audio recording you have supplied. For affectionate teasing aimed at you, use the same mode on your phone and import/record the performances you want to hear. Saved clips override their individual categories and style when **Use my recordings** is enabled. **An empty day** has its own recording slot; Classic can use its actor's greeting, while Sweet waits for its own recording.

`voice-pack/sweet-recording-script.txt` and `.json` contain 22 full-sentence human-recording prompts, two per situation. They are scripts, not newly performed audio. `python3 tools/prepare_original_voice_pack.py /path/to/recordings --performer "Name" --style sweet` bundles supplied WAV performances as **Bite Sweet** in the ignored personal overlay. The classic script now also has 22 lines, including the empty-day reaction. Both pack routes require real input recordings.

## Recurrence, context, and Quick Undo in 1.6

Task editing now supports **Does not repeat**, **Every day**, **Weekdays**, and **Every week**. Completing a recurring task keeps that occurrence and its history, and creates one next future occurrence at the same local time. Missed dates are skipped instead of generating a backlog. Rescheduling adjusts the deadline used for the next recurrence. Reopening removes an untouched next occurrence; if the next occurrence has already been edited or started, reopening is rejected to preserve that work. Deleting a pending recurring task stops future occurrences; earlier completed occurrences remain in History.

In-app completion, postponement, and confirmed deletion offer a **10-second Undo** bar. Undo restores the prior task state and checks that neither the task nor a generated next occurrence changed. It never overwrites newer work. Undo for deletion restores the selected task and its saved history; after the bar disappears, the deletion marker keeps old backups from recreating the task. Undo is local to the current open screen session; leaving/recreating the activity can dismiss it. Previously exported files are unchanged by Undo.

Bite now distinguishes the third and later postponements of the same task, and completing an overdue task. Each has its own written lines and recording category. Existing human packs fall back to their postponement/completion clips until a dedicated recording is supplied.

The full original human pack is **prepared for recording, not recorded yet**. `voice-pack/recording-script.txt` and `.json` now contain 22 original lines across 11 situations. Start with the [three-line audition brief](voice-pack/audition-brief.txt) to choose one consistent human performance before recording the full pack. Voice settings shows which situations have custom recordings and offers **Next missing situation**. Record/import a whole sentence for each situation. To bundle externally recorded performances, run `python3 tools/prepare_original_voice_pack.py /path/to/recordings --performer "Performer name"`; all 22 files must exist. The tool validates and normalizes real input files, creates no speech, and places the personal pack in the ignored overlay. After rebuilding, **Bite Originals** appears as a voice choice. Existing real recordings remain available throughout.

Database version 3 adds recurrence and snooze context through a non-destructive 2→3 migration; 1→2→3 is supported. JSON backup version 3 includes these fields and reads older v1/v2 backups with non-repeating defaults. UI concepts under `design/concepts/` are proposals; no candidate redesign has been applied.

## Minimal UI and reactions in 1.5

Today now opens directly onto a compact heading, filters, and task list. A fixed **New task** button sits below the list, so it cannot cover task actions. The large hero, mascot panel, and decorative progress sections are removed. Voice/volume controls come first in Sidekick; widgets and backups are under **Widgets & backups**. The latest reaction has a small play button and an explanation if automatic audio was suppressed.

Adding, starting, finishing, and postponing each play their assigned human recording when in-app voice is enabled. Overdue tasks can now react while Today is open without requiring notification or alarm permission. Starting one task no longer silences later idle nudges for the entire visit. Returning after leaving Today without progress can trigger the return reaction. Automatic nudges are spaced at least five minutes apart; due reminders retain the shared 30-minute/4-hour cooldowns. Quiet hours, silent mode, DND, and saved volume are preserved.

The three included actors have 16 audio edits each, originally two for each of eight situations. Version 1.14 curates five of those recordings for each frequent empty-list, idle, and leaving/clearing situation. Complete phrases are trimmed, joined with short pauses, and normalized for consistent loudness. These are real human performances, not synthesized or cloned voices. They are short reactions rather than newly recorded readings of the app's full roast text. **Voice settings** lets you select a situation, read its trigger, and preview the exact assigned recording. Custom recordings still override their category, and the selected actor is preserved during updates.

## Playback and deletion

Bundled voices, including the optional High on Life clips, are now mono 16-bit PCM WAV at 44.1 kHz and play directly through AudioTrack. This removes the compressed-media decoder and APK file-descriptor paths used in earlier releases. WAV headers, byte sizes, channels, sample rates, sample lengths, and duration are validated before playback. Imported compressed files still use MediaPlayer; unrelated setup failures are reported as player errors rather than misleadingly called decoding errors. Both playback paths stop and release audio focus on cancellation, leaving the app, and user stop.

**Delete task** is available from task actions, the task editor, and completed/archived history. A confirmation names the task and explains the short Undo window before permanent removal. Archive remains the reversible option. Deletion is atomic, refreshes widgets, clears reminders, and stores only an ID/timestamp marker to stop old drafts and backups from recreating the task.

Database version 2 originally added the deletion-marker table through an explicit 1→2 migration, leaving existing tasks and history unchanged. JSON backup version 2 includes deletion markers; version 1 backups remain readable. Restore merges newer tasks/history and applies saved deletions after confirmation. A deleted ID stays deleted; a new task gets a new ID.

### Media volume behavior

Voice clips use `USAGE_MEDIA` and `STREAM_MUSIC` consistently. Earlier builds checked notification volume but used system-sound audio attributes, and blocked explicit previews in silent/vibrate modes. Tests reproduce muted notification/system channels with audible media, vibrate previews, priority-DND previews, and denied audio focus. Muted app volume, muted media, DND, focus denial, unavailable files, decoding failure, and timeout have distinct messages. User-stopped previews produce no error. Automatic reactions retain silent/DND and quiet-hour suppression.

This personal build also includes four High on Life clips from the user-selected [Voicy soundboard](https://www.voicy.network/official-soundboards/games/high-on-life). Select **High on Life** under **Sidekick → Voice settings**. Starting and adding rotate “LEZDUIT!!” with the approving clip; completing rotates “We Did It!” with approval. Empty-list, stalling, postponing, returning/clearing, overdue, and tiny-task reminders rotate “LEZDUIT!!” with the paused-game clip. Each situation has two alternatives, with independent saved cycles. The game option contains strong language. Custom recordings still take precedence when enabled.

Personal audio assets are in the ignored `app/src/personal/assets/` overlay and are included only in local builds where present. Each clip’s source is recorded in the overlay catalog and credits. These game recordings are not covered by the original pack’s CC BY license; no redistribution license is claimed for them. A normal source checkout includes the three CC BY actors and custom recording/import support.

## Human voices and custom roasts

Open **Sidekick → Voice settings**. Choose Alex Brodie, Sean Lenhart, or Meghan Christian and use **Hear voice** to preview the selected reaction category. The included pack contains 48 human reaction edits: short acted phrases, sighs, refusals, and encouragement matched to app actions. Captions show these actual short phrases. This pack does not perform the original longer scripts or dynamic task names. Sweet requires its own recordings.

For longer, personal roast lines:

1. Choose a reaction category: overdue, postponing, leaving without progress, stalling, tiny-task avoidance, added, completed, or started.
2. Tap **Record a roast**, grant microphone permission, and read the suggested line or your own script. Tap **Stop recording**, listen, then **Save recording**. Recording stops after 30 seconds; leaving the screen cancels an unfinished take.
3. Or tap **Import audio** and choose a recording through Android's file picker. Audio is validated and copied into private app storage, independent of the original file's location.
4. **Use my recordings** enables custom clips (automatically enabled on save/import). Categories without recordings use an actor of the same style, if one exists; otherwise they stay text-only. Up to five clips per category and style, 30 seconds / 5 MB per clip, 20 MB total. New reactions rotate clips when alternatives exist; Replay keeps the same clip. Preview, edit captions/style, or remove saved clips in the same screen.

In-app reactions and background due reminders use the same voice selection and alert volume. There is no synthesized fallback, voice cloning, or audio upload. Microphone access is requested only when recording. Dictation remains a separate feature handled by the phone's speech recognition app.

Bundled recordings are from [Super Dialogue Audio Pack v1 by Dillon Becker](https://dillonbecker.itch.io/sdap), licensed under [Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/). Complete recorded phrases were trimmed and joined with 160 ms pauses, loudness normalized, and converted to mono 16-bit PCM WAV at 44.1 kHz with metadata removed. No endorsement is implied. Attribution is included in the app's Voice settings and `app/src/main/assets/voices/CREDITS.txt`; the catalog records original filenames and the source archive checksum. To reproduce the assets from the author's original download, run `python3 tools/prepare_voice_pack.py /path/to/archive.zip` (requires ffmpeg).

Custom recordings live in private `files/voice_clips/` and remain intact during signed app updates. Android backup/device transfer includes them when supported and enabled. **Back up everything** includes recordings and preferences. Task/history JSON exports remain task-only; retain a complete backup before uninstalling or clearing storage. Unfinished takes are temporary and discarded when the voice editor is destroyed.

## Home-screen widgets

Choose **Sidekick → Widgets & backups → Add home-screen widget**, or long-press the home screen and choose **Widgets → TaskBite → TaskBite · Today**. The launcher asks you to confirm placement. Long-press the placed widget and drag its edges to resize it. Compact, medium, and large layouts adapt to the available space; exact cell dimensions depend on the launcher.

- The scrollable list shows pending tasks due today plus earlier overdue tasks, ordered by deadline. Tap the circle to complete directly without opening the app. Completion is saved to the same database and history as in-app actions. Repeated taps on stale rows do not reopen completed tasks.
- Tap a task title to edit it. **+ Add** opens a new task draft. **Voice** opens the phone's recognizer, then a draft for review; it never saves an unreviewed transcript. If speech recognition is unavailable, the text editor opens instead.
- Pending counts include future tasks; overdue counts include only unfinished, unarchived tasks whose deadline has arrived. Larger layouts show completion progress and deadline details.
- Saving, editing, completing, reopening, postponing, archiving, restoring, and importing enqueue a refresh of all placed TaskBite widgets. Widget completion also requests an immediate refresh. The UI and widget use the same task data.
- A 15-minute background job updates time-based counts even with notifications disabled. Reboot, update, clock/timezone/date changes, reopening the app, and the widget's refresh button also trigger updates. Android can delay background refreshes, especially after force-stop or in battery-saving modes. Removing the last widget cancels widget-specific work.
- The first 50 due tasks are shown to keep Android's widget payload bounded; larger lists include a **See all … in TaskBite** link. Counts include the entire list. Multiple widget instances show the same task list and refresh together.

Widget task names are visible on the home screen. A fresh installation shows a setup prompt until onboarding is complete. Widget completion is silent; the existing app and reminder voice settings still apply to those features.

Implementation references: [Glance layouts and resizing](https://developer.android.com/develop/ui/compose/glance/build-ui), [widget state and updates](https://developer.android.com/develop/ui/compose/glance/glance-app-widget).

## Reminder behavior

- Normal notifications use WorkManager: the next deadline, planning prompt, or away check-in plus a 15-minute periodic recovery check. Timing is approximate; Doze and manufacturer battery management can delay it.
- Opt-in spoken reminders and away check-ins use exact alarms and a short-lived media playback foreground service. Its notification includes **Stop audio**. This allows audio focus on Android 15+, where background apps without a foreground service cannot request it.
- Each delivery rechecks whether a task is still pending, enabled notifications, quiet hours, and cooldowns. Regular deadline/planning reminders retain their 30-minute shared cooldown and deadline reminders retain four hours per task. Extra away check-ins use the selected gap independently and yield to imminent deadlines.
- Phone restarts, app updates, clock/timezone changes, and alarm permission grants reschedule spoken reminders.
- App abandonment: leaving Today after at least 10 seconds without any task action can trigger an away check-in after the selected gap, subject to quiet hours and deadline priority. Returning cancels it. Opening voice input, calendar, backup pickers, or settings does not count as abandonment. The normal inactivity gap still runs if you stay outside Bite, and returning resets it. A pending abandonment can produce an in-app reaction on return if it was not already delivered outside.
- Idle: five minutes without interaction on Today, with pending work due today, even if a task was started or completed earlier in the visit, produces an in-app roast. Tiny tasks are tasks estimated below five minutes.
- Force-stopping the app prevents background delivery until it is opened again. On-device behavior depends on notification permissions, audio routing, and the OS.

Android references: [WorkManager scheduling](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), [exact alarms](https://developer.android.com/develop/background-work/services/alarms), [foreground service launch rules](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [audio focus](https://developer.android.com/media/optimize/audio-focus).

## Data safety and future updates

Tasks and history live in `taskbite.db`. App preferences use `taskbite.xml`. Neither is reset on launch or app version changes. Database schemas are exported under `app/schemas/`. There is deliberately **no destructive migration fallback**.

Install future signed updates over the existing app. Do not uninstall or clear storage first. Preserve all three identities: application ID, database name, and signing key. Increase `versionCode` for every release. If changing the database schema, increase its version, add an explicit Room migration, and test old databases containing tasks and history before releasing. A missing migration fails instead of deleting data; the automated guard test verifies this behavior. See [Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions).

Keep private, independent backups of **both `taskbite-release.jks` and `signing.properties`**. Losing the signing key prevents normal updates of existing installations. These files contain sensitive signing material and must not be committed or shared with the APK.

Use **Sidekick → Widgets & backups → Export tasks & history** before uninstalling, resetting, or changing phones. Backups contain readable task names, notes, and history. Store them privately. Android system backup/device transfer includes the app's data when enabled by the device, but it is not a guarantee against device loss. Manual exports are portable; voice preferences are intentionally not imported.

Automatic release discovery uses GitHub Releases. There is no cloud account or multi-device synchronization. Signed APKs can also be installed manually over the existing app without resetting local tasks.

## Build

Use JDK 17 or 21 and Android SDK platform 35:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew testDebugUnitTest lintDebug assembleRelease
```

For a fresh source checkout, use `assembleDebug` for development. Create release signing material **only for a brand-new distribution** with `python3 tools/create_signing_key.py`. To update this existing distribution, restore its original signing files instead. The creation script refuses to overwrite existing files.

Release APK: `app/build/outputs/apk/release/app-release.apk`.

To prepare assets for a future updater release, run:

```sh
python3 tools/prepare_update_release.py app/build/outputs/apk/release/app-release.apk \
  --output build/update-release --notes path/to/release-notes.txt
```

This verifies the original signing certificate and APK metadata, then writes a versioned APK, checksum, and `taskbite-update.json`. It does not upload anything. Publish the APK and manifest together as assets on one stable, non-draft GitHub release, using the suggested tag. The updater reads the latest stable release, so mark the intended version as latest. Do not upload signing files. For public distribution, use `--public`: it rejects APKs containing personal voice assets whose credits exclude redistribution. The current personal build therefore needs a distribution decision before publication; a public build must use distributable recordings.

The manifest format is version 1, with `packageName`, increasing integer `versionCode`, `versionName`, `minSdk`, `apk` filename, byte `size`, lowercase `sha256`, and optional `notes`. The APK filename must match the uploaded asset. The preparation script derives these values from the signed APK instead of relying on manual entry.


To build the public APK without optional personal assets:

```sh
./gradlew -PincludePersonalVoices=false testDebugUnitTest lintDebug assembleRelease
```

To prepare a public release with the original signing key kept on this machine:

```sh
python3 tools/release.py --notes docs/release-1.19.txt
```

After committing and pushing the tested source to `origin/main`, add `--publish` to publish it. The script requires clean tracked source, refuses existing release/tag versions, checks the public-asset rule, verifies uploads while the release is still a draft, and never uploads signing files. GitHub Actions independently tests/lints public source on pushes and pull requests, and compiles the isolated phone test app. No signing key or write-capable release token is stored in CI.

To compile the separate phone test app without connecting a device:

```sh
./gradlew -PdeviceChecks=true -PincludePersonalVoices=false assembleDebug assembleDebugAndroidTest
```

Run `python3 tools/run_phone_checks.py` later with an authorized USB-debugging phone. This targets `com.dracula.taskbite.devcheck`, not the production installation. Read [the phone testing guide](docs/phone-testing.md) for the remaining manual checks.

## Validation

The 1.19 checks cover real 3→4 database migration, checklist persistence/recurrence/Undo/search, stale-editor protection, Later today validation, pause/resume and deadline separation, notification interactions during a break, reminder-status fixes, complete backups with real audio and settings, archive tampering/path/size limits, repeat restores, personal-pack preservation, four distinct short scripts per situation/style, and compact/large-font UI flows. Physical-phone tests are prepared for the user to run later; they are not reported as passed.

The 1.18 checks cover stable release parsing, trusted URLs and redirects, bounded responses, SHA-256 failures, signing/package/version/Android validation on API 26 and 33, offline checks retaining a ready update, verified download and installer URI grants, private-file isolation, cache cleanup after an upgrade, Wi-Fi versus manual network constraints, large Unicode release notes, silent update notifications, saved controls at 320 dp, explicit installation permission/confirmation, and draft-safe navigation. The preparation helper is checked against a real signed APK and rejects public packaging of personal assets. These host checks do not perform an actual Android package replacement on the phone.

The 1.17 checks cover the separate testing screen, pending test status across activity recreation, canceling without changing real tasks/history/settings or the next regular alarm, repeated taps without resetting the countdown, and blocked/expired/sent results. Native layout previews cover the testing tools at 411 dp and 320 dp widths. The full sample-day and real notification-flow suites also exercise the updated navigation.

The 1.15 regression reproduces and fixes dismissal audio being interrupted by a concurrent reminder alarm. Native Android simulation verifies actual PCM playback, direct foreground-service dismissal intents, clear-all deduplication, matching popup captions, muted playback diagnostics, unchanged task/history data, and service cleanup. Additional checks cover old receiver-based notifications, missing Sweet audio, preserved custom recordings, separate clearing rotation, and the new simulator scene.

The 1.14 checks exercise complete recording cycles with fresh selector instances, cycle boundaries, alternating nudges and dismissals, overlapping pools, changed/duplicate/single-recording pools, actor preservation, and exact captions. Repeated 15-minute empty-list delivery with every reminder dismissed runs through two full cycles for the standard and personal game voices while preserving empty task/history data and the next reminder time. Catalog checks reject the reported greeting from recurring standard situations.

The 1.12 checks add real worker execution with voice alarms enabled/denied, notification-only fallback, persisted inactivity state, quiet-hours and permission gates, the task-safe background test, queued voice expiry/cancellation, direct activity and dismissal intents, clear-all deduplication, unanswered versus opened follow-ups, actual dismissal-service PCM playback, notification-open caption/audio identity, rotation, and the notification-reaction switch. The 1.11 checks cover repeated 15/30/custom-minute scheduling and delivery, empty lists with planning disabled, small lists, low-priority changes, legacy cap removal, gap validation/persistence, actual background caption/PCM identity, missing Sweet recordings, task/deadline preservation, concurrent delivery, deadline priority, quiet hours and daylight saving, permission denial, task-action resets, home/return and external-picker lifecycle behavior. Settings are checked at 411dp and 320dp widths. Locked-phone delivery and physical audio still need a device check.

The 1.9 regression checks exercise caption/audio identity, replay after rotation, all 11 Sweet slots, missing/deleted recordings, legacy audio preservation, caption/style editing, notification audio and Done/Tomorrow actions, repeated notification taps, canceled dictation, calendar export, widget-to-editor navigation, and widget refreshes after complete transactions. Existing task/history, backup, migration, recurrence, permissions, audio, widget layout, and day-simulation suites also run. Android simulation validates these paths but cannot certify physical microphone/speaker quality, OEM battery restrictions, or an actual APK upgrade on the phone.

Simulator checks cover the 34-event guided day, all 12 reaction categories, manual clock jumps and leaving/returning, recurring away check-ins, empty-list/small-list/low-priority previews and simulation-only gap changes, reminder cooldowns, quiet hours, recurrence/deletion Undo, deterministic replay, automatic run/pause, compact controls, progress after rotation, pause on background, actual recording selection, and unchanged real tasks/history/deletions/preferences/notifications after a full run.

Automated checks cover persistence after closing/reopening the database, completion idempotency, stale-editor protection, archive/restore, calendar-day snoozing across daylight saving changes, lossless backup round trips, duplicate/malformed backup rejection, stale-backup merging, the real v1→v2 data-preserving migration, deletion confirmation and stale-backup safeguards, the missing-migration data-preservation guard, quiet-hour boundaries, reminder eligibility, word limits, saved voice volume, separate media/ringer behavior, specific playback failures, PCM parser validation and playback cleanup, licensed asset coverage, custom audio persistence/fallback/validation, app UI flows that verify the exact PCM recordings for add/start/postpone/complete and explain suppressed audio, widget counts/actions, refresh hooks, and widget layouts.

Before public distribution, check on a physical phone:

1. Add a typed and a spoken task. Review the transcript and deadline.
2. Start, finish, reopen, postpone, archive, and restore a task; inspect History.
3. Export a backup, change a task, and restore the old backup; confirm the newer edit survives. Delete another task and confirm its history disappears, other tasks remain, and restoring the older backup does not recreate it.
4. Grant notification/alarm access, disable quiet hours temporarily, and set a task due soon. Lock the phone and verify the spoken reminder, Stop audio action, Done action, and Tomorrow action.
5. Check silent mode, DND, headphones, permission revocation, and reboot. Declining permissions must leave task management usable.
6. Save a calendar event. Editing it in the calendar must not imply a synced change in TaskBite.
7. Install a higher-version APK signed with the same key over an existing installation; check tasks, history, and preferences remain.
8. Preview each included actor. Record, preview, save, and import a roast; verify custom/fallback selection, volume, cancel on background, and microphone permission denial.
9. Add two widgets on the Galaxy S25 launcher. Resize one small and one tall, scroll the lists, complete a task, and check both widgets update. Try quick-add and voice with the app closed and already open. Change a deadline, archive/restore, import a backup, then check midnight/timezone/reboot refreshes and removal of the last widget.

The automated checks run on the development machine. Sending the APK to a phone does not verify physical-device audio capture/playback, permission behavior, background scheduling, or an actual APK upgrade.
