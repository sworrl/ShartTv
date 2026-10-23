package com.sharttv.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * HOME redirect for locked builds (Sony BRAVIA) that refuse to honor a user app as the HOME role
 * holder. Sony's framework hard-forces the stock Google TV launcher (launcherx) for the HOME intent,
 * so the ONLY way to land on Shart TV from the remote's HOME button is to detect launcherx coming to
 * the front and immediately launch over it.
 *
 * It also notes which launchable app comes on screen (package name only, kept on the TV), so the
 * home's Recent row knows the app that was running even when it was opened from a remote button,
 * voice, or another app rather than from a Shart TV tile.
 *
 * And it hosts the {@link HourChime}: the top-of-the-hour clock + weather card drawn over whatever
 * is playing (an accessibility overlay needs no extra permission).
 *
 * Enable over adb (no root):
 *   settings put secure enabled_accessibility_services com.sharttv.launcher/com.sharttv.launcher.HomeRedirectService
 *   settings put secure accessibility_enabled 1
 */
public class HomeRedirectService extends AccessibilityService {

    private static final String STOCK = "com.google.android.apps.tv.launcherx";
    private long lastRedirect = 0;
    private final Map<String, Boolean> launchable = new HashMap<>();
    private HourChime chime;
    private android.content.BroadcastReceiver previewReceiver;
    private static HomeRedirectService instance;
    private final android.os.Handler wxH = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Keeps the weather cache fresh even while an app is in front (the home only refreshes while
     *  it's visible): every minute, fetch if the snapshot is 10+ minutes old and the screen is on. */
    private final Runnable wxTick = new Runnable() {
        @Override public void run() {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isInteractive())
                new com.sharttv.launcher.weather.WeatherEngine(HomeRedirectService.this).refreshIfOlderThan(10 * 60_000L);
            wxH.postDelayed(this, 60_000);
        }
    };

    /** For the Appearance screen's preview button; false when the service isn't running. */
    static boolean previewChime() {
        if (instance == null || instance.chime == null) return false;
        instance.chime.preview();
        return true;
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        chime = new HourChime(this);
        chime.start();
        wxH.removeCallbacks(wxTick);
        wxH.post(wxTick);
        // adb-only preview: `am broadcast -a com.sharttv.launcher.PREVIEW_CHIME`. Gated on DUMP, which
        // only shell/system hold, so other apps can't pop the overlay.
        previewReceiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, Intent i) {
                if (chime != null) chime.preview(i.getStringExtra("style"));   // --es style nixie
            }
        };
        registerReceiver(previewReceiver, new android.content.IntentFilter("com.sharttv.launcher.PREVIEW_CHIME"),
                android.Manifest.permission.DUMP, null, RECEIVER_EXPORTED);
    }

    @Override public void onDestroy() {
        if (chime != null) chime.stop();
        wxH.removeCallbacks(wxTick);
        if (previewReceiver != null) { try { unregisterReceiver(previewReceiver); } catch (Exception ignored) {} }
        instance = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cs = e.getPackageName();
        if (cs == null) return;
        String pkg = cs.toString();
        if (pkg.equals(getPackageName())) {
            if (chime != null) chime.homeInFront = LauncherActivity.class.getName().contentEquals(
                    e.getClassName() == null ? "" : e.getClassName());
            return;
        }
        if (!STOCK.equals(pkg)) {
            if (isLaunchable(pkg)) {
                if (chime != null) chime.homeInFront = false;
                new UsageStore(this).noteForeground("app:" + pkg);
            }
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastRedirect < 800) return;   // debounce launcherx's own transition churn
        lastRedirect = now;
        new UsageStore(this).clearForeground();
        try {
            Intent i = new Intent(this, LauncherActivity.class);
            // NO_ANIMATION so our side snaps in instantly — on this locked build one stock frame is
            // unavoidable; killing our own transition makes it a flicker instead of a cross-fade.
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    /** Only apps with a home tile count (skips system UI, dialogs, the keyboard...). Cached. */
    private boolean isLaunchable(String pkg) {
        Boolean b = launchable.get(pkg);
        if (b == null) {
            PackageManager pm = getPackageManager();
            b = pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null;
            launchable.put(pkg, b);
        }
        return b;
    }

    @Override public void onInterrupt() {}
}
