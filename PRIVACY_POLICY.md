# Privacy Policy

This document explains about personal and user-provided data that the AudioWagon app collects and how it is processed.

## Media data

The app's purpose is to provide access to audio files given by the user on a USB flash drive. This includes metadata
embedded in audio files e.g. *mp3 tags* regarding artist, album, track name or album art image files. The media data is
accesssible to other *system-installed apps* (e.g. *preinstalled* Android media related apps, Google Voice Assistant)
for the following purposes:

- playback of audio files
- showing audio file metadata to the user (e.g. showing artist, album, track name, play time, etc. on the screen)

Access is also allowed to the [Google Media Controller Test app](https://github.com/googlesamples/android-media-controller)
for testing purposes.

Album art is currently accessible to preinstalled apps as well as third party apps (but no third party app has any
reason to access it and I do not consider this harmful).


## Log files

The app has a *setting to collect log files*. These log files will be written to the attached USB drive. This
setting is *off by default*. The user of the app may voluntarily provide this log file to the developer for debugging
purposes, it is *not transferred automatically via internet*.

The log file contains the following personal data that could be used to identify a person:

- filenames and metadata of audio files on the USB drive
- volume name, brand and manufacturer of the USB drive


## Firebase Crashlytics

The app uses *Firebase Crashlytics*, a service provided by Google, to collect *anonymous crash and error reports* and
will transfer them to the developer via internet. This is done so the developer can discover and fix issues in the app.

The user of the app *needs to opt-in* to use this feature via the settings screen. This setting is *off by default*.

A detailed description about the data collected, how and where it is being processed can be found
[in Google's Firebase support documentation for Crashlytics](https://firebase.google.com/support/privacy?hl=en).

In addition to the crash/error message, the last 64 kB lines of the log file are transferred as well.


## Sharing usage & diagnostics data via Google

The Android Automotive system may collect some *information regarding the usage of the app* and will also send
*crash reports* to the developer via internet. This is a feature implemented by the Android system, not specifically
by the app. [Check here to learn more about this feature and how to turn it off](https://support.google.com/accounts/answer/6078260?hl=en).

