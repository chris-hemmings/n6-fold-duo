package com.n6.foldduo;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings shared by the activity and the service (same process). */
public final class Prefs {
    public static final String NAME = "foldduo";
    public static final String ENABLED = "enabled", OPEN = "open", CLOSED = "closed",
            BLUR = "blur", SHADOW = "shadow", GLASS = "glass",
            SMOOTH_MS = "smooth_ms", CAPTURE_SCALE = "capture_scale", WARP = "warp", TAPER = "taper", PAGE_WIDTH = "page_width", COVER_EFFECT = "cover_effect", CORNER_RADIUS = "corner_radius", EDGE_SOFTNESS = "edge_softness", BLUR_LEFT_ONLY = "blur_left_only", HINGE_RIGHT = "hinge_right", HUD = "hud",
            CLOSE_SWITCH = "close_switch", OPEN_SWITCH = "open_switch";
    /** Used until the real switch angle has been measured. */
    public static final float DEFAULT_SWITCH = 70f;

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static FoldEffect.Config config(SharedPreferences p) {
        FoldEffect.Config c = new FoldEffect.Config();
        c.open = p.getInt(OPEN, 172);
        c.closed = p.getInt(CLOSED, 2);
        c.maxBlurPx = p.getInt(BLUR, 60);
        c.shadow = p.getInt(SHADOW, 60) / 100f;
        c.glass = p.getInt(GLASS, 100) / 100f;
        c.taper = p.getInt(TAPER, 40) / 100f;
        c.pageWidth = p.getInt(PAGE_WIDTH, 100) / 100f;
        c.coverEffect = p.getBoolean(COVER_EFFECT, false);
        c.cornerRadius = p.getInt(CORNER_RADIUS, 250);
        c.edgeSoftness = p.getInt(EDGE_SOFTNESS, 40);
        // Panels swap at a different angle when closing vs opening. Each panel's effect spans its
        // whole visible range: the inner panel stays on down to the closing switch, the cover
        // panel stays on up to the opening switch. (Entering a panel the other way, it simply
        // starts part-way through.)
        c.innerSwitch = p.getFloat(CLOSE_SWITCH, DEFAULT_SWITCH);
        c.coverSwitch = p.getFloat(OPEN_SWITCH, DEFAULT_SWITCH);
        return c;
    }
}
