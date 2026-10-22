#!/bin/sh
# Shart TV debloat — reversible, no root. Run against the BRAVIA over adb.
# Usage: ./apply-debloat.sh [ip:port] [--dlna] [--sensing] [--voice]
# Default set = ACR + ads + telemetry. Flags add optional groups.
set -u
T="${1:-<tv-ip>:5555}"; shift 2>/dev/null || true
A="adb -s $T shell pm"
BASE="tv.samba.ssm tv.samba.ssm.ui com.sony.dtv.promos com.sony.dtv.sonyselect \
com.sonyericsson.idd.agent com.sony.dtv.demosupport com.google.android.feedback"
DLNA="com.sonyericsson.dlna com.sony.huey.dlna.module com.sony.huey.dlna.renderersettings"
SENSING="com.sony.dtv.sensingcamera.settings com.sony.dtv.sensingcamera.fwupdate \
com.sony.dtv.sensingfusionservice com.sony.dtv.gestureapp com.sony.dtv.distancealert"
VOICE="com.sony.dtv.voicesearch com.sony.dtv.voicerecognitionconfirmation com.sony.dtv.assistantadapter"
set="$BASE"
for f in "$@"; do
  case "$f" in
    --dlna) set="$set $DLNA";; --sensing) set="$set $SENSING";; --voice) set="$set $VOICE";;
  esac
done
for pk in $set; do echo "disable $pk"; $A disable-user --user 0 "$pk"; done
echo "Done. Rollback with scripts/rollback-debloat.sh $T"
