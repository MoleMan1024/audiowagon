---
layout: default
title: For developers
nav_order: 3
---

# AudioWagon developer documentation (DRAFT)

TODO: draft, clean up, provide info about app architecture, etc.


## How to build

My development environment is Windows, if you use Linux or Mac, some steps may differ (e.g. *gradle* wrapper setup).
These steps assume basic knowledge about build and install process on Android in general:

- Get latest [Android Studio](https://developer.android.com/studio)
- In Android Studio download the Android 10 SDK
- Clone the project from Github
- Load the project into Android Studio
- Use *gradle* to compile the project via Android Studio and create an `.apk` or `.aab` bundle


## How to install in emulator

Android emulator does not support USB-passthrough out-of-the-box. To help with that I previously added basic support
for SD cards as well which you can attach to the Android emulator

- in Android Studio: download the system images and create a Android Virtual
  device (check the [Android developer pages](https://developer.android.com/training/cars/testing#system-images) )
- Run the Android emulator to start the virtual device
- Use `adb` to install the apk or bundle in the emulator, e.g. `adb install -r <packagename>.apk`

For SD card support:

- First you will need a SD card image file formatted as FAT32 containing media files. I was not able to achieve this in
  Windows, I used a Linux virtual machine and followed [these steps](https://askubuntu.com/questions/667291/create-blank-disk-image-for-file-storage).
- The standard Polestar 2/Android Automotive virtual devices in the emulator do not allow you to add a SD card image via
  the GUI. However you can still do it by editing the configuration .ini files. Add the following lines:

```
; in c:\Users\<username>\.android\avd\Polestar_2_API_29.avd\config.ini

hw.sdCard = yes
sdcard.path = C:\<path to SD card>.img
```

UPDATE 2024-03-04: I found out that someone has made the USB passthrough feature for Android emulator
[work in Windows and MacOS](https://xdaforums.com/t/guide-build-mod-avd-kernel-android-10-11-12-13-rootavd-magisk-usb-passthrough-linux-windows-macos-google-play-store-api.4212719/)
(original steps by Google for Linux can be found [here](https://source.android.com/devices/automotive/start/passthrough))


## How to install in car

(I might have forgotten some steps)

The only way to get the app into the car right now is via *Google Play Store*. You will need to [sign up for a developer
account](https://play.google.com/console/u/0/signup) (costs *15 USD*). Also note that you will *not* be able to connect
to the car using `adb`.

### Preparation

This assumes you have done all basic steps required to create an app and you have gone through the checklists in the
play console. You can find multiple guides online on how to do it.

Make sure to have the following settings in the Google Play Store developer console:

- in *Advanced Settings* your app must be *Published*, otherwise you will not be able to see it at all
- in *Advanced Settings* > *Release Types*, do *Add release type* > *Android Automotive OS*, then follow the
  instructions. In the end you need to have a checkmark at "Opt-in to Android Automotive" in here
- create some screenshots and add them under *Main Store listing* > *Android Automotive OS*. Additionally you will need
  to provide two phone screen screenshots, these will appear e.g. in web browser on PC. You will need
  to provide screenshots in all orientations, even when the car you want to publish the app to does not support
  landscape/portrait orientation (your app will be rejected if you miss this and appeals to Google will be answered with
  automated messages. I've had success re-uploading the same screenshot for another review and it was
  accepted&hellip; but after improving the screenshots I did not see this issue again)
- you will need to rename the package of the app, you cannot have two packages with identical names on Google Play Store

### Releasing

Right now "Internal Testing" is not supported for apps using AAOS features, so each release you create for the app needs
to go through Google's review process (2023-06-01 UPDATE: internal test tracks are supported meanwhile). Initially this
can take a week, later it should go down to a couple of hours for each release you make. Also note that any larger
change you make (e.g. change country availability of the app) will also trigger Google's review process.

- In Closed testing, switch category in top right to *Automotive OS only* and create a closed test track (NOTE: closed
  tests were giving me trouble at some point, the app was not visible in the car for some days. Also note that "Open
  testing" is *not* allowed for Automotive OS apps)
- Add the country you use for your Play Store in the *Countries / regions* tab
- In *Testers*, create an e-mail list and add your own e-mail. Make sure to use the e-mail address associated with your
  Google account in the car
- Then create a release in this test track

### After releasing

When you have uploaded the app the device catalog should show a few AAOS devices that are supported by the app, no other
devices should show up (because of `<uses-feature android:name="android.hardware.type.automotive" />` in the app's
manifest).

After Google's review process is completed you will receive an e-mail. My impression is that these "reviews" are done
automatically only, so they might show some strange warnings for your app that are incorrect (e.g. AudioWagon is always
listed as "has no content". I assume the automated check they do does not insert any SD card/USB). As long as you
receive another e-mail "Your update is live" afterwards, you can ignore these warnings (you will need to somehow resolve
the warnings before releasing to production).

### App is live

When your update is live, the app should appear in the Play Store in the car. Make sure to search for it, not only
browse for it, Early Access test track apps will not show up in the browse view.


## How to install on a phone

Using a phone as development platform is much more convenient than the emulator when you need USB. Also you will have
access to `adb logcat` logging and don't need to go through Google's review process in the car. But note that the
AAOS build does *not* behave exactly the same as the software in the car!

You will need a phone and install Android Automotive OS on it. Google provides [installation instructions for Google
Pixel 3 XL and Pixel 4 XL](https://source.android.com/devices/automotive/start/pixelxl) that show how to compile AAOS
for these phones. I use a Pixel 3 XL, you can get those used. You will not be able to use this phone for phone calls or
other meaningful tasks I would say.

Some notes on this process:

- You really need a BIG hard drive for this (I used 1TB). Also it takes really long to compile (I think >8 hours,
  depends on your hardware of course). It is possible to do this in a virtual machine using an external hard drive (e.g.
  via *VirtualBox*)
- If you want to enable developer options (which I think you don't really need&hellip;), you need to modify some
  files before compiling. If I remember correctly I set `Settings$DevelopmentSettingsDashboardActivity` to
  `android:enabled="true"` in
  [the Settings app's manifest](https://android.googlesource.com/platform/packages/apps/Settings/+/master/AndroidManifest.xml)
  This change will go into `product.img`. But I think `adbd` will work without this change as well.
- I needed to reduce the number of parallel jobs (`-j2` instead of `-j4`) to not run out of memory in my virtual
  machine
- In `packages/modules/adb/daemon/main.cpp` you can set `auth_require = false;` always so you don't ever get an ADB USB
  debugging permission popup.
- When doing `adb sync vendor` in last step, note this is not for `vendor.img` but for `vendor/` directory that needs to
  be uploaded to device as well. Without this the GUI scale is messed up (and you might not be able to click on *Accept*
  when asked for USB debugging permissions, use trick above instead).
- Alternatively: [procedure for a Raspberry Pi 4 and AAOS
  11](https://medium.com/snapp-automotive/android-automotive-os-11-on-a-raspberry-pi-2abaa133f468) (for newer
  Raspberry Pi 4B model it needs an updated `start4.elf` and `fixup4.dat` from e.g.
  `2021-05-07-raspios-buster-armhf.zip`)

Since you will want to use the USB port of the phone, you must switch `adbd` to work via Wi-Fi whenever you start up the
phone. This can be done using these steps:

- With the phone connected via USB to your PC, run this to allow `adb` to connect via network: `adb tcpip 5555`
- Then unplug phone from USB and run `adb connect <IP_address_of_the_phone>:5555`. This can take multiple tries to
  succeed, if it does not work, keep restarting Wi-Fi on the phone. You can monitor the connection by pinging the
  phone continuously.
- Afterwards you can use `adb`, Android Studio debugging etc. all normally via wireless network and the USB port is free
  for testing the USB functionality of the app


## Links

- A sample audio app for Android that also supports AAOS: [uamp](https://github.com/android/uamp)
- A tool by Google to test a MediaBrowser service. It will also run on AAOS. I found a few issues in the app using this:
  [android-media-controller](https://github.com/googlesamples/android-media-controller)
- The [Vehicle property IDs in the car library](https://developer.android.com/reference/android/car/VehiclePropertyIds).
  It shows a lot of fun things that 3rd party app developers are mostly not allowed to use.

You can find more links in the source code.

