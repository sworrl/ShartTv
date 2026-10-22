#!/bin/sh
# Re-enable everything Shart TV disabled. Usage: ./rollback-debloat.sh <ip:port>
T=${1:-<tv-ip>:5555}
adb -s $T shell pm enable --user 0 tv.samba.ssm
adb -s $T shell pm enable --user 0 tv.samba.ssm.ui
adb -s $T shell pm enable --user 0 com.sony.dtv.promos
adb -s $T shell pm enable --user 0 com.sony.dtv.sonyselect
adb -s $T shell pm enable --user 0 com.sonyericsson.idd.agent
adb -s $T shell pm enable --user 0 com.sony.dtv.demosupport
adb -s $T shell pm enable --user 0 com.google.android.feedback
adb -s $T shell pm enable --user 0 com.sony.dtv.voicesearch
adb -s $T shell pm enable --user 0 com.sony.dtv.voicerecognitionconfirmation
adb -s $T shell pm enable --user 0 com.sony.dtv.assistantadapter
adb -s $T shell pm enable --user 0 com.sony.dtv.sensingcamera.settings
adb -s $T shell pm enable --user 0 com.sony.dtv.sensingcamera.fwupdate
adb -s $T shell pm enable --user 0 com.sony.dtv.sensingfusionservice
adb -s $T shell pm enable --user 0 com.sony.dtv.gestureapp
adb -s $T shell pm enable --user 0 com.sony.dtv.distancealert
