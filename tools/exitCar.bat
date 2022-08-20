@ECHO OFF
echo Stopping media player
cmd.exe /c "adb shell input keyevent 86"
sleep 2s
echo Waiting 30 seconds
sleep 30s
echo Sending power event to turn screen off
cmd.exe /c "adb shell input keyevent 26"
sleep 3s
echo detach USB device
pause
echo Suspend to RAM
cmd.exe /c "adb shell echo freeze ^> /sys/power/state"
sleep 3s
echo attach USB device
pause
echo Resume
cmd.exe /c "adb shell input keyevent 26"

