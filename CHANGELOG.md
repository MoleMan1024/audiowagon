# Changelog

All notable changes to this project will be documented in this file.

Listed here are only software version that have been made public. A jump in the version number (e.g. from 0.2.5 to
0.3.4) without any inbetween version means that multiple internal releases were made that were not made available to the
public. This is required for in-car-testing due to the way the Google Play Store works.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).


## [Unreleased]

## [1.2.0] - 2021-12-04

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

### Changed

- extended the option "Read metadata" with a "manual" setting. This allows you to index metadata of audio files on the
  connected USB drive at your request only. The 
  [FAQ was updated](https://moleman1024.github.io/audiowagon/faq.html#what-happens-during-indexing) with a section 
  explaining the behaviour. [#21](https://github.com/MoleMan1024/audiowagon/issues/21)


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


## [0.6.1] - 2021-11-0

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

