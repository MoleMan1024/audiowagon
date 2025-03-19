@ECHO OFF
REM https://xdaforums.com/t/guide-build-mod-avd-kernel-android-10-11-12-13-rootavd-magisk-usb-passthrough-linux-windows-macos-google-play-store-api.4212719/
..\..\..\sdk\emulator\emulator.exe -verbose -selinux permissive -writable-system -netdelay none -netspeed full -avd AAOS_API_33 -usb-passthrough vendorid=0x0781,productid=0x5581

