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
- **browse** by track, artist, or album
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
- partial support for **voice input**

## Release notes

Please see Github for a [list of changes for each version of the app](https://github.com/MoleMan1024/audiowagon/blob/master/CHANGELOG.md).

## Limitations

- You can only put up to 128 *files* into any directory. You can use as many *directories* as you want though. I advise a
  directory structure like this: `D:/Music/<Artist>/<Album>/<Song1..n>.mp3`. If you don't follow this rule AudioWagon
  version 0.2.5 will corrupt your USB drive and you will lose your files on it. The next version will prevent the data loss,
  however the limitation will still exist until the underlying issue can be fixed (in upcoming version directories with
  more than 128 files in them will be ignored).
- In AudioWagon version 0.2.5 you can only use about a couple hundred to a few thousand tracks maximum, above that you
  might see the app hang when looking at browsing tracks. This has been improved in version 0.3.4.
- The app can *not* play back .wma files

## How to use

- Format a USB flash drive using **FAT32** filesystem

![format](/img/format.jpg)

- Insert the USB flash drive into the car's USB port that is connected to the Android infotainment system (usually
  **marked with a white outline**). Depending on your car model, you might need an adapter for USB-C.

![format](/img/port.jpg)
![format](/img/insert_usb.jpg)

- Wait a few seconds, then tap OK to **allow access** to the USB drive contents in the popup window

![format](/img/allow_access.jpg)

- **Wait for indexing** to complete

![format](/img/indexing.jpg)

- Enjoy 🤩


## Frequently asked questions

### Why is my USB drive not recognized?

The app only supports USB flash drives formatted using **FAT32 filesystem**. There are several tools available that you
can download from the internet to format a drive using this filesystem. On Windows I recommend
[Rufus](https://rufus.ie/en/).

Also make sure to plug the USB flash drive into the port in your car that is marked (usually in the front of the car,
with a white outline). The other USB ports are for charging your phone only.

### Why does the permission popup appear each time? The "always open AudioWagon &hellip;" checkbox does not do anything!

Sorry, I don't know why that happens. It works fine on the mobile phone that I use for development, however the car
maker probably put some extra security in the car that will always trigger this permission dialog popup. I would need
help from the car maker or Google™ to improve this.

### Why does it say "Loading content &hellip;" and nothing happens?

This usually indicates some error has happened somewhere. Please switch to a different audio app (e.g. radio or
Bluetooth™) and then back to AudioWagon, that should fix it.

If not, go to Settings &#8594; Apps and notifications &#8594; Show all
apps &#8594; Show system &#8594; Media Center and tap "Force Stop", then try the app again.

### Can I use multiple USB drives?

Yes, in most cases.

The only thing that will not work is using multiple USB drives of the same model from the same
manufacturer that have the same volume label. This will mess up the internal database. Please assign unique names when
you format or use USB drives by different manufacturers/different models.

If you meant "can I connect multiple USB drives at the same time using a USB hub?", then no, the app does not support
that.

### What is the eject button for?

The eject button will make sure that the USB drive is not in use when you unplug it. Press it while the infotainment
system is still on and wait for the popup to appear, then you can safely unplug your USB drive.

Alternatively, turn off your infotainment system first and unplug your USB drive afterwards.

If you do not follow this procedure, and unplug the USB drive while it is still in use, the app could crash or the data
on your USB drive might be damaged.

### Does the app support voice input?

Partially. The following utterances work with Google Assistant (at least in English):

- "Play"
- "Pause"
- "Next track"
- "Previous track"
- "Skip ahead &lt;number&gt; seconds"
- "Skip backwards &lt;number&gt; seconds"
- "Volume up/down"

However playing a song/artist/album by name (e.g. "play artist &lt;artistname&gt; on AudioWagon") does *not* work (if
you find out why, please let me know).

### Does the app support video?

No.

### Does the app work on phones with Android Auto?

No.

[Android Auto](https://www.android.com/auto/) is an extension running on your mobile phone that connects to your car.
The AudioWagon app can only run in
[Android Automotive OS (AAOS)](https://developers.google.com/cars/design/automotive-os), which is an operating system
built into certain car models.

### How do I report an issue?

OPEN BETA:

I advise all beta testers to keep logging to USB *activated* in the settings at all times. If possible provide all of
the following info to me at my email (or you can use [GitHub](https://github.com/MoleMan1024/audiowagon/issues)):

- What happened? Describe all steps that you did exactly
- What did you expect to happen?
- At what date/time did the issue happen? (so I can align with the timestamps in the log files)
- Which version of AudioWagon were you using? (see Settings)
- Which version is your car's software? (e.g. P2124 on a Polestar2)
- Log files are being created continously on your USB drive (if this is enabled in the settings). Please attach these 
logfiles from around the date/time when this happened (also provide previous logfile). They have filenames like 
`aw_logs_<num>/audiowagon_<date_and_time>.log`.

### How to contribute?

#### Open beta

If you provide me with the **e-mail address that you use in your car for your Google account** I can invite you to the
beta version. If you are interested, please contact me.

#### Native speakers

I am currently looking for **volunteer native speakers to translate** the GUI texts in the app to all languages
(except German, English, Dutch, Swedish, Norwegian). You can get the 
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

