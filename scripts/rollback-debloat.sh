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
