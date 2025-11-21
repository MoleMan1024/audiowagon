# Changelog

All notable changes to this project will be documented in this file.

Listed here are only software version that have been made public. A jump in the version number (e.g. from 0.2.5 to
0.3.4) without any inbetween version means that multiple internal releases were made that were not made available to the
public.

The date given for a release is when the app is pushed into Google Play Store. However it will be visible only after
Google's review process which can take a couple of days.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project loosely follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.11.2] - 2025-11-21

### Changed

- After waking up from suspend we receive an event that the previous USB device was detached. Wait 3 seconds after
  waking up, so we do not try to access this old USB device instance (because it will fail in `initConnection()`)
- bump some third-party library versions


## [2.11.1] - 2025-10-24

### Added

- on the playback view you can now enable "increased playback speed". This is especially useful when listening to
  podcasts or audiobooks that are quite long. You can choose the increased playback speed via AudioWagon settings. Three
  settings are supported: 1.2 times, 1.5 times or 2 times faster than regular playback speed, depending on your
  preference.

### Fixed

- reset audio player during suspend to avoid that early `onPlay()` callbacks during wakeup will try to read
  data from USB drive when it is not yet available for reading
- reset current playback queue item during suspend, to avoid a playback queue item is shown after wakeup that can not
  yet be played back

### Changed

- update browse view earlier during wakeup after suspend
- show "Please wait" instead of "USB drive ejected or could not be read" while still waiting for USB permission to be
  granted automatically during startup
- wait 20 seconds before updating attached USB devices and asking user for USB permission so that `USBDummyActivity` has
  sufficient time to hopefully be started (increased from 10 seconds). You can always trigger the USB permission popup
  manually by entering AudioWagon settings if you do not want to wait (in case permission was not yet granted)
- try to access connected USB devices once directly after wakeup in case USB access permission is already
  present (but do not show USB permission popup yet if permission is missing). This should avoid the 20 seconds waiting
  time from previous bullet point, in case the USB permission was already granted
- ignore some more unsupported USB devices
- bumped some dependency versions


## [2.10.4] - 2025-09-02

### Fixed

- minor translation improvements in Polish and Spanish

### Changed

- wait 10 seconds before updating attached USB devices and requesting USB permission so that `USBDummyActivity` has
  sufficient time to hopefully be started (increased from 2 seconds)
- reduce amount of characters used in group titles in General Motors headunits because they use a horizontal
  layout that leaves little room for text
- add some emoji/icons as subtitles with numbers of items in groups to make it more clear those entries are groups
- change icon of file groups to make it more clear it is a group
- reworked `AssetStorageLocation` related classes to be more generic (for quick local tests)
- log manufacturer, brand and model during startup
- check `isInitialized` in many more places where `lateinit` variables are used in case methods are called at
  unexpected times during startup
- bump `compileSDK` to Android 35
- bump some library versions
- use latest NDK to comply with Google's 16 kB page size requirement

## [2.10.0] - 2025-06-14

### Added

- translations for Spanish

### Changed

- ignore another USB hub device


## [2.9.1] - 2025-05-29

### Added

- Some Polestar 4 and Volvo XC60 2026 drivers reported that audio playback was stuttering a lot, especially noticeable
  with large FLAC files. Such errors were not seen in Polestar 2 and other older Volvo models. Possibly the new
  Snapdragon-CPU based headunit has different USB chipsets and drivers that look like they perform worse than previous
  headunit generations. Some changes were made in AudioWagon to try to reduce the I/O stress on the USB device during
  playback:
    - Album art thumbnails will now be cached onto the car's flash disk during indexing (previously they were read from
      USB drive on-demand). This increases the flash disk space required by AudioWagon quite a bit. The database
      deletion option in AudioWagon settings was hence modified to delete both the metadata database and any cached
      album art from the car's disk. A checksum of the album art thumbnail is also written to disk, to avoid unnecessary
      writes to the car's flash disk which could wear it out. In case no cached album art is found on disk, the old
      mechanism is used as fallback. **To use this feature you must delete the database and re-index metadata.**
      [#174](https://github.com/MoleMan1024/audiowagon/issues/174)
    - Album art can now be disabled via the settings

### Changed

- Some changes were made in AudioWagon to try to reduce the I/O stress on the USB device during playback:
    - Number of cached blocks of USB data was increased and lock usage was modified


## [2.8.14] - 2025-04-30

### Fixed

- Playlist files that have file paths containing invalid characters (e.g. NUL character) would throw an exception and
the whole playlist file could not start playback. This has been improved so that such file paths will be ignored and the
rest of the playlist file will be parsed properly [#173](https://github.com/MoleMan1024/audiowagon/issues/173)


## [2.8.13] - 2025-04-16

### Fixed

- A combination of changes done in the last two app versions caused an error where the USB device handle was not
  properly released when the screen was turned off (e.g. when parking the car). This sometimes caused the USB device to
  not be detected properly during next startup. Errors like `Could not send data to OUT endpoint: error 19 (No such
  device)` would show in the log and the user would need to re-plug the USB device. It looks like the *kotlin-reflect
  library* does not work properly with AAOS used in Polestar/Volvo cars which caused the broadcast receiver that
  notifies when the screen turns on or off to not work anymore. The kotlin-reflect library has been replaced with a
  different approach [#172](https://github.com/MoleMan1024/audiowagon/issues/172)


## [2.8.10] - 2025-04-12

### Fixed

- Avoid `ConcurrentModificationException` for list of broadcast receivers
- Playlist files that have URL encoded paths (e.g. created by VLC) were not working in AudioWagon. This has been
  fixed [#170](https://github.com/MoleMan1024/audiowagon/issues/170)

### Changed

- small optimizations for USB broadcast receivers based on Crashlytics logs, for first onPlay() event after startup and
  for foreground service usage


## [2.8.7] - 2025-03-19

### Fixed

- After 4 years Polestar/Volvo have finally changed something regarding the [USB permission popup](https://moleman1024.github.io/audiowagon/faq.html#why-does-the-permission-popup-appear-each-time-the-always-open-audiowagon--checkbox-does-not-do-anything). The USB permission popup should no longer appear constantly if you
  upgrade to car software version 3.3.16 or higher (the one with wired Android Auto) and the most recent AudioWagon
  release [#133](https://github.com/MoleMan1024/audiowagon/issues/133)
- avoid to trigger multiple attached USB device updates at the same time that could cause overlapping permission popup
  calls which would cancel the previous permission popup and make the next one possibly not appear at all
  [#161](https://github.com/MoleMan1024/audiowagon/issues/161)
- do not try to shuffle an empty playback queue
- try to avoid some race conditions in broadcast receivers during suspend-to-RAM

### Added

- translations for Simplified Chinese

### Changed

- register/unregister runtime USB broadcast receivers when car goes into suspend or wakes up again
- stop player when car goes into suspend
- internal improvements for binder code
- ignore another USB hub device
- improved logging of Kotlin coroutines
- improved compatibility with Android 14 [#166](https://github.com/MoleMan1024/audiowagon/issues/166)
- improvements to instrumented tests to not depend on a big SD card image
- migrate Gradle scripts to Kotlin and use version catalog for dependencies
- use Android Room Gradle plugin
- bumped some dependency versions


## [2.8.0] - 2024-10-05

### Fixed

- when starting up a Renaul Austral car the app sometimes reports an error "audio focus denied". This might be related
  to some boot/startup chime that this car will play during initialization which conflicts with the feature to resume
  music playback that was playing when the car was shut down previously. Such audio focus errors are now ignored if they
  appear within 15 seconds of starting the app and the app will try again to re-start playback 5 seconds later. The
  timings are guesswork based on a single log file I received, I do not have such a car available for fine tuning
  [#151](https://github.com/MoleMan1024/audiowagon/issues/151)

### Added

- some things added after noticing the Android Automotive media browser documentation has been updated with some new
  info. However none of this will likely have any effect in Android <= 13:
    - handle shuffle-request extras in certain media session callbacks binder calls. No clue what this does, but it is
      mentioned in documentation
    - added a "TintableAttributionIcon"
    - log if custom browser actions are supported. At least the Polestar 2 on Android 12 does not support such extra
      actions (e.g. "favorite", "download") linked to each media item.
    - differentiate normal browse view items and "category" items

### Changed

- when selecting a track from the tracks view, a randomized playback queue of tracks was created with the selected track
  as starting item. This causes issues for autistic people who expect the track list which is shown in alphabetical
  order to also play back in this exact order. This has been changed so that when picking a track from the "Tracks"
  browse view, a playback queue will be created using all tracks as shown in that view starting at the selected track's
  index. You can still create a random playback queue of tracks using either the "shuffle all" button or using the
  shuffle mode in the playback view [#157](https://github.com/MoleMan1024/audiowagon/issues/157)
- when selecting a single track from a search, the resulting playback queue will be shortened to maximum 400 items to
  avoid huge playback queues
- ignore media IDs that start with "sid:<number>". I saw this once in Crashlytics, it seems to be related to some new
  standard called "CMCD" which the app does not support
- ignore some more USB devices that are not mass storage devices
- re-enable minification to make app smaller which was accidentally turned off some time ago
- strip all logcat logging from release build since I can not access it anyway and to reduce stress on logd
- bump some library dependency versions


## [2.7.0] - 2024-07-07

### Added

- some Volvo cars do not come with a graphical equalizer like the Polestar 2 does. Some users also wanted to tweak the
  equalizer presets used by AudioWagon a bit. That is why you can now adjust the built-in 5-band equalizer to your own
  liking. To use this new feature, go to AudioWagon settings > Sound > Equalizer, enable the equalizer, select preset
  "customized" and adjust the equalizer bands as you like [#55](https://github.com/MoleMan1024/audiowagon/issues/55)

### Changed

- the log file will now include wall clock time and monotonic time. The latter clock should be unaffected by things such
  as the headunit being in sleep mode or wall clock changes e.g. based on GPS data
- log a warning message when reading from the USB filesystem is slow
- bumped target SDK API level as required by Google
- some updates of dependant libraries


## [2.6.11] - 2024-06-09

### Fixed

- in case the media player encountered an error, the playback position of the current track was lost. When the playback
  state was persisted afterwards, the missing playback position was persisted also. This meant the playback position
  could not be retrieved on next startup. This has been fixed: when there is a media player error, but a playback
  position was known previously, this last known, good playback position will now be used for persistance instead
  [#145](https://github.com/MoleMan1024/audiowagon/issues/145)
- when switching to a different media app (e.g. radio app) and back to AudioWagon, the playback position of the current
  track could be lost, this has been improved

### Changed

- album art JPEG files ending with extension ".jpeg" are now accepted (previously only file extension ".jpg" was
  allowed)
- the fake exception added in the previous version for debugging purposes was removed again


## [2.6.10] - 2024-05-13

More internal optimizations only, nothing that should be visible/interesting to end users.

### Changed

- rework all broadcast receivers to run in their own threads to avoid main thread ANRs sometimes seen in Crashlytics
- handle the media button presses slightly differently
- extract error codes from native code and log them to see why USB bulk transfers fail sometimes
- some more changes on how to handle foreground services and modified exception handling
- added a missing permission for foreground service media playback in manifest
- log a fake exception when filesystem successfully initialized to understand better what is going on in Renault cars


## [2.6.7] - 2024-04-26

### Fixed

- reverted a change for exception handling during shutdown that could cause the app to crash in rare cases
- avoid a `ConcurrentModificationException` of the playback queue by creating a deep copy of it before passing it to
  Android framework

### Changed

- modified logging to try to catch a rare situation where the app is stopped by Android 12 due to not reacting as
  expected
- updates of development libraries, Kotlin and Gradle version
- tack Java to version 11
- some internal changes regarding exception handling


## [2.6.6] - 2024-04-21

### Added

- A new repeat mode was added for a total of 3 modes: "repeat off", "repeat one", "repeat all". Depending on the mode,
  the current track/file will be repeated when it finishes playing, or the whole playback queue will be repeated (or
  nothing will be repeated). You can cycle through the modes in the playback view in the extras drawer (three dots on
  the right) [#139](https://github.com/MoleMan1024/audiowagon/issues/139)

### Fixed

- when fixing [#118](https://github.com/MoleMan1024/audiowagon/issues/118) a problem was introduced where tracks
  could be missing their parent album. This has been fixed.
- the icon shown in the *Files* view was inconsistent with other views (*Tracks*, *Album* and *Artist*) when no USB
  drive was connected. This has been fixed.
- fixed a `IndexOutOfBoundsException` seen once in Crashlytics for `getCurrentItem()` at end of playback queue
- try to avoid a `ConcurrentModificationException` seen once in Crashlytics in variable `buildLibraryJobs`.

### Changed

- improve compatibility with Android 12 for upcoming major Volvo/Polestar software update. More changes might be done
  in near future after I get the Android 12 update for my own car
- the code to check if the screen is currently on or off was modified to be more accurate
- handle situation where broadcasts `USER_PRESENT` and `SCREEN_ON` come multiple times or in different orders
- ignore some more USB devices that are not mass storage devices (Apple iPhone, iPod, Samsung phone, etc.)
- block the re-binding of `AlbumArtContentProvider` to `AudioBrowserService` for some seconds after unbinding because
  that probably only happens after the screen goes to sleep and we should not try to retrieve album art in that
  situation
- do no try to load album art when there are no storage locations
- changed the broadcast forwarding procedure in `USBDummyActivity`
- some internal changes for logging, exception handling and mutual exclusion


## [2.5.14] - 2024-02-19

It makes me happy to say that the app is now being used in at least ten-thousand cars around the world 😄

### Fixed

- In the albums browse view an "unknown album" could appear with zero tracks inside. This should contain any tracks
  without any album info in their metadata. However when artist and title were filled in the metadata, it would not
  show up here. This error only appeared in album view, in artist view this issue did not occur. When there are tracks
  that have artist and title in metadata, but no album, they should now be correctly collected in here
  [#137](https://github.com/MoleMan1024/audiowagon/issues/137)
- an issue was fixed that prevented a small speed improvement in the albums and artists browse view when groups of
  items were to be shown
- in Crashlytics I noticed that once during startup of AudioWagon the shared preferences could not be accessed because
  the user profile was locked with a PIN code. This should now be avoided when possible or the exception will be
  handled properly [#136](https://github.com/MoleMan1024/audiowagon/issues/136)
- in Crashlytics I noticed that someone tried to use a USB hub with a USB mass storage device and a mobile phone
  connected at the same time. The app erroneously tried to process the phone device as well. This should no longer
  cause an error [#137](https://github.com/MoleMan1024/audiowagon/issues/137). In general the app does NOT support
  multiple USB mass storage devices in parallel using a hub.

### Changed

- internal cleanup of some items found by SonarLint
- bumped version of some dependencies


## [2.5.9] - 2023-11-04

### Fixed

- depending on the data received from Google Assistant a voice search for an album could not work and the TTS would say
  "Sorry, something went wrong". This has been fixed [#131](https://github.com/MoleMan1024/audiowagon/issues/131)

### Changed

- the database tokenizer was updated to make it easier to search by typing or by voice. Previously entries such as "We
  Didn’t Start the Fire" would not be found if searching for "We Didn't Start the Fire" (note the different
  apostrophe!). This was especially problematic in voice searches, where such characters are not speakable. You might
  need to re-index metadata to see the changed behaviour.


## [2.5.7] - 2023-10-17

### Fixed

- fix a small issue with notification channels that is not visible to end users but spams Crashlytics issue log

## [2.5.6] - 2023-10-07

### Fixed

- ignore permission change events for USB device objects which are no longer connected
- updates to notification channels were posted from main thread which could make the app unresponsive (ANR) in rare
  cases. Now this is done from threads

### Changed

- try to recover from failed USB initializations whenever possible. But most of the times a recovery is impossible
  [#91](https://github.com/MoleMan1024/audiowagon/issues/91)
- wait 4 seconds before showing the USB permission popup. This should be sufficient time for the USB subsystem to
  power-cycle the USB device in case of issues that would otherwise cancel the permission popup
- react to USB timeout errors more quickly
- when USB device was attached, but permission to access it was denied, this was not easily visible. This situation is
  now shown in the main browse view also (lock icon)
- more logging
- internal refactoring


## [2.5.2] - 2023-08-16

### Fixed

- I discovered a forum post about some **issues in Renault cars** with Android 10 when using AudioWagon: After scrolling
  items with album art for some time, the album art squares would turn into gray squares and shortly later the GUI would
  partially break and not show anything at all anymore. I encountered a similar issue on my development build on a
  mobile phone, but only in the media browse view. I believe this to be the same issue. Volvo/Polestar users are
  *not* affected it seems, because they use Android 11 which does not have this bug. The issue seems to be only in cars
  with Android 10 when using album art. The way that album art is transferred to the GUI has been changed now, which
  should fix this [#127](https://github.com/MoleMan1024/audiowagon/issues/127).
- a small problem was introduced in last update: when starting up AudioWagon with no USB drive plugged in, the screen
  would say "Please wait" indefinitely. This message was not accurate and has been fixed.


## [2.5.1] - 2023-08-11

### Fixed

- minor translation inconsistency in German

### Changed

- some icons were updated

### Added

- the 4 view tabs at the top of the browse view ("Tracks", "Albums", "Artists", "Files") are now configurable in the
  AudioWagon settings. You can now show only those categories that you use and order them however you want (feature
  request [#124](https://github.com/MoleMan1024/audiowagon/issues/124)).
- translation for Hungarian
- some icons were added


## [2.4.7] - 2023-07-09

### Fixed

- artist groups were not being created correctly, because they did not take into account the rules for album artists
  in ticket [#22](https://github.com/MoleMan1024/audiowagon/issues/22). This had the effect that the last artist group
  was missing some artists. This has been fixed. To get this change working, you will need to re-index your database
  [#122](https://github.com/MoleMan1024/audiowagon/issues/122)
- As described in the [FAQ](https://moleman1024.github.io/audiowagon/faq.html#album-artists), only album artists shall be
  shown in the artists view. When an artist was added to the database, and it was not marked as an album artist in the
  first track found for that artist, it would not be shown, even if marked as album artist in other tracks. This has
  been improved: the behaviour is no longer determined only by the first track found.


## [2.4.6] - 2023-06-23

### Fixed

- fixed some concurrency issues related to new multi-threaded library creation
- catch OutOfMemoryException when loading large embedded album art
- cache only downsized album art instead of full-sized album art to save memory
- try to avoid an ANR when writing remaining logging during shutdown
- minor library update


## [2.4.5] - 2023-06-18

### Fixed

- Google has fixed an [issue](https://issuetracker.google.com/issues/212779546) meanwhile that was preventing a
  feature in voice search to work properly. It was discovered there is an issue when using this feature in AudioWagon
  to search for artist or album by voice. This has been fixed.
  [#119](https://github.com/MoleMan1024/audiowagon/issues/119)
- When shuffle mode was on and the user selected a track inside an album or a file inside a directory, the playback
  queue would start in random order, but not with the selected track/file. This was confusing and has been fixed, a
  shuffled playback queue will now always start with the item the user selected.
  [#120](https://github.com/MoleMan1024/audiowagon/issues/120)
- The fix for issue [#83](https://github.com/MoleMan1024/audiowagon/issues/83) was only applied for all tracks on a
  single album but not for playlists nor when playing all tracks of an artist. This has been fixed.

### Changed

- search results within one media type will now be sorted alphabetically
- major internal refactoring was done to be able to experiment with different libraries more easily (*libusb* does not
  bring any improvement over accessing USB via Android Java code)
- *libaums* library source code was integrated into version control and modified in many places to make it thread-safe
- performance was improved during indexing of metadata (fine grained locks and multiple coroutines to build
  the media library are now used)
- creating a shuffled playback queue of tracks is now a bit faster
  [#121](https://github.com/MoleMan1024/audiowagon/discussions/121)
- improvements to exception handling and some error messages


## [2.3.10] - 2023-05-21

### Fixed

- when converting audio files from one file type to another (e.g. from .m4a to .mp3) and using embedded album art the
  database could still point to the old filename. In such cases no album art was shown, a database deletion was needed
  to recover. This has been improved: modification of album art in existing audio files or changes of file extensions
  will now correctly update the album art location in the database
  [#118](https://github.com/MoleMan1024/audiowagon/issues/118)

### Changed

- ignore built-in USB devices found in GMC Sierra trucks
- updated some library dependencies to latest version


## [2.3.8] - 2023-03-26

### Changed

- indexing speed was a bit slow when having many directories on the USB drive without any changes compared to last time
  the drive was indexed. The indexing speed has been improved by removing some not-necessary database operations
  [#112](https://github.com/MoleMan1024/audiowagon/issues/112)
- updated some Google library dependencies to latest version

### Added

- translation for Japanese


## [2.3.7] - 2023-02-28

### Fixed

- when writing log files to the USB drive in rare cases the filesystem got corrupted. This has been fixed
  [#105](https://github.com/MoleMan1024/audiowagon/issues/105)
- the pseudo artist "Various artists" used for compilation albums could show up multiple times in multiple cases when
  users would tag such files as "Various Artists" (note the capital letter 'A' in 'Artists' versus lowercase 'artists').
  Now any casing of "Various artists" in metadata will be used for the pseudo compilation artist. Also this pseudo
  artist will now be localized in the GUI (e.g. "Verschiedene Künstler" in German). To make full use of this fix you
  will need to re-index your database [#110](https://github.com/MoleMan1024/audiowagon/issues/110)

### Changed

- the musical note icon for tracks did not look very nice in the Volvo GUI, it was replaced with a microphone icon
- the number of items found during indexing on the GUI did not include files/directories, only audiofiles with metadata
  were shown. Now files/directories will be counted as well, resulting in a higher total number. During indexing you
  will first see the items increasing quickly based on the directories, and later a bit more slowly when the actual
  metadata in the audio files is read


## [2.3.6] - 2023-01-27

### Fixed

- ensure log lines are written in correct order to log file
- try to avoid `MissingNotifChannelException` during startup

### Changed

- extraction of metadata is now faster [#103](https://github.com/MoleMan1024/audiowagon/issues/103)
- hidden resource fork files created by Apple OS X are now ignored when indexing
  [#107](https://github.com/MoleMan1024/audiowagon/issues/107)
- avoid spamming logfile with stacktraces when there are lots of mismatches between media library and persisted playback
  queue
- ignore another built-in USB device found in some car using AAOS
- the indexing notification visible to the user is now updated earlier to indicate more clearly that the media library
  indexing is actually in progress (from 0 to 100 it is now updated every 20 files. Above 100 files it is unchanged
  and is updated every 100 files)
- increased log file write frequency

### Added

- ReplayGain tags in .opus files are now taken into account
  [#99](https://github.com/MoleMan1024/audiowagon/issues/99)


## [2.3.1] - 2022-11-26

### Fixed

- a regression was fixed where album art was no longer displayed
  [#92](https://github.com/MoleMan1024/audiowagon/issues/92)
- the eject function was not properly releasing all resources, this has been fixed

### Changed

- Apple Lossless Audio ("alac") is not supported by the Android platform. Previously, when trying to play back such files
  AudioWagon would show an "unknown error". Now the error message will say "Not supported" instead.
  [#94](https://github.com/MoleMan1024/audiowagon/issues/94)
- When the *AudioBrowserService* was started while the screen was still off the app would try to access the USB device,
  even though this situation seems to happen most often before going into suspend-to-RAM. The app will now no longer try
  to query and/or index USB devices while the screen is off.
- some micro-SD card readers show a "Unit attention ASCQ 40" error message when being plugged in. However it turns out
  this error can usually be ignored, those devices should now work with AudioWagon
- ignoring some more built-in USB devices found in Renault cars that use AAOS
- cancel some more coroutines that were possibly still running when going into shutdown/suspend


## [2.2.8] - 2022-11-05

### Changed

- trying to avoid issue with `MAX_RECOVERY_ATTEMPTS` when waking up from sleep
- trying to reduce memory usage of album art
- ignoring some more built-in USB devices found in General Motors and Renault cars that use AAOS


## [2.2.5] - 2022-09-26

### Changed

- increased the number in `MAX_RECOVERY_ATTEMPTS` in *libaums* back to 20 in case USB is busy and cannot do bulk
  transfers right away (seen a bit more often recently in *Crashlytics*).


## [2.2.4] - 2022-09-04

### Fixed

- selecting a track in an album inside artist view would erroneously play all songs from that artist instead of just the
  tracks on that album. This has been fixed.
  [#89](https://github.com/MoleMan1024/audiowagon/issues/89)


## [2.2.3] - 2022-08-28

### Fixed

- fixed a situation where AudioWagon became unresponsive when loading external cover art image files
  [#88](https://github.com/MoleMan1024/audiowagon/issues/88)


## [2.2.2] - 2022-08-23

### Fixed

- fixed an issue in *Crashlytics* logging
- added another USB device to the list of built-in devices to be ignored
- avoid a crash when closing a USB file does not finish within 10 seconds


## [2.2.1] - 2022-08-20

### Fixed

- album art was not shown for tracks that did not have an album name in metadata, this has been fixed
  [#79](https://github.com/MoleMan1024/audiowagon/issues/79)
- confined all access to *libaums* to a single thread because the library is not thread-safe. Hopefully this will fix a
  rare filesystem corruption [#80](https://github.com/MoleMan1024/audiowagon/issues/80)
- sometimes albums/artists with no associated tracks were kept in database, those are now properly cleaned up
  [#82](https://github.com/MoleMan1024/audiowagon/issues/82)
- when starting a new playback queue with shuffle mode turned on, the first track was never shuffled. This issue was
  related to the intended behaviour that the currently playing track shall not be modified when toggling shuffle mode.
  The issue been fixed [#83](https://github.com/MoleMan1024/audiowagon/issues/83)
- try to avoid hangs (ANRs) in different places by refactoring blocking coroutines
- try to avoid a `IllegalStateException` that could occur in Settings screen sometimes
- fixed an issue during quick service startup/shutdown where coroutines were not cancelled properly during
  restoring of persistent state
- fixed multiple issues when writing log files to USB drive
- fixed some problems in `LogActivity`
- handled a rare `IndexOutOfBoundsException` when setting up filesystem partitions
- made sure that stack traces arrive in *Crashlytics* logs fully

### Changed

- when persisting the playback queue, only upcoming tracks were stored. This has been changed to persist the complete
  playback queue instead [#78](https://github.com/MoleMan1024/audiowagon/issues/78)
- when a user cancelled the permission popup, or if the permission request was denied automatically, you had to re-plug
  the USB drive to make the permission pop-up appear again. Now the permission popup will be triggered again when
  entering *AudioWagon* settings (in case the permission was not yet given)
  [#76](https://github.com/MoleMan1024/audiowagon/issues/76)
- the eject icon could be tapped accidentally while driving because it was nearby the skip backwards icon. To avoid
  accidental presses the eject icon has been moved into the extra icon drawer next to shuffle and repeat icons. A
  seek-backwards icon was added in its original place which is less annoying if tapped accidentally
  [#74](https://github.com/MoleMan1024/audiowagon/issues/74)
- for *Crashlytics* exception catching we now log the maximum amount of log lines possible (64 kB) instead of just the
  last 100 lines (because it was confusing during analysis of logs)
- *libaums* and other library version updates
- update of icons
- internal refactoring (mainly in coroutines)

### Added

- translation for Italian


## [2.0.1] - 2022-07-03

### Fixed

- avoid a RuntimeException during service initialization in certain cases


## [2.0.0] - 2022-06-23

This release contains some major new features. When upgrading you will be required to re-index metadata from your USB
drives (if you were using that feature) because the database structure has changed significantly. If you find any
issues, please [contact me](https://moleman1024.github.io/audiowagon/index.html#how-do-i-report-an-issue).

### Fixed

- audio files in .m3u playlists with relative path markers could not be played back. This has been fixed.
  [#65](https://github.com/MoleMan1024/audiowagon/issues/65)
- groups of items in large media libraries are now shown more quickly due to database redesign
  [#38](https://github.com/MoleMan1024/audiowagon/issues/38)

### Added

- album art is now being shown for all tracks and albums when browsing
- a setting was added to show album art in a larger grid view. This setting is enabled by default. Changing this setting
  will only have an effect on next infotainment start or when restarting system app "Media Center"
  [#57](https://github.com/MoleMan1024/audiowagon/issues/57)
- the file view now supports playing back a whole directory hierarchy [#50](https://github.com/MoleMan1024/audiowagon/issues/50).
  Also you can now search for files and directories [#34](https://github.com/MoleMan1024/audiowagon/issues/34). To use
  these features metadata indexing must be turned on in the settings screen (using any setting other than "off")
- articles (e.g. "The", "A") and some other symbols are now ignored when sorting media items. For example an entry like
  "The Beatles" will no longer appear near other artists starting with letter "T" but will now appear near "B".
  [#60](https://github.com/MoleMan1024/audiowagon/issues/60)
- disc numbers in metadata are now being taken into account when sorting by track number.
  [#62](https://github.com/MoleMan1024/audiowagon/issues/62)


## [1.5.6] - 2022-04-02

### Added

- some users that have gotten a new Polestar / Volvo car software version 2.0 with Android Automotive OS 11 in workshops
  have reported that USB drives are no longer detected. This is required for AudioWagon to work at all. I would like to
  investigate this issue before the update is rolled out via OTA to many users mid of April. To investigate, I added a
  possiblity to view the log file on screen via AudioWagon settings and tapping on the "version" field. It would be very
  helpful if someone that has this issue could provide me some photos of the contents of this log window before
  and after they plug in their USB drive, maybe there is an error message in there that will help me fix this
  [#68](https://github.com/MoleMan1024/audiowagon/issues/68)


## [1.5.0] - 2022-02-26

### Fixed

- when AudioWagon was set to "Read metadata manually", and a file was deleted from USB drive, and the metadata was not
  updated by the user, and the track for that missing file was requested to be played, then the playback would just
  hang. This is improved now to show an error popup for the missing file and the playback queue will continue to the
  next track (if still available on USB drive)

### Changed

- prepare for AAOS 12 where toasts are no longer allowed for non-system apps. Replaced all toast messages with error
  states in PlaybackStateCompat which have the same visual effect

### Added

- Playlist files (.m3u, .pls and .xspf) that reference other files on the USB drive can now be played back from the
  file view. [#51](https://github.com/MoleMan1024/audiowagon/issues/51)


## [1.4.0] - 2022-02-12

### Fixed

- robustness improvements

### Added

- added Polish translation

### Changed

- updated Dutch translation
- modified skip backwards threshold added in version 1.3.0 to 10 seconds (down from 20 seconds)
- logging improvements


## [1.3.0] - 2022-01-12

### Fixed

- robustness improvements

### Changed

- show an error message to the user when audio effects (equalizer, ReplayGain) can not be used

### Added

- added Russian translation
- the behaviour when skipping backwards has changed: when the currently playing track has played less than 20 seconds,
  the skip previous buttons will go to the previous track in the playback queue. However when the currently playing
  track has already played more than 20 seconds, the previous buttons will now restart the track. This is more in-line
  with the behaviour in other media software [#45](https://github.com/MoleMan1024/audiowagon/issues/45)
- the app has been extended to support album art in directories. The highest priority is the album art embedded in the
  audio file. If no such album art is found, the app will now look also for .jpg or .png in the same directory as the
  audio file with usual filenames (e.g. "front.jpg", "folder.png", "cover.jpg", "index.jpg", "albumart.jpg", etc.)
  [#36](https://github.com/MoleMan1024/audiowagon/issues/36)


## [1.2.5] - 2021-12-25

### Fixed

- robustness improvements

### Changed

- show an error message to the user when a file can not be played back
- send last 100 log file lines to Crashlytics when errors/crashes occur to be able to analyze the cause of the issue
- now that a web browser is available, added a link to the AudioWagon website from within the settings


## [1.2.2] - 2021-12-10

### Fixed

- finally fixed the RemoteServiceException I was seeing. This was related to the media hardware button behaviour
  depending on timing. You should now be able to still restart the most recent media session using the play/pause
  next/previous hardware buttons after leaving AudioWagon [#56](https://github.com/MoleMan1024/audiowagon/issues/56)
- the app will now store a bit less data on disk when playing back a random selection of tracks
- fixed "play music" speech command when player was stopped
- improved service lifecycle handling


## [1.2.0] - 2021-12-04

### Changed

- extended the option "Read metadata" with a "manual" setting. This allows you to index metadata of audio files on the
  connected USB drive at your request only. The
  [FAQ was updated](https://moleman1024.github.io/audiowagon/faq.html#what-happens-during-indexing) with a section
  explaining the behaviour. [#21](https://github.com/MoleMan1024/audiowagon/issues/21)

### Added

- added a setting to select the behaviour of the app when listening to audio and a route guidance voice prompt needs to
  be played. You can now select to either pause the audio (more suitable for audiobooks) or continue playing the audio
  at a lower volume (more suitable for music). [#24](https://github.com/MoleMan1024/audiowagon/issues/24)
- I still see RemoteServiceExceptions in the Google Play Console but no user has reported any issue to me. That's why
  I added *Google Firebase Crashlytics* to the app now to see where exactly these crashes are coming from. This
  Crashlytics library will send more detailed error/crash information to me via internet. This setting is disabled by
  default for data privacy reasons, please see the
  [privacy policy](https://github.com/MoleMan1024/audiowagon/blob/master/PRIVACY_POLICY.md). You will also need to
  re-accept the legal disclaimer.


## [1.1.4] - 2021-11-29

### Fixed

- fixed an issue in 1.1.3 where number of characters for directory groups in browse view was not calculated
  [#54](https://github.com/MoleMan1024/audiowagon/issues/54)


## [1.1.3] - 2021-11-27

### Fixed

- avoid to extract ReplayGain from a track when such a track does not exist
- AudioWagon will store the last playback position when you exit the app. However after reaching the end of a playback
  queue and exiting the app, AudioWagon would still restore a previous playback state from somewhere earlier in the
  playback queue which was confusing. Now, when the playback queue ends (without repeat mode), the stored playback
  position will be cleared instead.

### Changed

- create album art bitmaps based on the root hint size provided by MediaBrowser clients which should slightly improve
  memory consumption
- calculate the possible number of characters based on screen resolution to be able to use more available screen space
  for artist/album/track group names in the browse view
- try once more to avoid the elusive RemoteServiceExceptions
- improve robustness slightly when suspending to RAM
- internal refactoring
- added testcases


## [1.1.0] - 2021-11-16

### Fixed

- A situation could occur where indexing was started multiple times in parallel which would mess up the internal
  database. This is avoided now. [#46](https://github.com/MoleMan1024/audiowagon/issues/46),
  [#49](https://github.com/MoleMan1024/audiowagon/issues/49)
- Depending on timing of certain events sometimes two USB permission popups could appear right after each other e.g.
  after entering the car. Now only one popup should be displayed. Related to [#46](https://github.com/MoleMan1024/audiowagon/issues/46)
- Try to avoid an exception which occurs after updating the app to a higher version
- Avoid that a selected track from the tracks browse view is duplicated in the resulting playback queue
- robustness improvements

### Changed

- The app will now interpret the *album artist* field in the metadata of your music files. To make use of this feature
  when updating from 1.0.1 or lower you will need to delete your database via the AudioWagon settings to force the app
  to re-create the database. [#22](https://github.com/MoleMan1024/audiowagon/issues/22) and
  [#1](https://github.com/MoleMan1024/audiowagon/issues/1).
  The behaviour has been documented in a
  [FAQ section](https://moleman1024.github.io/audiowagon/faq.html#why-do-my-compilation-albums-show-up-as-separate-albums).


## [1.0.1] - 2021-11-08 (beta phase ended)

### Fixed

- quotation marks in GUI texts were not showing up, this has been fixed

### Added

- voice commands such as "play &lt;artist name | album name | track name&gt;" should now work
  [#43](https://github.com/MoleMan1024/audiowagon/issues/43)
- added a GUI toast popup to show the search query after a voice input by the user. This is done to make it clear to the
  user what is being searched for so that they can adjust their voice input in case the search fails


## [0.6.4] - 2021-11-05

### Fixed

- fix regression where log files were created without content [#42](https://github.com/MoleMan1024/audiowagon/issues/42)
- fix regression where sorting order of items inside track/album/artist groups was wrong
  [#44](https://github.com/MoleMan1024/audiowagon/issues/44)


## [0.6.2] - 2021-11-02

### Fixed

- fix an issue where app settings screen could not be opened when clearing all app-specific data or after re-installing
  the app [#41](https://github.com/MoleMan1024/audiowagon/issues/41)
- improved latency of some database lookups
- improve robustness


## [0.6.1] - 2021-11-01

### Fixed

- fix filesystem corruption due to issues in *libaums* library. This will also remove the limitation
  of having max 128 files per directory. The issue was a combination of ignoring data when reading long FAT cluster
  chains as well as unintended writing to USB filesystem while reading from it in multiple threads
  [#37](https://github.com/MoleMan1024/audiowagon/issues/37)


## [0.6.0] - 2021-10-26

### Fixed

- avoid a crash when a file can not be found for some reason
- fix issue where files could be played without agreeing to legal disclaimer

### Changed

- remove not needed wake lock permission

### Added

- Danish and French translation
- you can now delete internal databases of USB drives that were previously indexed via the settings screen to save space.
  The database of the currently in-use USB drive can not be deleted while it is in use, you have to eject the drive first.
  Previously it was only possible to delete the data of the whole app including all settings via the Android system
  settings [#2](https://github.com/MoleMan1024/audiowagon/issues/2)
- ReplayGain on track level is now supported to normalize volume across many different tracks
  [#9](https://github.com/MoleMan1024/audiowagon/issues/9)
- some more icons in settings screen


## [0.5.0] - 2021-10-18

### Fixed

- prevent that multiple audio file storage location objects are used, this could maybe help with filesystem corruption
  [#33](https://github.com/MoleMan1024/audiowagon/issues/33)
- avoid some errors with empty content hierarchy IDs
- abort indexing when USB file reading methods throw unrecoverable I/O exceptions

### Changed

- prevent that USB indexing process is stopped when switching to another media app. This will allow a user to
  e.g. listen to the radio or use other media apps while an USB drive is being indexed. Previously the indexing process
  could be interrupted in that case [#19](https://github.com/MoleMan1024/audiowagon/issues/19)
- enabling shuffle mode will now move the current item in the playback queue to the beginning of the playback queue
- latency decreased when selecting a single track for playback

### Added

- added directory/file browsing [#26](https://github.com/MoleMan1024/audiowagon/issues/26)
- added a switch in the settings to turn off reading of audio file metadata. In case users want to use only the
  directory/file browsing, they can turn this off so the USB drive is not indexed anymore
- added an eject button to settings screen so users have the option to eject the USB drive to abort indexing
- added icons for main categories in browse view


## [0.4.0] - 2021-10-04

### Fixed

- for cars like Polestar 2 version P2127 or higher when entering the car the audio will resume playing if it was playing
  previously. This should now also work with AudioWagon [#15](https://github.com/MoleMan1024/audiowagon/issues/15)
- the USB filesystem could get corrupted when the car was exited (and thus Android suspended) when indexing was ongoing.
  Some improvements were made that should avoid this issue [#27](https://github.com/MoleMan1024/audiowagon/issues/27)
- with large media libraries the browse view could sometimes freeze for multiple seconds due to too large database
  queries. This is now avoided [#28](https://github.com/MoleMan1024/audiowagon/issues/28)
- in case of many files missing metadata the number of entries in the "unknown artist/album" category could grow too big
  to be properly displayed. This is now avoided by using groups in the browse view
  [#29](https://github.com/MoleMan1024/audiowagon/issues/29)
- sanitize year strings in metadata formatted like timestamps
- stability improvements (more exception handling)

### Changed

- some users did not understand that selecting a single track e.g. in an album would only play that track and nothing
  else, they expected the next/previous track buttons to work also. This design was changed, selecting a track inside an
  artist/album view will now play all items in the shown list starting from the selected item
  [#18](https://github.com/MoleMan1024/audiowagon/issues/18)
- initial indexing speed is slightly improved


## [0.3.4] - 2021-09-18

### Added

- added translations for Dutch, Swedish, Norwegian

### Changed

- add uncaught exception handler that will flush to USB if possible to have more information before app exits
- do not store USB status in settings persistently
- try to improve recovery of app after issues with USB drive
  [#5](https://github.com/MoleMan1024/audiowagon/issues/5)
- added more exception handling
- more logging, removed obfuscation from stack traces

### Fixed

- store USB status in settings as ID instead of as localized strings to avoid English strings in other languages being
  shown
- improved USB status update in settings on development phone with USBDummyActivity
- fix app failure with large music libraries: previously more than approx. 500 tracks could cause issues, now it should
  work fine with up to approx. 150000 tracks [#6](https://github.com/MoleMan1024/audiowagon/issues/6)
- notify user if USB drive is full but logging to file on USB drive is enabled
  [#7](https://github.com/MoleMan1024/audiowagon/issues/7)
- fix issues with files that contain a percent sign in the filename
  [#11](https://github.com/MoleMan1024/audiowagon/issues/11)
- avoid issue where USB filesystem is corrupted when more than 128 files are put in root directory. Users are notified
  about this and asked to add subdirectories on their USB drive. A real fix will require a change in libaums.
  [#13](https://github.com/MoleMan1024/audiowagon/issues/13)
- avoid issue where indexing slows down on files which are not supported. Previously the app tried to index .wma files
  as well which caused a 5 second delay on each of those files as .wma is not supported. Such files are now ignored
  [#14](https://github.com/MoleMan1024/audiowagon/issues/14)


## [0.2.5] - 2021-09-03

### Added

- initial release for beta test

