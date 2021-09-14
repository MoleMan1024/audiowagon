---
layout: default
title: For developers
nav_order: 2
---

# AudioWagon developer documentation (DRAFT)

TODO: draft, clean up, provide info about app architecture, etc.

## How to build

My development environment is Windows, if you use Linux or Mac, some steps may differ (e.g. gradle wrapper setup). These
steps assume basic knowledge about build and install process on Android in general:

- Clone the project
- Load the project into latest Android Studio
- In Android Studio download the Android 10 SDK
- Use *gradle* to compile the project via Android Studio and create an .apk or .aab


## How to install in emulator

- In case you want to test in the emulator, in Android Studio download the system images and create an Android Virtual
  device (check the [Android developer pages](https://developer.android.com/training/cars/testing#system-images) )
- Use adb to install the apk or bundle in the emulator, e.g.  `adb install -r <packagename>.apk`


## How to install in car

(draft: I might have forgotten some steps)

The only way to get the app into the car right now is via Google Play Store. You will need to sign up for a developer
account (15 USD). This assumes you have done all basic steps required to create an app and you have gone through the
checklists in the play console.

Make sure to have the following settings in the Google Play Store developer console:

- in *Advanced Settings* your app must be *Published*, otherwise you will not be able to see it at all
- in *Advanced Settings* > *Release Types*, do *Add release type* > *Android Automotive OS*, then follow the
  instructions. In the end you need to have a checkmark at "Opt-in to Android Automotive" in here
- create some screenshots and add them under *Main Store listing* > *Android Automotive OS*. Additionally you will need
  to provide two phone screen screenshots, these will appear e.g. in web browser on PC. You will need
  to provide screenshots in all orientations, even when the car you want to publish the app to does not support
  landscape/portrait orientation (your app will be rejected if you miss this and appeals to Google will be answered with
  automated messages. I've had success resubmitting the same app without changes to the screenshots and it was
  accepted&hellip; but after improving the screenshots I did not see this issue again)

Right now "Internal Testing" is not supported for apps using AAOS features, so each release you create for the app needs
to go through Google's review process. Initially this can take a week, later it should go down to a couple of hours for
each release you make. Also note that any larger change you make (e.g. change country availability of the app) will also
trigger Google's review process.

- In Closed testing, switch category in top right to *Automotive OS only* and create a closed test track (NOTE: closed 
  tests are giving me trouble right now, app no longer visible in the car!)
- Add the country you use for your Play Store in the *Countries / regions* tab
- In *Testers*, create an e-mail list and add your own e-mail. Make sure to use the e-mail address associated with your
  Google account in the car

When you have uploaded the app the device catalog should show a few AAOS devices that are supported by the app.

After Google's review process is completed you will receive an e-mail. My impression is that these "reviews" are done
automatically only, so they might show some strange faults in your app that are incorrect (e.g. my app is always listed
as "has no content". I assume the automated check they do does not insert any SD card/USB). As long as you receive
another e-mail "Your update is live" afterwards, you can ignore these warnings.

When your update is live, the app should appear in the Play Store in the car. Make sure to search for it, not only
browse for it, Early Access Testing apps will not show upp in the browse view.


## How to install on a phone

The problem with the Android emulator is that it does not support USB (at least on Windows, I have not tried [these
steps for a Linux host](https://source.android.com/devices/automotive/start/passthrough)). Using a phone as development
platform is much more convenient. But note that the AAOS build does *not* behave exactly the same as the software in 
the car!

You will need a phone and install Android Automotive OS on it. Google provides [installation instructions for Google
Pixel 3 XL and Pixel 4 XL](https://source.android.com/devices/automotive/start/pixelxl). I use a Pixel 3 XL as dedicated
development platform.

Some notes on this process:

- You really need a BIG hard drive for this. Also it takes really long to compile. It is possible to do this in a
  VirtualBox
- If you want to enabled developer options (which you don't really need I found out later), you need to modify some
  files before compiling. If I remember correctly I set `Settings$DevelopmentSettingsDashboardActivity` to 
  `android:enabled="true"` in 
  [platform/packages/apps/Settings/+/master/AndroidManifest.xml](https://android.googlesource.com/platform/packages/apps/Settings/+/master/AndroidManifest.xml)
  This change will go into `product.img`. But I think `adbd` will work without this change as well.
- I needed to reduce the number of parallel jobs to 2 (`-j2` instead of `-j4`) to not run out of memory in my virtual
  machine

Since you will want to use the USB port of the phone, you must switch `adbd` to work via Wi-Fi whenever you start up the
phone. This can be done using these steps:

- With the phone connected via USB to your PC, run this to allow adb to connect via network: `adb tcpip 5555`
- Then unplug phone from USB and run `adb connect <IP_address_of_the_phone>:5555`. This can take multiple tries to
  succeed, if not working keep restarting the Wi-Fi on the phone. You can monitor if the connection works by pinging the
  phone continuously.
- Afterwards you can use `adb`, Android Studio debuggin etc. all normally via wireless network and the USB port is free
  for testing the USB functionality of the app

