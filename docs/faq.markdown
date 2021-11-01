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

### It get a popup saying "Too many files in a directory". What is that about?

![Too many files in a directory](/img/too_many_files.jpg)

There is a limitation that you can only have up to 128 files in directory in versions 0.6.0 and lower. Directories with
more files than that are ignored. See [the section on limitations]({{ site.baseurl }}{% link index.markdown
%}#limitations). This has been fixed in version 0.6.1.

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
stored in the car. This process will take some time (a couple of minutes for 10000 files)

When connecting the same USB drive again, the app will again go through all directories and all files but will check the
last file modification date. If the file has been modified, it will re-read the metadata of that file. If not, it will
take the information from the database. Thus this process should be much quicker than the first time.

In version 0.5.0 and higher you can turn off this indexing of the metadata in the settings screen. Afterwards you will
not be able to navigate by track/artist/album anymore, only by file/directory.

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

There is a theoretical limit of about 160.000 tracks. However you might observe other issues before you reach that many
tracks (see above) and you will go mad waiting for indexing to complete 😉. 

Also there is a limitation regarding the number of files in a directory, see [the section on limitations]({{ site.baseurl }}{% link index.markdown %}#limitations).

Also before you try to go there, ask yourself how many tracks you really need on your journey.  10.000 tracks with an
average of 3 minutes each is already more than 20 days of music to listen to.

### Can I use multiple USB drives?

Yes, in most cases.

The only thing that will not work is using multiple USB drives of the same model from the same
manufacturer that have the same volume label. This will mess up the internal database. Please assign unique names when
you format or use USB drives by different manufacturers/different models.

If you meant "can I connect multiple USB drives at the same time using a USB hub?", then no, the app does not support
that.

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

### Does the app support video? Will you add support for videos?

No.

### Does the app work on phones with Android Auto?

No.

[Android Auto](https://www.android.com/auto/) is a software running on your mobile phone and it connects to your car.
The AudioWagon app can only run in
[Android Automotive OS (AAOS)](https://developers.google.com/cars/design/automotive-os) which is an operating system
built into certain car models.


## Other

### When will the app be released to everyone?

Current estimate is November 2021

