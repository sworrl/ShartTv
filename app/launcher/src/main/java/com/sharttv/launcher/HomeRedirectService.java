package com.sharttv.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * HOME redirect for locked builds (Sony BRAVIA) that refuse to honor a user app as the HOME role
 * holder. When the stock Google TV launcher comes to the foreground (which is what the remote's
 * HOME button opens), this immediately launches Shart TV instead. It fires ONLY on the stock
 * launcher's own window, so it never interferes with YouTube, an input, or any other app.
 *
 * Enable over adb (no root):
 *   settings put secure enabled_accessibility_services com.sharttv.launcher/com.sharttv.launcher.HomeRedirectService
 *   settings put secure accessibility_enabled 1
 * Disable: set enabled_accessibility_services back to its previous value (or empty).
 */
public class HomeRedirectService extends AccessibilityService {

    private static final String STOCK = "com.google.android.apps.tv.launcherx";
    private long lastRedirect = 0;

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null || !STOCK.contentEquals(pkg)) return;
        // debounce so we don't thrash during the launcher's own transitions
        long now = SystemClock.uptimeMillis();
        if (now - lastRedirect < 1200) return;
        lastRedirect = now;
        try {
            Intent i = new Intent(this, LauncherActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    @Override public void onInterrupt() {}
}
