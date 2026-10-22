package com.sharttv.launcher.weather;

/** A location fix plus optional sun times (from BeaconFix), used for weather + time-of-day color. */
public class Loc {
    public boolean valid;
    public double lat, lon;
    public String place = "";
    public String source = "";
    // Sun times as "seconds since local midnight", -1 if unknown. BeaconFix supplies these.
    public int sunriseSec = -1, sunsetSec = -1, solarNoonSec = -1, civilDawnSec = -1, civilDuskSec = -1;
}
