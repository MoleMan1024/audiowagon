# Changelog

All notable changes to this project will be documented in this file.

Listed here are only software version that have been made public. A jump in the version number (e.g. from 0.2.5 to 
0.3.4) without any inbetween version means that multiple internal releases were made that were not made available to the 
public. This is required for in-car-testing due to the way the Google Play Store works.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).


## [0.3.5] - 2021-09-18

### Added

- added translations for Dutch, Swedish, Norwegian

### Changed

- the project is now *public open beta* (i.e. no invites necessary anymore)
- add uncaught exception handler that will flush to USB if possible to have more information before app exits
- do not store USB status in settings persistently
- try to improve recovery of app after issues with USB drive
  [#6](https://github.com/MoleMan1024/audiowagon_beta/issues/6)
- added more exception handling
- more logging, removed obfuscation from stack traces

### Fixed

- store USB status in settings as ID instead of as localized strings to avoid English strings in other languages being
  shown
- improved USB status update in settings on development phone with USBDummyActivity
- fix app failure with large music libraries: previously more than approx. 500 tracks could cause issues, now it should
  work fine with up to approx. 150000 tracks [#7](https://github.com/MoleMan1024/audiowagon_beta/issues/7)
- notify user if USB drive is full but logging to file on USB drive is enabled
  [#8](https://github.com/MoleMan1024/audiowagon_beta/issues/8)
- fix issues with files that contain a percent sign in the filename 
  [#12](https://github.com/MoleMan1024/audiowagon_beta/issues/12)
- avoid issue where USB filesystem is corrupted when more than 128 files are put in root directory. Users are notified
  about this and asked to add subdirectories on their USB drive. A real fix will require a change in libaums. 
  [#14](https://github.com/MoleMan1024/audiowagon_beta/issues/14)
- avoid issue where indexing slows down on files which are not supported. Previously the app tried to index .wma files
  as well which caused a 5 second delay on each of those files as .wma is not supported. Such files are now ignored
  [#15](https://github.com/MoleMan1024/audiowagon_beta/issues/15)
- for cars like Polestar 2 version P2127 or higher when entering the car the audio will resume playing if it was playing
  previously. This should now also work with AudioWagon [#16](https://github.com/MoleMan1024/audiowagon_beta/issues/16)


## [0.2.5] - 2021-09-03

### Added

- initial release for beta test

