---
layout: home
title: Home
nav_order: 1
---

# AudioWagon documentation

This app will **play audio files** from an attached **USB flash drive** in cars equipped with **Android™ Automotive OS**
(for example Polestar 2™, Volvo XC40 Recharge™, &hellip;)[^1].

![AudioWagon in car](/img/audiowagon.jpg)

## Features

- common audio file formats including **MP3**, **WAVE**, **FLAC**, **AAC**, **MIDI**(!) and
  [more](https://developer.android.com/guide/topics/media/media-formats).
- **browse** by track, artist, album, or file/directory
- media actions:
    - **play** / **pause**
    - **skip** forwards/backwards
    - **seek** forwards/backwards
    - **shuffle** and **repeat** mode
- tracks and albums show **album art**
- **compilation albums** are indicated (if marked as such in metadata)
- **persistency**: remembers the last used playback queue and playback position
- **search**: search for entries using the on-screen keyboard
- **equalizer** with multiple presets
- [**ReplayGain**](https://en.wikipedia.org/wiki/ReplayGain) for volume normalization of tracks
- [**gapless playback**](https://en.wikipedia.org/wiki/Gapless_playback) for FLAC files
- support for **voice input**
- **playlists** (**m3u**, **pls**, **xspf**) can be played back from file view

## Release notes

Please see Github for a [list of changes for each version of the app](https://github.com/MoleMan1024/audiowagon/blob/master/CHANGELOG.md).

## Limitations

- The app will *not* work with Polestar/Volvo OTA car software version 2.0 nor 2.1. You will need the car software
  version 2.2 or higher that includes the CarPlay Update (or 1.9 or lower) which re-enables the USB data connection.  
- The app can *not* play back **.wma** files. Also Android does not support files encoded with "Apple Lossless Audio
  (alac)" codec.
- The app does *not* work with music stored on *iPod*, *iPhone* nor *Android phone*, you need to use a *USB mass storage
  device* formatted as *FAT32* (for example a USB flash drive)
- One person has [reported incompatibility](https://github.com/MoleMan1024/audiowagon/issues/85) with a *SanDisk 2TB
  Extreme Portable SSD*
- *Some* people have reported issues when using the app with a **USB to micro SD card adapter** where no files could be
  played back and lots of error messages were shown. In such cases the adapter is probably not compatible with the
  library I use to read the filesystem, please try with a USB flash drive instead, I have not heard about problems with
  those. 

  *This problem applies only to certain adapters*, I also have reports where similar adapters were working normally.

## How to use

- Install the app via the [Google Play Store](https://play.google.com/store/apps/details?id=de.moleman1024.audiowagon)

- Format a USB flash drive using **FAT32** filesystem (in Windows you can use [Rufus](https://rufus.ie/en/) for example).

![format](/img/format.jpg)

- Insert the USB flash drive into the car's USB port that is connected to the Android infotainment system (usually
  **marked with a white outline**). Depending on your car model, you might need an adapter for USB-C.

![USB port](/img/port.jpg)
![USB port plugged](/img/insert_usb.jpg)

- Wait a few seconds, then tap OK to **allow access** to the USB drive contents in the popup window

![Permission popup](/img/allow_access.jpg)

- If you have installed the app for the first time, you have to do the following 3 extra steps: you get a warning that 
  you need to accept the legal disclaimer in the settings. Open the AudioWagon settings using the gear icon.

![Legal disclaimer not agreed](/img/legal_disclaimer_not_agreed.jpg)

- In the settings screen tap on "Show legal disclaimer"

![Settings](/img/settings.jpg)

- Read through the legal disclaimer and tap "Agree". Then return to the main screen using the arrow in top
  left corner.

![Legal disclaimer](/img/legal_disclaimer.jpg)

- **Wait for indexing** to complete or use the file/directory browser

![Indexing](/img/indexing.jpg)

- Enjoy 🤩


## Frequently asked questions

See [the FAQ section]({{ site.baseurl }}{% link faq.markdown %}).

## How do I report an issue?

If you encounter any issues, please let me know so I can fix them. If possible provide all of the following info to me
at my email (see below, or you can use [GitHub](https://github.com/MoleMan1024/audiowagon/issues)):

- What happened? Describe ALL steps that you did EXACTLY. Please mention even minor details (e.g. did you leave/enter
  the car before you saw the issue occur?). Also be precise: saying that something "does not work"/"is broken" is not
  helpful, rather describe what the behaviour of the app actually is.
- What did you expect to happen?
- At what date/time did the issue happen? (so I can align with the timestamps in the log files)
- Which *version of AudioWagon* were you using? (version number can be found in the settings screen)
- Which version is your car's software? (e.g. P2124 on a Polestar 2)
- *Log files* are being created continously on your USB drive (if this is enabled in the settings). Please attach these
  log files from around the date/time when the issue happened. If unsure about the date, just provide everything. If you
  can reproduce the issue easily, turn on logging in the settings, do whatever you need to trigger the issue, then turn
  logging off again, then the latest log file should contain the issue. The log files have filenames like
  `aw_logs_0/audiowagon_<date_and_time>.log`. These files are very important, without this I only have your description
  of the issue to work with.


## How to contribute?

### Native speakers

I am still looking for **volunteer native speakers to translate** the GUI texts in the app to all languages (already
translated: German, English, Dutch, Swedish, Norwegian, Danish, French, Russian, Polish, Italian). You can get the
[English strings to translate here](https://github.com/MoleMan1024/audiowagon/blob/master/automotive/src/main/res/values/strings.xml).


## Contact

If you have further questions, please send me an e-mail at [moleman1024dev@gmail.com](mailto:moleman1024dev@gmail.com)

## Licenses & Acknowledgements

This app uses content from the following other creators, all licensed under
[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0). Thank you so much for providing this content:

- the [libaums library](https://github.com/magnusja/libaums) created by Magnus Jahnen
- various libraries created by the [Android Open Source Project](https://source.android.com/)
- [Material design vector icons](https://fonts.google.com/icons) created by Google

Also many thanks to all beta testers and translators!

AudioWagon is licensed under [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html)

---

[^1]: Any product names, brands, and other trademarks referred to are the property of their respective trademark
      holders. *AudioWagon* is not affiliated with, endorsed by, or sponsored by any trademark holders mentioned on this
      website.

