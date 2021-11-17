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

Sorry, I don't know why that happens. It works fine on the mobile phone that I use for development, however the car
maker probably put some extra security in the car that will always trigger this permission dialog popup. I would need
help from the car maker or Google to improve this.

### What is the eject button for?

The eject button will make sure that the USB drive is not in use when you unplug it. Press it while the infotainment
system is still on and wait for the popup to appear, then you can safely unplug your USB drive.

If you do not follow this procedure, and unplug the USB drive while it is still in use, the app could crash or the data
on your USB drive might be damaged. 

### I get a popup saying "Too many files in a directory". What is that about?

![Too many files in a directory](/img/too_many_files.jpg)

There is a limitation that you can only have up to 128 files in directory in versions 0.6.0 and lower. Directories with
more files than that are ignored. This has been fixed in version 0.6.1 and higher, please update the app.

### Why is my USB drive not recognized?

![FAT32 error](/img/fat32_error.jpg)

The app only supports USB flash drives formatted using **FAT32 filesystem**. There are several tools available that you
can download from the internet to format a drive using this filesystem. On Windows I recommend
[Rufus](https://rufus.ie/en/).

Also make sure to plug the USB flash drive into the port in your car that is marked (usually in the front of the car,
with a white outline). The other USB ports are for *charging your phone only*.

![USB port](/img/port.jpg)

### Why does it say "Loading content &hellip;" and nothing happens?

This usually indicates some error has happened somewhere. Please switch to a different audio app (e.g. radio or
Bluetooth™) and then back to AudioWagon, that should fix it.

If not, go to General Android Settings &#8594; Apps and notifications &#8594; Show all
apps &#8594; Show system &#8594; Media Center and tap "Force Stop", then try the app again.

### What happens during indexing?

![Indexing](/img/indexing.jpg)

When connecting a USB drive for the first time the app walks through all the directories on the USB drive and extracts
*metadata* from all audio files (i.e. artist, album, title, year etc.). This information is written into a database
stored in the car. This process will take some time (a couple of minutes for 10000 files).

When connecting the same USB drive again, the app will again go through all directories and all files but will check the
last file modification date. If the file has been modified, it will re-read the metadata of that file. If not, it will
take the information from the database. Thus this process should be much quicker than the first time (there is an [open
feature request](https://github.com/MoleMan1024/audiowagon/issues/21) to make this faster or run less often).

In version 0.5.0 and higher you can turn off this indexing of the metadata in the settings screen. Afterwards you will
not be able to navigate by track/artist/album anymore, only by file/directory.

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

Alternatively you can also put "Various Artists" in the *album artist* tag, this will have the same effect as the
compilation field above.

Afterwards the compilation album will show up in the album view with a pseudo artist "Various Artists"

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


### My operating system tells me that my USB drive has problems. But all files are okay?

This can happen when the USB drive is not properly ejected. A secret marker will be set on the USB drive and this
warning will pop-up reminding you to always properly eject the USB drive. Use your operating system to repair the USB
drive. That should remove this warning message.

### Files on my USB drive were deleted!

This usually means the FAT32 filesystem was damaged somehow. Unfortunately this still sometimes happens to a few people.
I am trying my best to avoid this situation but sometimes I don't know why it happens. Here are some things you can try
if this happens to you:

- First of all do not store any important data on the USB drive other than audio files. Keep a copy of those files on
  your PC
- Update the app to version 0.6.1 or higher, there was an issue in earlier versions that could cause this corruption
- Try using a different USB drive (flash memory can wear out after some years)
- Try putting less data on the USB drive (this issue seems to happen more frequently to people who have gigabytes of
  music that they bring into their car)
- Make sure to always properly eject the USB drive before unplugging it. Do not assume that if the infotainment screen
  is off it is safe to unplug the USB drive. Like on your phone, even with the screen off the Android system might still
  be running.
- Try turning off log files in the settings screen. The log file will likely be lost anyway if the filesystem is
  damaged. This will avoid that anything is written to the USB drive, the app will only read from it in this case.

You might be able to recover some of your data using e.g. Windows *chkdsk* tool. You should re-format the USB drive 
afterwards to make sure it is in a clean state.


## Supported features

### Is there a limitation on the number of files the app can handle?

There is a theoretical limit of about 160000 tracks. However you might observe other issues before you reach that many
tracks (see above) and you will go mad due to the loading time 😉. 

Also before you try to go there, ask yourself how many tracks you really need on your journey. 10000 tracks with an
average of 3 minutes each is already more than 20 days of music to listen to.

### Can I use multiple USB drives?

Yes, in most cases.

The only thing that will not work is using multiple USB drives of the same model from the same
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
- sometimes the TTS voice says "Sorry, an error has occured" although the app has executed the command just fine


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

*Update Nov 2021:* Oh wow, people are actually doing this and notifying me, this is amazing! 😄

- Thank you Timo for planting 100 trees in Madagascar


