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
        public float cornerRadius = 250f;
        /** Feather (px) where the left page meets the black around it, at full strength. */
        public float edgeSoftness = 40f;
        /** How far the left page's blur carries on past the hinge, as a fraction of the inner screen's width. */
        public float hingeBlend = 0f;
        /** Darkening of the left page at full strength: this much black at its outer edge, none at the hinge. */
        public float shade = 0f;
        /**
         * Window projection: instead of a fixed trapezoid, show the left page as a window onto the
         * flat screen behind it, from an eye viewDistanceCm away and eyeOffset (fraction of the
         * page width) to the right of the hinge. Uses the real hinge angle.
         */
        public boolean window = false;
        public float viewDistanceCm = 35f, eyeOffset = 0.5f;
        /**
         * How much of the projection's sideways stretch to keep (0 = picture keeps its width, only
         * the height tapers). Full stretch only looks right from exactly the assumed eye position.
         */
        public float windowStretch = 0f;
        /**
         * How the blur and the shade build up over the effect (exponent on its strength): below 1
         * comes in early and stays heavy, above 1 holds off until near the switch.
         */
        public float blurCurve = 1.5f, shadeCurve = 1f;
        /** Vertical blur as a fraction of the horizontal: below 1 smears the page sideways. */
        public float blurVertical = 1f;
        /** Fraction of the left page, ending at the hinge, over which the blur fades out (1 = the whole page). */
        public float blurFade = 1f;
        /** Opened and held still at or above this angle for holdClearMs, the blur clears (closing: any angle). */
        public float holdClearAngle = 90f;
        public int holdClearMs = 500;
        /** How long the blur takes to fade out once held still. */
        public int blurFadeOutMs = 400;
        /** Once cleared, how far (degrees) the hinge must move before the blur comes back. */
        public float rearmDeg = 6f;
        /**
         * 0..1 blur envelope set by the service each frame: on while the hinge moves, off once it
         * has been held still above holdClearAngle. The blur follows movement, not the angle.
         */
        public float blurGate = 1f;
    }

    /** Output for one frame. */
    public float strength, blurPx, shadowAlpha, shadowWidth, sheenAlpha, sheenPos;
    /** Left page: fraction of height the outer edge loses (0 = flat). The hinge edge keeps full height. */
    public float pageTaper;
    /** Left page width as a fraction of the half-screen; 1 when fully open. */
    public float pageWidthFrac = 1f;
    /** Strength 0..1 used to ease the corner radius from the screen's own radius to the setting. */
    public float pageAmount;
    /** Left page shade for this frame (0..1 black at the outer edge). */
    public float shadeAlpha;
    /** Hinge angle this frame was computed for (degrees; NaN when unknown). */
    public float angle = Float.NaN;

    public static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    public static float smoothstep(float e0, float e1, float x) {
        if (e1 <= e0) return x >= e1 ? 1f : 0f;
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3f - 2f * t);
    }

    /** Degrees before fully open over which the inner blur lands, so it never cuts off in a jump. */
    public static final float END_FADE = 15f;

    /** Inner panel blur: the movement envelope, landing to none over the last END_FADE before open. */
    public static float blurAmount(float angle, Config c) {
        if (Float.isNaN(angle)) return 0f;
        return clamp01(c.blurGate) * clamp01((c.open - angle) / END_FADE);
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
        this.angle = angle;
        blurPx = c.maxBlurPx * (float) Math.pow(cover ? p * clamp01(c.blurGate) : blurAmount(angle, c), c.blurCurve);
        shadowAlpha = clamp01(c.shadow * p);
        shadowWidth = 0.15f + 0.85f * p;
        sheenAlpha = 0.15f * c.glass * (float) Math.sin(Math.PI * p);
        sheenPos = p;
        pageTaper = c.taper * p;
        pageWidthFrac = 1f - (1f - c.pageWidth) * p;
        pageAmount = p;
        shadeAlpha = clamp01(c.shade * (float) Math.pow(p, c.shadeCurve));
        return this;
    }

    /**
     * Window projection, as {flat corners..., panel corners...} for setPolyToPoly (flat -> panel),
     * in natural coordinates (hinge is the vertical line x = hingeX, page is x < hingeX, height h).
     *
     * The left panel is tilted toward the viewer by f = 180 - angle. A point on it u px from the
     * hinge, seen from an eye D px in front of the flat screen and e px to the right of the hinge,
     * lines up with the flat-screen point X(u) = (u cos f + e) / t - e, t = 1 - u sin f / D, and
     * heights there are scaled by t around the eye's height. Seeing one plane through another from
     * a point is a homography, so the four page corners pin it down exactly. Toward fully open the
     * result eases to flat so the effect can end without a jump.
     */
    public static float[] windowCorners(float hingeX, float h, float angleDeg, float distPx, float eyeOffset,
                                        float stretch, float amount) {
        float u = hingeX, ye = h / 2f, e = eyeOffset * hingeX;
        // Past perpendicular the panel faces away from the viewer and the projection degenerates;
        // hold it at the perpendicular view (by then the page is mostly blur and shade anyway).
        double f = Math.toRadians(Math.max(0f, Math.min(88f, 180f - angleDeg)));
        float t = (float) (1 - u * Math.sin(f) / distPx);
        float x = (float) ((u * Math.cos(f) + e) / t - e);
        x = u + (x - u) * clamp01(stretch);
        float k = clamp01(amount);
        // Flat-screen points seen at the panel's outer corners, eased toward the flat page.
        float outerX = hingeX - (u + (x - u) * k);
        float top = ye - ye / (1f + (t - 1f) * k), bottom = ye + (h - ye) / (1f + (t - 1f) * k);
        return new float[]{
                outerX, top, hingeX, 0f, hingeX, h, outerX, bottom,
                0f, 0f, hingeX, 0f, hingeX, h, 0f, h};
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
