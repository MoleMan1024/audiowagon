@ECHO OFF
echo Go to ON state
cmd.exe /c "adb shell dumpsys activity service com.android.car inject-vhal-event 0x11410A00 0,0"
sleep 2s
echo Suspend
cmd.exe /c "adb shell dumpsys activity service com.android.car suspend --simulate"
sleep 2s
echo Suspend with VHAL
cmd.exe /c "adb shell dumpsys activity service com.android.car inject-vhal-event 0x11410A00 3,0"

sleep 10s

echo Resume
cmd.exe /c "adb shell dumpsys activity service com.android.car resume"
sleep 2s
echo Resume with VHAL
cmd.exe /c "adb shell dumpsys activity service com.android.car inject-vhal-event 0x11410A00 0,0"

