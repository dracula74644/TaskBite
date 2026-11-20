# Phone checks for Bite 1.19

These checks are prepared, not yet run on the Galaxy S25. Automated host tests cannot confirm what the speaker sounds like or how Samsung handles a locked phone.

## Automated checks on a connected phone

Enable USB debugging, connect the phone, and accept its debugging prompt. From the TaskBite project run:

```sh
python3 tools/run_phone_checks.py
```

This installs **Bite Test Lab**, a separate `com.dracula.taskbite.devcheck` app and its test runner. It does not install, clear, or uninstall your normal Bite app. It checks real database persistence, complete backup restoration with real audio, and deadline-notification state. The output is saved under `build/reports/phone-checks/`. It never marks listening or lock-screen checks as passed.

## Check by listening and interacting

Use Sidekick → Test & troubleshoot → Send test notification for temporary tests. Use the test app for any sample tasks. Record Pass, Fail, or Not tested for each item.

| Check | What to do | Expected result | Result |
|---|---|---|---|
| Locked screen | Send a test, press Home, lock the phone | Notification arrives; with voice enabled and sound allowed, its caption matches the recording | Not tested |
| Clearing | Send another test and swipe it away | One clearing reaction; no task completed and no replacement notification | Not tested |
| Opening | Send another test and tap it | Bite opens once; the reaction matches the caption | Not tested |
| Quiet hours / DND | Use a real sample deadline during quiet hours, then with DND | Automatic teasing respects both; explicit previews follow Android media rules | Not tested |
| Break | Pause teasing for an hour; leave deadline reminders enabled | No automatic teasing; due-task notifications remain plain | Not tested |
| Break resume | Use Resume teasing, or let the hour expire | Normal scheduling resumes; the saved nudge gap stays the same | Not tested |
| Bluetooth | Connect headphones, then test voice; disconnect and repeat | Audio follows the phone's chosen media output | Not tested |
| Reboot | Add a sample future task; restart the phone and unlock it | Task remains; reminders resume subject to Android restrictions | Not tested |
| Large text | Temporarily raise font size; open Today, editor, Sidekick and backup confirmation | Text wraps; controls and actions remain reachable by scrolling | Not tested |
| TalkBack | Turn on TalkBack and navigate by swiping | Search, checklists, pause controls and recordings have clear labels and can be operated | Not tested |
| Recording | Record a complete suggested line, preview and save it | Sound is clear, caption is exact, correct style and situation are used | Not tested |
| Backup | Back up everything; import into the test app | Tasks, steps, history, recordings and selected settings restore; newer task edits survive | Not tested |
| Signed update | In normal Bite, make a complete backup; install the new signed APK over it without uninstalling | Existing tasks, history, steps, settings and recordings remain; version increases | Not tested |
| Update download | With a newer public release available, check App updates | APK verifies; installation only starts after your explicit tap and Android confirmation | Not tested |

Restore your normal font, DND and sound settings after testing. A simulator result or a successfully sent APK does not count as a physical-phone pass.

## Preloaded meme packs (1.20)

Not yet tested on a physical phone. Update through Bite's App updates screen. In Voice settings, select Meme Sidekick and preview An empty day. Check the spoken words against the caption. Select Unfiltered Memes to opt into strong language. Both packs must work with airplane mode enabled, without an additional download.

Preview all 16 situations, reopen Bite, and restore a complete backup. Check that an empty-day notification still uses the empty-day pool after being opened or ignored. Notification-open audio must not claim a task started. Listen for cut-off syllables and volume consistency on the phone speaker.
