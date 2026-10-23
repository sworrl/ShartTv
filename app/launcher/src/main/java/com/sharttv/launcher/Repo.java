package com.sharttv.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Reads installed TV apps and TV inputs using public APIs only. No network, no tracking. */
public class Repo {

    /** Installed launchable apps: LEANBACK_LAUNCHER first, then any LAUNCHER app not already seen. */
    public static List<Tile> apps(Context ctx, Theme theme) {
        PackageManager pm = ctx.getPackageManager();
        List<Tile> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String self = ctx.getPackageName();

        Intent lb = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(lb, 0)) {
            addApp(pm, out, seen, ri, theme, self, true);
        }
        Intent la = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(la, 0)) {
            addApp(pm, out, seen, ri, theme, self, false);
        }
        Collections.sort(out, new Comparator<Tile>() {
            public int compare(Tile a, Tile b) { return a.label.compareToIgnoreCase(b.label); }
        });
        return out;
    }

    private static void addApp(PackageManager pm, List<Tile> out, Set<String> seen,
                               ResolveInfo ri, Theme theme, String self, boolean leanback) {
        String pkg = ri.activityInfo.packageName;
        if (pkg.equals(self)) return;           // don't list ourselves
        if (seen.contains(pkg)) return;
        seen.add(pkg);
        CharSequence lab = ri.loadLabel(pm);
        Drawable icon = null;
        try {
            if (leanback && "banner".equals(theme.iconStyle)) icon = ri.activityInfo.loadBanner(pm);
            if (icon == null) icon = ri.loadIcon(pm);
        } catch (Exception e) { icon = ri.loadIcon(pm); }
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        out.add(new Tile(Tile.Kind.APP, lab == null ? pkg : lab.toString(), icon, launch, pkg));
    }

    /** HDMI / external inputs via TvInputManager. Switching uses the public passthrough URI. */
    public static List<Tile> inputs(Context ctx, Theme theme) {
        List<Tile> out = new ArrayList<>();
        try {
            TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) return out;
            PackageManager pm = ctx.getPackageManager();
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput()) continue;   // HDMI/Composite/etc.
                CharSequence lab = info.loadLabel(ctx);
                String label = lab == null ? info.getId() : lab.toString();
                Uri uri = TvContract.buildChannelUriForPassthroughInput(info.getId());
                Intent launch = new Intent(Intent.ACTION_VIEW, uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Drawable glyph = new ColorDrawable(theme.surfaceColor);
                Tile t = new Tile(Tile.Kind.INPUT, label, glyph, launch, info.getId());
                // "HDMI 3 (eARC/ARC)" -> title "HDMI 3", caption "eARC"
                int paren = label.indexOf('(');
                if (paren > 0) {
                    t.label = label.substring(0, paren).trim();
                    String s = label.substring(paren + 1).replace(")", "").trim();
                    t.sub = s.toLowerCase(java.util.Locale.US).contains("earc") ? "eARC" : s;
                }
                t.art = inputArt(t.label);
                out.add(t);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Bespoke art per input: HDMI 1-4 each have their own, then composite video, then a generic door. */
    private static int inputArt(String label) {
        String l = label.toLowerCase(java.util.Locale.US);
        if (l.startsWith("hdmi")) {
            switch (l.replaceAll("\\D", "")) {
                case "1": return R.drawable.art_hdmi1;
                case "2": return R.drawable.art_hdmi2;
                case "3": return R.drawable.art_hdmi3;
                case "4": return R.drawable.art_hdmi4;
            }
        }
        if (l.contains("video") || l.contains("composite") || l.contains("component") || l.contains("av"))
            return R.drawable.art_video;
        return R.drawable.art_input;
    }

    public static List<Tile> settings(Context ctx, Theme theme) {
        List<Tile> out = new ArrayList<>();
        out.add(new Tile(Tile.Kind.SETTINGS, "Appearance",
                new ColorDrawable(theme.accentColor),
                new Intent(ctx, SettingsActivity.class), "appearance"));
        Intent sys = new Intent(android.provider.Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        out.add(new Tile(Tile.Kind.SETTINGS, "System Settings",
                new ColorDrawable(theme.surfaceColor), sys, "system"));
        Intent net = new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        out.add(new Tile(Tile.Kind.SETTINGS, "Network",
                new ColorDrawable(theme.surfaceColor), net, "network"));
        return out;
    }
}
