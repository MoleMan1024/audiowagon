@ECHO OFF
echo Suspend to RAM
cmd.exe /c "adb shell echo mem ^> /sys/power/state"
