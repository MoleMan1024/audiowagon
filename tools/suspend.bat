@ECHO OFF
echo Suspend to RAM
cmd.exe /c "adb shell echo mem ^> /sys/power/state"

REM alternatively use "adb shell rtcwake -m freeze -s 5" to wake up after 5 seconds