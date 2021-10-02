# Changelog

All notable changes to this project will be documented in this file.

Listed here are only software version that have been made public. A jump in the version number (e.g. from 0.2.5 to 
0.3.4) without any inbetween version means that multiple internal releases were made that were not made available to the 
public. This is required for in-car-testing due to the way the Google Play Store works.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

