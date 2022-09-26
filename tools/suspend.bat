@ECHO OFF
echo Suspend to RAM
cmd.exe /c "adb shell echo freeze ^> /sys/power/state"
