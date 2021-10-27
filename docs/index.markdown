---
layout: home
title: Home
nav_order: 1
---

# AudioWagon documentation

This project is currently in **OPEN BETA**.

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
- currently playing track shows embedded **album art**
- **compilation albums** are indicated (if marked as such in metadata)
- **persistency**: remembers the last used playlist and playback position
- **search**: search for entries using the on-screen keyboard
- **equalizer** with multiple presets
- [**ReplayGain**](https://en.wikipedia.org/wiki/ReplayGain) for volume normalization of tracks
- [**gapless playback**](https://en.wikipedia.org/wiki/Gapless_playback) for FLAC files
- partial support for **voice input**

## Release notes

Please see Github for a [list of changes for each version of the app](https://github.com/MoleMan1024/audiowagon/blob/master/CHANGELOG.md).

## Limitations

- You can only put up to 128 *files* into any directory. You can use as many *directories* as you want though. I advise a
  directory structure like this: `D:/Music/<Artist>/<Album>/<Song1..n>.mp3`. If you use more than 128 files in a
  directory then AudioWagon version 0.2.5 will corrupt your USB drive and you will lose your files on it. Version 0.3.4
  and higher will prevent the data loss, however the limitation will still exist until the underlying issue can be fixed
  (directories with more than 128 files in them are ignored).
- The app can *not* play back **.wma** files

## How to use

- Install the app via the [Google Play Store](https://play.google.com/store/apps/details?id=de.moleman1024.audiowagon)
  (BETA TEST: you need an invite to see the app, [see below](#how-to-contribute))

- Format a USB flash drive using **FAT32** filesystem

![format](/img/format.jpg)

- Insert the USB flash drive into the car's USB port that is connected to the Android infotainment system (usually
  **marked with a white outline**). Depending on your car model, you might need an adapter for USB-C.

![USB port](/img/port.jpg)
![USB port plugged](/img/insert_usb.jpg)

- Wait a few seconds, then tap OK to **allow access** to the USB drive contents in the popup window

![Permission popup](/img/allow_access.jpg)

- **Wait for indexing** to complete or use the file/directory browser

![Indexing](/img/indexing.jpg)

- Enjoy 🤩


## Frequently asked questions

See [the FAQ section]({{ site.baseurl }}{% link faq.markdown %}).

## How do I report an issue?

I advise all beta testers to keep logging to USB *activated* in the settings at all times. If possible provide all of
the following info to me at my email (or you can use [GitHub](https://github.com/MoleMan1024/audiowagon/issues)):

- What happened? Describe ALL steps that you did EXACTLY. Please mention even minor details (e.g. did you leave/enter
  the car before you saw the issue occur?)
- What did you expect to happen?
- At what date/time did the issue happen? (so I can align with the timestamps in the log files)
- Which version of AudioWagon were you using? (version number can be found in the settings screen)
- Which version is your car's software? (e.g. P2124 on a Polestar2)
- Log files are being created continously on your USB drive (if this is enabled in the settings). Please attach these
logfiles from around the date/time when this happened (also provide previous logfile). They have filenames like
`aw_logs_<num>/audiowagon_<date_and_time>.log`.

## How to contribute?

### Open beta

If you provide me with the **e-mail address that you use in your car for your Google™ account** I can invite you to the
beta version. If you are interested, please contact me.

### Native speakers

I am currently looking for **volunteer native speakers to translate** the GUI texts in the app to all languages
(except German, English, Dutch, Swedish, Norwegian, Danish, French). You can get the
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

