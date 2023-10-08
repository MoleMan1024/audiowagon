---
layout: home
title: Frequently Asked Questions
nav_order: 2
---

# Frequently asked questions
{: .no_toc }

- Table Of Contents
{:toc}

## Using the app

### Why does the permission popup appear each time? The "always open AudioWagon &hellip;" checkbox does not do anything!

![Permission popup](/img/allow_access.jpg)

Sorry, I cannot change that. It works fine on the mobile phone that I use for development, however *Volvo/Polestar*
probably put some extra security in the car that will always trigger this permission dialog popup and they likely would
[need to add some code from
Google](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android11-platform-release/car-usb-handler/)
to improve this. This would allow the user to select *AudioWagon* to be the default app for USB storage devices (it
should not interfere with wired CarPlay). I would provide help on how to integrate this code if *Volvo/Polestar* decides
to use it.

### Why is there a popup about "USB device is not supported" when the USB device works fine?

![Not supported popup](/img/not_supported.jpg)

This popup is triggered by *Volvo/Polestar* and is invalid, it always comes and can be ignored when using *AudioWagon*.
You can temporarily get rid of it by doing the steps I mentioned in [issue #73](https://github.com/MoleMan1024/audiowagon/issues/73#issuecomment-1172879863) 
but it will come back after a headunit restart. Please file a bug report at *Volvo/Polestar* to get this fixed.

As far as I know, this issue does not appear when using the app on *Renault* and *GMC* cars.

### Why is my USB drive not recognized?

If you are driving a Polestar or Volvo, make sure you use car software version 2.2 or higher, [certain versions do not
allow access to the USB data port]({{ site.baseurl }}{% link index.markdown %}#limitations).

![FAT32 error](/img/fat32_error.jpg)

The app only supports USB flash drives formatted using **FAT32 filesystem**. Do *not* use *exFAT*, do *not* use any
other filesystem, it will *not* work. There are several tools available that you can download from the internet to
format a drive using this filesystem. On Windows I recommend [Rufus](https://rufus.ie/en/).

Also make sure to plug the USB flash drive into the port in your car that is marked (usually in the front of the car,
with a white outline). The other USB ports are for *charging your phone only*.

![USB port](/img/port.jpg)

If you still have problems, try to to [reboot the center
display](https://www.polestar.com/ie/polestar-support/how-to-videos/polestar-2/how-to-reboot-the-polestar-2-centre-display/).


### Why does it take so long when starting the app?

When starting up the app with a USB flash drive connected, *indexing* will happen:

![Indexing](/img/indexing.jpg)

*Indexing* means that the app will walk through all directories and extract the *metadata* from all files (i.e. artist,
album, title, year etc.). In version 1.1.5 and higher you can choose the desired behaviour of this process in
the settings screen using the option "Read metadata":

- **When USB drive connected** *(default setting)*: When connecting a USB drive for the first time the app walks through
  all the directories on the USB drive and extracts metadata from all audio files. This information is written into a
  database stored in the car. This process will take some time (a couple of minutes for 10000 files).

  When connecting the same USB drive again, the app will again go through all directories and all files but will check
  the file modification date. If the file has been modified since it was last indexed, it will re-read the metadata of
  that file. If not, it will take the information from the database. Thus this process should be much quicker than the
  first time. However it can still take multiple seconds with large music libraries.

  This setting is **recommended when you don't have a lot of audio files on your USB drive**.

- **Manually**: Similar to above, but you have to start the indexing process by hand. You should do this whenever you
  change files on the USB drive. You can start the indexing using the option "Read metadata now" in the settings screen.

  This setting is **recommended when you have many audio files on your USB drive but you do not change them very
  often**.

- **File paths only**: With this setting only the file paths on the USB drive will be indexed, no other metadata. This
  setting will allow you to use "Play all" in any directory.

  This setting is **recommended when you don't care about metadata** but still would like to play all files in a directory
  hierarchy.

- **Off**: With this setting metadata will *not* be extracted from audio files. You will not be able to navigate nor
  search by track, album, artist. You can only browse by directory or file and you can only play files found in a single
  directory.

  This setting is **recommended when many audio files on your USB drive do not have any/correct metadata** or you never
  want to wait for indexing.


### Why do my compilation albums show up as separate albums?

In version 1.1.0 and higher the behaviour is the following for such "special" cases:

#### Compilation albums

A *compilation* is an album that contains various artists. To mark an album as a compilation it needs a special tag in
the metadata. Different tools have different ways to achieve this, for example:

- in [mp3tag](https://www.mp3tag.de/en/) open the *extended tags* of the files and add a field COMPILATION with value 1.
  ![compilation in mp3tag](/img/compilation_mp3tag.jpg)
- in [tagscanner](https://www.xdlab.ru/en/) tick the checkmark "Part of Compilation"
  ![compilation in tagscanner](/img/compilation_tagscanner.jpg)
- in [MusicBee](https://www.getmusicbee.com/) edit the track, go to *settings* tab and tick *iTunes compilation*
  ![compilation in MusicBee](/img/compilation_musicbee.jpg)

Alternatively you can also put "Various artists" in the *album artist* tag, this will have the same effect as the
compilation field above.

Afterwards the compilation album will show up in the album view with a pseudo artist "Various artists"

![compilation](/img/compilation.jpg)

Inside this album each track will show the respective artist:

![compilation tracks](/img/compilation_tracks.jpg)

#### Album artists

The *album artist* tag in the metadata of each file is preferred over the *artist* tag.

As an example consider this album containing some song covers for the artist "Mirah":

![album artist list](/img/album_artist_list.jpg)

It is *not* tagged as a compilation, the *album artist* is "Mirah" and the *artist* is the one that covered the song.

The AudioWagon app will react the following way based on feature request
[#22](https://github.com/MoleMan1024/audiowagon/issues/22):
- In the **artist view** the *album artist* will be shown
- In the **album view** the *album artist* will be shown

  ![album artist](/img/album_artist.jpg)
- In the **track view** the original *artist* will be shown

  ![album artist tracks](/img/album_artist_tracks.jpg)
- In the **playback view** the original *artist* will be shown
- For **searching** you must use the *album artist*

#### Missing metadata

If the *album* tag is empty an entry "Unknown album" will be created to collect such tracks. If the *artist* is
available this album will be associated with the respective artist.

If the *artist* tag is empty an entry "Unknown artist" will be created to collect such tracks.

If the *title* tag is empty, the file name will be used instead.


### After adding more files to my USB drive the browse view looks strange

When you add more than 400 tracks/albums/artists they will be shown using *groups*, it looks like this:

![album groups](/img/album_groups.jpg)

This is necessary because Android cannot deal with lists which are extremely long. Also it will be annoying to
scroll through such long lists.

Each group contains 400 entries and is based on the names of the
tracks/albums/artists inside. Tapping on one entry will show those 400 tracks/albums/artists in a new list (in this
example all albums "One" til "Sounds of Summer", sorted alphabetically):

![album groups open](/img/album_group_open.jpg)


### My operating system tells me that my USB drive has problems. But all files are okay?

This can happen when the USB drive is not properly ejected. A secret marker will be set on the USB drive and this
warning will pop-up reminding you to always properly eject the USB drive. Use your operating system to repair the USB
drive. That should remove this warning message.

### Files on my USB drive were deleted!

This usually means the FAT32 filesystem was damaged somehow. I am trying my best to avoid this situation, if you
encounter this in a recent version of the app, please send a bug report. Here are some things you can try if this
happens to you:

- First of all do not store any important data on the USB drive other than audio files. Keep a copy of those files on
  your PC
- Update the app to latest version, there were some issues in earlier versions (especially before version 0.6.1) that
  could cause this corruption
- Try using a different USB drive (flash memory can wear out after some years)
- Try putting less data on the USB drive
- Make sure to always properly eject the USB drive before unplugging it. Do not assume that if the infotainment screen
  is off it is safe to unplug the USB drive. Like on your phone, even with the screen off the Android system might still
  be running.

You might be able to recover some of your data using e.g. Windows *chkdsk* tool. You should re-format the USB drive
afterwards to make sure it is in a clean state.


### Why does it say "Loading content &hellip;" and nothing happens?

This usually indicates some error has happened somewhere. Please switch to a different audio app (e.g. radio or
Bluetooth™) and then back to AudioWagon, that should fix it.

If not, go to General Android Settings &#8594; Apps and notifications &#8594; Show all
apps &#8594; Show system &#8594; Media Center and tap "Force Stop", then try the app again.


### What is the eject button for?

The eject button will make sure that the USB drive is not in use when you unplug it. Press it while the infotainment
system is still on and wait for the popup to appear, then you can safely unplug your USB drive.

If you do not follow this procedure, and unplug the USB drive while it is still in use, the app could crash or the data
on your USB drive might be damaged.


## Supported features

### Is there a limitation on the number of files the app can handle?

There is a theoretical limit of about 160000 tracks. However you might observe other issues before you reach that many
tracks (see above) and you will go mad due to the loading time 😉.

Also before you try to go there, ask yourself how many tracks you really need on your journey. 10000 tracks with an
average of 3 minutes each is already more than 20 days of music to listen to.

### Can I use multiple USB drives?

Yes, in most cases.

The only thing that will *not* work is using multiple USB drives of the same model from the same
manufacturer that have the same volume label. This will mess up the internal database. Please assign unique names when
you format or use USB drives by different manufacturers/different models.

If you meant "can I connect multiple USB drives at the same time using a USB hub?", then no, the app does not support
that.

### Does the app support voice input?

Yes, the following utterances work with Google Assistant (at least in English):

- "Play"
- "Pause"
- "Next track"
- "Previous track"
- "Skip ahead &lt;number&gt; seconds"
- "Skip backwards &lt;number&gt; seconds"
- "Volume up/down"
- "Play &lt;artist name &#x7c; album name &#x7c; track name&gt;"
- "Play track &lt;track name&gt;"
- "Play album &lt;album name&gt;"
- "Play artist &lt;artist name&gt;"
- "Play some music"

However it has some strange behaviour, I am still trying to figure out why this happens:

- AudioWagon should be running for voice input to work properly
- when AudioWagon is running, you can switch to *radio app* and back to AudioWagon both using your voice. However you
  can *not* switch to AudioWagon when a different *media app* is running (for example Spotify). Commands such as "Play
  Michael Jackson on AudioWagon" appear to *not* work

When using a voice command, if you hear a voice prompt from Google Assistant that goes like "To do that, you'll need to
install &lt;Pandora/YouTube Music/Spotify/...&gt; then you have likely selected a default music app in your *Google
Assistant settings*. To change that, go to the Google Assistant settings *on your Android phone* (using the same Google
account as in your car).  In here go to "Music" and select "No default provider":

![Google Assistant default music provider](/img/music_provider.jpg)

### Does the app support video? Will you add support for videos?

No.

### Does the app work on phones with Android Auto?

No.

[Android Auto](https://www.android.com/auto/) is a software running on your mobile phone and it connects to your car.
The AudioWagon app can only run in
[Android Automotive OS (AAOS)](https://developers.google.com/cars/design/automotive-os) which is an operating system
built into certain car models.


## Other

### I want to thank you, do you accept donations?

I am fine, thanks. If you really want to give something in return, please put it towards a good cause, for example
to protect the environment (reforestation, solar power, right-to-repair, local projects, etc.).

*Update:* Oh wow, people are actually doing this and notifying me, this is amazing! 😄

- Thank you Timo for planting 100 trees in Madagascar!
- Thank you Bill for donating to a local animal shelter!
- Thank you Vasimo for donating to Vancouver Food Runners!
- Thank you Trevor for donating to Northern India Flood Relief!


### Why don't you just use a streaming app? USB is so old-fashioned!

Many people don't find streaming apps appealing for some of these reasons:

- streaming requires a *reliable internet connection*. In cars with cellular modems you will have problems in certain
  rural areas and will have dropouts (think UK or Germany, think tunnels)
- while some streaming providers have a large selection of music, you might want to listen to some obscure or
  independent artists, that are *not available on any streaming platform*. Music enthusiasts who prefer
  physical/downloaded music usually have a large library of music they have compiled over multiple decades from various
  sources and have no need for streaming
- streaming platforms require a *monthly subscription*. If not subscribed, you will get ads. With physical/downloaded
  music, you only pay once and usually keep the music for your lifetime. Also in case the streaming provider ever 
  *goes out of business*, you have nothing. With physical/downloaded music, you own the music data
- streaming platforms might limit the *audio quality* of the music you are listening to (and some artists refused to use
  certain streaming providers for this reason). With downloaded music files you are only limited by the quality of
  those files themselves (and by the audio hard- and software in the car, but that is the same for streaming)
- streaming music probably has a slightly higher *CO2 footprint* than downloading music (I don't have conclusive studies
  to link to, but some articles suggest that direction)

Most car makers still included USB media players in their infotainment systems in recent years, so USB is not as
"deprecated" as some people might pronounce it as.

