# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).


## [Unreleased]

### Added

- added translations for Dutch, Swedish, Norwegian

### Changed

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


## [0.2.5] - 2021-09-03

### Added

- initial release for beta test

