package com.n6.foldduo;

/**
 * Pure angle -> effect mapping (no Android dependencies, so it can be unit tested on a PC).
 *
 * The effect depends only on which panel is active and the hinge angle, so folding and
 * unfolding are mirror images of each other:
 *  - inner panel: none at {@code open} degrees, full at {@code innerSwitch}
 *  - cover panel: none at {@code closed} degrees, full at {@code coverSwitch}
 * The effect never darkens the screen; it blurs, angles the left page, and adds a hinge shadow and sheen.
 */
public final class FoldEffect {
    public static final class Config {
        public float open = 172f, closed = 2f;
        /** Angle at which ColorOS swaps panels; learned at runtime. */
        public float innerSwitch = 70f, coverSwitch = 70f;
        public float maxBlurPx = 60f;
        public float shadow = 0.6f, glass = 1f;
        /** Whether the cover panel gets any effect at all (off: it shows plain content right away). */
        public boolean coverEffect = false;
        /** Inner left page at full strength: fraction of its height the outer edge loses (perspective taper). */
        public float taper = 0.4f;
        /** Inner left page width at full strength, as a fraction of the half-screen (1 = full width). */
        public float pageWidth = 1f;
        /** Radius (px) of the left page's outer corners at full strength. */
        public float cornerRadius = 120f;
        /** Feather (px) where the left page meets the black around it, at full strength. */
        public float edgeSoftness = 40f;
    }

    /** Output for one frame. */
    public float strength, blurPx, shadowAlpha, shadowWidth, sheenAlpha, sheenPos;
    /** Left page: fraction of height the outer edge loses (0 = flat). The hinge edge keeps full height. */
    public float pageTaper;
    /** Left page width as a fraction of the half-screen; 1 when fully open. */
    public float pageWidthFrac = 1f;
    /** Strength 0..1 used to ease the corner radius from the screen's own radius to the setting. */
    public float pageAmount;

    public static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    public static float smoothstep(float e0, float e1, float x) {
        if (e1 <= e0) return x >= e1 ? 1f : 0f;
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3f - 2f * t);
    }

    /** 0 = no effect, 1 = full effect (at the panel switch). */
    public static float strength(boolean cover, float angle, Config c) {
        if (Float.isNaN(angle)) return 0f;
        if (cover) {
            if (!c.coverEffect) return 0f;
            float top = Math.max(c.coverSwitch, c.closed + 1f);
            return clamp01((angle - c.closed) / (top - c.closed));
        }
        float bottom = Math.min(c.innerSwitch, c.open - 1f);
        return clamp01((c.open - angle) / (c.open - bottom));
    }

    public FoldEffect compute(boolean cover, float angle, Config c) {
        float p = strength(cover, angle, c);
        strength = p;
        blurPx = c.maxBlurPx * (float) Math.pow(p, 1.5);
        shadowAlpha = clamp01(c.shadow * p);
        shadowWidth = 0.15f + 0.85f * p;
        sheenAlpha = 0.15f * c.glass * (float) Math.sin(Math.PI * p);
        sheenPos = p;
        pageTaper = c.taper * p;
        pageWidthFrac = 1f - (1f - c.pageWidth) * p;
        pageAmount = p;
        return this;
    }

    /** Outer corner radius: the screen's own corner radius when open, the setting at full strength. */
    public float cornerRadius(Config c, float screenRadius) {
        return screenRadius + (c.cornerRadius - screenRadius) * pageAmount;
    }

    /**
     * Where the left page's corners go, in natural coordinates (hinge is the vertical line x = hingeX,
     * page is x < hingeX, height h): {src corners..., dst corners...} as x,y pairs in the order
     * outer-top, hinge-top, hinge-bottom, outer-bottom. The hinge edge stays put; the outer edge
     * shrinks toward the vertical middle (like a page turned toward you) and moves in toward the
     * hinge by the page-width setting.
     */
    public float[] pageCorners(float hingeX, float h) {
        float inset = h * pageTaper / 2f, outerX = hingeX * (1f - pageWidthFrac);
        return new float[]{
                0f, 0f, hingeX, 0f, hingeX, h, 0f, h,
                outerX, inset, hingeX, 0f, hingeX, h, outerX, h - inset};
    }
}
