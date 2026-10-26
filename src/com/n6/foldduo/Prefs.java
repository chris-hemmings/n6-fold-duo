package com.n6.foldduo;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings shared by the activity and the service (same process). */
public final class Prefs {
    public static final String NAME = "foldduo";
    public static final String ENABLED = "enabled", OPEN = "open", CLOSED = "closed",
            BLUR = "blur", SHADOW = "shadow", GLASS = "glass",
            SMOOTH_MS = "smooth_ms", CAPTURE_SCALE = "capture_scale", WARP = "warp", TAPER = "taper", PAGE_WIDTH = "page_width", COVER_EFFECT = "cover_effect", CORNER_RADIUS = "corner_radius", EDGE_SOFTNESS = "edge_softness", HINGE_BLEND = "hinge_blend", SHADE = "shade", WINDOW = "window", VIEW_DISTANCE = "view_distance", EYE_OFFSET = "eye_offset", WINDOW_STRETCH = "window_stretch", BLUR_LEFT_ONLY = "blur_left_only", HINGE_RIGHT = "hinge_right", HUD = "hud",
            CLOSE_SWITCH = "close_switch", OPEN_SWITCH = "open_switch";
    /** Used until the real switch angle has been measured. */
    public static final float DEFAULT_SWITCH = 70f;

    /** The iPhone Duo look: no on-screen warp, blur running past the hinge, a darkened left page. */
    public static void applyIphoneDuo(SharedPreferences p) {
        p.edit()
                .putBoolean(WARP, true)        // still needs the live capture for the blur gradient
                .putBoolean(WINDOW, false)     // the panel's own tilt provides the perspective
                .putInt(TAPER, 0)
                .putInt(PAGE_WIDTH, 100)
                .putInt(CORNER_RADIUS, 0)
                .putInt(EDGE_SOFTNESS, 0)
                .putInt(BLUR, 80)
                .putInt(HINGE_BLEND, 7)
                .putInt(SHADE, 60)
                .putInt(SHADOW, 0)
                .putInt(GLASS, 0)
                .apply();
    }

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
        c.hingeBlend = p.getInt(HINGE_BLEND, 0) / 100f;
        c.shade = p.getInt(SHADE, 0) / 100f;
        c.window = p.getBoolean(WINDOW, false);
        c.viewDistanceCm = p.getInt(VIEW_DISTANCE, 35);
        c.eyeOffset = p.getInt(EYE_OFFSET, 50) / 100f;
        c.windowStretch = p.getInt(WINDOW_STRETCH, 0) / 100f;
        // Panels swap at a different angle when closing vs opening. Each panel's effect spans its
        // whole visible range: the inner panel stays on down to the closing switch, the cover
        // panel stays on up to the opening switch. (Entering a panel the other way, it simply
        // starts part-way through.)
        c.innerSwitch = p.getFloat(CLOSE_SWITCH, DEFAULT_SWITCH);
        c.coverSwitch = p.getFloat(OPEN_SWITCH, DEFAULT_SWITCH);
        return c;
    }
}
