package com.n6.foldduo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.view.Display;
import android.view.RoundedCorner;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * Everything the effect draws goes into this one surface, so the capture helper can leave it out
 * of screen captures (otherwise the overlay would capture and re-darken itself every frame).
 *
 * Inner panel: the left page is replaced by a live capture of itself, warped into a parallelogram
 * and blurred, over black. Then hinge shadow and glass sheen go on top.
 */
final class OverlaySurface extends SurfaceView implements SurfaceHolder.Callback {
    final FoldEffect fx = new FoldEffect();
    FoldEffect.Config config = new FoldEffect.Config();
    boolean cover, hingeRight, hud;
    int rotation;
    String hudText = "";
    /** Latest capture of display 0 (without this overlay), or null, and the screen region it shows. */
    Bitmap page;
    final Rect pageCrop = new Rect();

    private boolean surfaceReady;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Composite of the sharp page and, on top, the blurred page masked by a gradient. */
    private final RenderNode pageNode = new RenderNode("page");
    private final RenderNode blurNode = new RenderNode("pageBlur");
    /** The blurred copy faded out toward (and past) the hinge; also drawn alone in the strip past it. */
    private final RenderNode fadedBlurNode = new RenderNode("pageBlurFaded");
    private final Paint maskPaint = new Paint();
    /** Cuts the page to its rounded trapezoid; a blur mask filter feathers the edge into the black. */
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /**
     * Composites the edge-mask layer onto the page with DST_IN. Done as a whole layer, because a
     * shape drawn with DST_IN only affects the pixels it covers and would leave the corners alone.
     */
    private final Paint maskLayerPaint = new Paint();
    private final Paint shadePaint = new Paint();
    private float edgeFeather = -1f;
    // Reused every frame so drawing doesn't allocate (garbage collection pauses show up as hitches).
    private final Matrix toScreen = new Matrix(), toNatural = new Matrix(), warp = new Matrix();
    private final Path pagePath = new Path();
    private Bitmap recordedPage;
    private boolean recordedCover;
    private int recordedSteps = -1;
    private float recordedBlur = -1f;
    private boolean recordedDecal;
    private float recordedVertical = -1f;

    OverlaySurface(Context c) {
        super(c);
        setZOrderOnTop(true);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        getHolder().addCallback(this);
        text.setColor(Color.WHITE);
        text.setTextSize(14 * c.getResources().getDisplayMetrics().density);
        text.setShadowLayer(4f, 0f, 0f, Color.BLACK);
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        // Ask for the panel's full refresh rate so the system doesn't drop the overlay to 60 Hz.
        try {
            h.getSurface().setFrameRate(120f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        } catch (RuntimeException ignored) {
        }
    }
    @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) { surfaceReady = true; }
    @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; }

    boolean ready() {
        return surfaceReady && getSurfaceControl() != null && getSurfaceControl().isValid();
    }

    /** True when a capture is needed: the inner left page, or the blurred cover screen. */
    boolean wantsPage() {
        return fx.strength > 0f && (!cover || fx.blurPx >= 1f);
    }

    /** Screen region the capture should cover: the moving page on the inner panel, everything on the cover. */
    static Rect captureRegion(boolean cover, int rotation, int w, int h, float hingeBlend) {
        if (cover) return new Rect(0, 0, w, h);
        // The moving page, plus the strip past the hinge the blur carries on into.
        int steps = rotationSteps(rotation);
        int blend = Math.round(hingeBlend * (steps % 2 == 0 ? w : h));
        switch (steps) {
            case 1: return new Rect(0, Math.max(0, h / 2 - blend), w, h);
            case 2: return new Rect(Math.max(0, w / 2 - blend), 0, w, h);
            case 3: return new Rect(0, 0, w, Math.min(h, h / 2 + blend));
            default: return new Rect(0, 0, Math.min(w, w / 2 + blend), h);
        }
    }

    void render() {
        if (!surfaceReady) return;
        Surface surface = getHolder().getSurface();
        if (surface == null || !surface.isValid()) return;
        Canvas canvas;
        try {
            canvas = surface.lockHardwareCanvas();
        } catch (RuntimeException e) {
            return;
        }
        try {
            int w = getWidth(), h = getHeight();
            canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR);
            if (w > 0 && h > 0) draw(canvas, w, h);
        } finally {
            surface.unlockCanvasAndPost(canvas);
        }
    }

    private void draw(Canvas canvas, int w, int h) {
        if (fx.strength > 0f) {
            if (!cover && page != null) drawPage(canvas, w, h);
            else if (!cover) drawShadeOnly(canvas, w, h);
            else if (cover && page != null) {
                recordPage();
                drawRecordedPage(canvas);
            }
            drawSheen(canvas, w, h);
            drawShadow(canvas, w, h);
        }
        if (hud) {
            float y = h * 0.3f;
            for (String line : hudText.split("\n")) {
                canvas.drawText(line, w * 0.08f, y, text);
                y += text.getTextSize() * 1.3f;
            }
        }
    }

    /**
     * Work in "natural" coordinates (inner panel held like an open book, hinge vertical, moving page
     * on the left), then map through the display rotation to the screen.
     */
    private void drawPage(Canvas canvas, int w, int h) {
        int steps = rotationSteps(rotation);
        float nw = steps % 2 == 0 ? w : h, nh = steps % 2 == 0 ? h : w;
        naturalToScreen(toScreen, steps, nw, nh);
        toScreen.invert(toNatural);

        float[] corners = config.window
                ? FoldEffect.windowCorners(nw / 2f, nh, fx.angle, config.viewDistanceCm * pxPerCm(),
                        config.eyeOffset, config.windowStretch, Math.min(1f, fx.pageAmount * 4f))
                : fx.pageCorners(nw / 2f, nh);
        warp.setPolyToPoly(corners, 0, corners, 8, 4);

        canvas.save();
        canvas.concat(toScreen);
        canvas.clipRect(0, 0, nw / 2f, nh);
        canvas.drawColor(Color.BLACK);
        recordPage();
        // Draw the page into a layer, then keep only the rounded trapezoid with a soft edge.
        int layer = canvas.saveLayer(0, 0, nw / 2f, nh, null);
        canvas.save();
        canvas.concat(warp);
        canvas.concat(toNatural);
        drawRecordedPage(canvas);
        canvas.restore();
        if (fx.shadeAlpha > 0.002f) {
            int dark = Color.argb(Math.round(255 * fx.shadeAlpha), 0, 0, 0);
            shadePaint.setShader(new LinearGradient(0, 0, nw / 2f, 0,
                    new int[]{dark, Color.argb(Math.round(255 * fx.shadeAlpha * 0.55f), 0, 0, 0), Color.TRANSPARENT},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, nw / 2f, nh, shadePaint);
        }
        // Rounded to 2 px steps so the blur filter isn't rebuilt on every frame of motion.
        float feather = Math.round(config.edgeSoftness * fx.pageAmount / 2f) * 2f;
        if (feather != edgeFeather) {
            edgePaint.setMaskFilter(feather >= 0.5f ? new BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL) : null);
            edgeFeather = feather;
        }
        maskLayerPaint.setBlendMode(BlendMode.DST_IN);
        int mask = canvas.saveLayer(0, 0, nw / 2f, nh, maskLayerPaint);
        // Extend the shape past the hinge so only the outer edges feather; the hinge side stays
        // flush with the unblurred right half (the clip above cuts it off at the hinge).
        roundedPage(pagePath, corners, fx.cornerRadius(config, screenCornerRadius()), feather * 3f + 2f);
        canvas.drawPath(pagePath, edgePaint);
        canvas.restoreToCount(mask);
        canvas.restoreToCount(layer);
        canvas.restore();

        // Past the hinge: only the faded blur, unwarped, over the live right half, so the blur
        // fades out a little way past the hinge instead of stopping in a line there. Its mask
        // reaches zero at the far edge of this strip.
        float strip = config.hingeBlend * nw;
        if (strip >= 1f) {
            canvas.save();
            canvas.concat(toScreen);
            canvas.clipRect(nw / 2f, 0, nw / 2f + strip, nh);
            canvas.concat(toNatural);
            drawRecorded(canvas, fadedBlurNode);
            canvas.restore();
        }
    }

    /**
     * Record the captured frame at its own (small) resolution and blur it there: much cheaper than
     * blurring at screen size, and it gets scaled up when drawn anyway.
     *
     * Inner panel: the blurred copy is faded out toward the hinge, so the left page is most blurred
     * at its outer edge and sharp where it meets the (unblurred) right half. Cover panel: even blur.
     * The blur radius changes every frame but only touches blurNode's effect, not the recordings.
     */
    private void recordPage() {
        int bw = page.getWidth(), bh = page.getHeight(), steps = rotationSteps(rotation);
        if (page != recordedPage || cover != recordedCover || steps != recordedSteps) {
            blurNode.setPosition(0, 0, bw, bh);
            Canvas b = blurNode.beginRecording(bw, bh);
            b.drawBitmap(page, 0, 0, bitmapPaint);
            blurNode.endRecording();

            fadedBlurNode.setPosition(0, 0, bw, bh);
            Canvas f = fadedBlurNode.beginRecording(bw, bh);
            int layer = f.saveLayer(0, 0, bw, bh, null);
            f.drawRenderNode(blurNode);
            maskPaint.setShader(hingeFade(steps, bw, bh));
            maskPaint.setBlendMode(BlendMode.DST_IN);
            f.drawRect(0, 0, bw, bh, maskPaint);
            f.restoreToCount(layer);
            fadedBlurNode.endRecording();

            pageNode.setPosition(0, 0, bw, bh);
            Canvas c = pageNode.beginRecording(bw, bh);
            if (cover) {
                c.drawRenderNode(blurNode);
            } else {
                c.drawBitmap(page, 0, 0, bitmapPaint);
                c.drawRenderNode(fadedBlurNode);
            }
            pageNode.endRecording();
            recordedPage = page;
            recordedCover = cover;
            recordedSteps = steps;
            recordedBlur = -1f;
        }
        float blur = Math.max(0f, fx.blurPx) * bw / (float) Math.max(1, pageCrop.width());
        // In window mode the picture has edges inside the panel, so let the blur fade them out
        // (DECAL) rather than smear the edge pixels outward (CLAMP).
        boolean decal = config.window && !cover;
        // Horizontal blur along the page (away from the hinge), vertical as a fraction of it: on the
        // iPhone Duo the blur reads as a sideways smear. Which capture axis runs away from the hinge
        // depends on the rotation.
        float along = blur, across = blur * (cover ? 1f : config.blurVertical);
        boolean sideways = steps % 2 == 0;
        float bx = sideways ? along : across, by = sideways ? across : along;
        if (Math.abs(blur - recordedBlur) >= 0.25f || decal != recordedDecal || config.blurVertical != recordedVertical) {
            blurNode.setRenderEffect(blur >= 0.25f
                    ? RenderEffect.createBlurEffect(bx, Math.max(0.01f, by), decal ? Shader.TileMode.DECAL : Shader.TileMode.CLAMP) : null);
            recordedBlur = blur;
            recordedDecal = decal;
            recordedVertical = config.blurVertical;
        }
    }

    /**
     * Opacity mask for the blurred copy, in capture coordinates: opaque at the page's outer edge,
     * clear at the hinge. Which capture edge is the hinge depends on the display rotation (the
     * capture is the moving page, see captureRegion).
     */
    private static Shader hingeFade(int steps, int bw, int bh) {
        float ox, oy, hx, hy; // outer edge point -> hinge edge point
        switch (steps) {
            case 1: ox = 0; oy = bh; hx = 0; hy = 0; break;    // page is the bottom half, hinge on top
            case 2: ox = bw; oy = 0; hx = 0; hy = 0; break;    // page is the right half, hinge on its left
            case 3: ox = 0; oy = 0; hx = 0; hy = bh; break;    // page is the top half, hinge below
            default: ox = 0; oy = 0; hx = bw; hy = 0; break;   // page is the left half, hinge on its right
        }
        return new LinearGradient(ox, oy, hx, hy,
                new int[]{Color.BLACK, Color.argb(110, 0, 0, 0), Color.TRANSPARENT},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
    }

    /** Draw the recorded page back over the screen region it was captured from. */
    private void drawRecordedPage(Canvas canvas) {
        drawRecorded(canvas, pageNode);
    }

    /** Draw a recorded node (in capture coordinates) back over the screen region it came from. */
    private void drawRecorded(Canvas canvas, RenderNode node) {
        canvas.save();
        canvas.translate(pageCrop.left, pageCrop.top);
        canvas.scale(pageCrop.width() / (float) page.getWidth(), pageCrop.height() / (float) page.getHeight());
        canvas.drawRenderNode(node);
        canvas.restore();
    }

    /**
     * The left page's shade without a capture yet (the first frames after the inner screen comes
     * on), so the half isn't briefly bright before the angled page arrives.
     */
    private void drawShadeOnly(Canvas canvas, int w, int h) {
        if (fx.shadeAlpha <= 0.002f) return;
        int steps = rotationSteps(rotation);
        float nw = steps % 2 == 0 ? w : h, nh = steps % 2 == 0 ? h : w;
        naturalToScreen(toScreen, steps, nw, nh);
        canvas.save();
        canvas.concat(toScreen);
        int dark = Color.argb(Math.round(255 * fx.shadeAlpha), 0, 0, 0);
        shadePaint.setShader(new LinearGradient(0, 0, nw / 2f, 0,
                new int[]{dark, Color.argb(Math.round(255 * fx.shadeAlpha * 0.55f), 0, 0, 0), Color.TRANSPARENT},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, nw / 2f, nh, shadePaint);
        canvas.restore();
    }

    private float pxPerCm() {
        return getResources().getDisplayMetrics().xdpi / 2.54f;
    }

    private float screenCornerRadius() {
        Display d = getDisplay();
        RoundedCorner corner = d == null ? null : d.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT);
        return corner == null ? 0f : corner.getRadius();
    }

    /**
     * The page's trapezoid (dst corners from pageCorners: outer-top A, hinge-top B, hinge-bottom C,
     * outer-bottom D) with its two outer corners, A and D, rounded by radius r. The hinge edge is
     * pushed {@code hingeExtra} further toward the hinge side.
     */
    private static void roundedPage(Path p, float[] k, float r, float hingeExtra) {
        float ax = k[8], ay = k[9], bx = k[10] + hingeExtra, by = k[11], cx = k[12] + hingeExtra, cy = k[13], dx = k[14], dy = k[15];
        // Keep the rounding within half of each edge it touches.
        r = Math.max(0f, Math.min(r, Math.min(Math.min(dist(ax, ay, bx, by), dist(cx, cy, dx, dy)), dist(dx, dy, ax, ay) / 2f)));
        p.reset();
        float[] ab = toward(ax, ay, bx, by, r), cd = toward(dx, dy, cx, cy, r);
        float[] da = toward(ax, ay, dx, dy, r), ad = toward(dx, dy, ax, ay, r);
        p.moveTo(ab[0], ab[1]);
        p.lineTo(bx, by);
        p.lineTo(cx, cy);
        p.lineTo(cd[0], cd[1]);
        roundCorner(p, cd, dx, dy, ad);
        p.lineTo(da[0], da[1]);
        roundCorner(p, da, ax, ay, ab);
        p.close();
    }

    /**
     * Cubic from {@code from} to {@code to} around the corner (cx, cy), close to a circular arc
     * (control points 0.5523 of the way to the corner) rather than the flatter quadratic.
     */
    private static void roundCorner(Path p, float[] from, float cx, float cy, float[] to) {
        final float k = 0.5523f;
        p.cubicTo(from[0] + (cx - from[0]) * k, from[1] + (cy - from[1]) * k,
                to[0] + (cx - to[0]) * k, to[1] + (cy - to[1]) * k,
                to[0], to[1]);
    }

    private static float dist(float x0, float y0, float x1, float y1) {
        return (float) Math.hypot(x1 - x0, y1 - y0);
    }

    /** The point r along the line from (x0, y0) toward (x1, y1). */
    private static float[] toward(float x0, float y0, float x1, float y1, float r) {
        float len = dist(x0, y0, x1, y1);
        if (len <= 0f) return new float[]{x0, y0};
        return new float[]{x0 + (x1 - x0) * r / len, y0 + (y1 - y0) * r / len};
    }

    /** Natural (book-style) coordinates to screen, for each display rotation. */
    private static void naturalToScreen(Matrix m, int steps, float nw, float nh) {
        switch (steps) {
            case 1: m.setValues(new float[]{0, 1, 0, -1, 0, nw, 0, 0, 1}); break;   // x = Y, y = W - X
            case 2: m.setValues(new float[]{-1, 0, nw, 0, -1, nh, 0, 0, 1}); break; // x = W - X, y = H - Y
            case 3: m.setValues(new float[]{0, -1, nh, 1, 0, 0, 0, 0, 1}); break;   // x = H - Y, y = X
            default: m.reset(); break;
        }
    }

    private void drawSheen(Canvas canvas, int w, int h) {
        if (fx.sheenAlpha <= 0.002f) return;
        // A soft diagonal highlight that sweeps across the glass as the hinge moves.
        float diag = (float) Math.hypot(w, h);
        float c = -0.2f + 1.4f * fx.sheenPos, band = 0.12f;
        int white = Color.argb(Math.round(255 * fx.sheenAlpha), 255, 255, 255);
        paint.setShader(new LinearGradient(0, 0, diag * 0.94f, diag * 0.33f,
                new int[]{0, 0, white, 0, 0},
                new float[]{0f, clamp(c - band), clamp(c), clamp(c + band), 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
    }

    private void drawShadow(Canvas canvas, int w, int h) {
        if (fx.shadowAlpha <= 0.002f) return;
        int dark = Color.argb(Math.round(255 * fx.shadowAlpha), 0, 0, 0);
        if (!cover) {
            // Inner panel: hinge runs through the middle. Each half is cover-shaped, so the hinge
            // splits the shorter side.
            boolean vertical = w < h;
            float mid = (vertical ? w : h) / 2f, reach = mid * fx.shadowWidth;
            for (int dir = -1; dir <= 1; dir += 2) {
                float end = mid + dir * reach;
                paint.setShader(vertical
                        ? new LinearGradient(mid, 0, end, 0, dark, 0, Shader.TileMode.CLAMP)
                        : new LinearGradient(0, mid, 0, end, dark, 0, Shader.TileMode.CLAMP));
                if (vertical) canvas.drawRect(Math.min(mid, end), 0, Math.max(mid, end), h, paint);
                else canvas.drawRect(0, Math.min(mid, end), w, Math.max(mid, end), paint);
            }
            return;
        }
        // Cover panel: hinge is one long edge. Map the natural edge through the display rotation.
        int edge = ((hingeRight ? 2 : 0) + rotationSteps(rotation)) % 4; // 0=left 1=bottom 2=right 3=top
        float reach = (edge % 2 == 0 ? w : h) * fx.shadowWidth;
        switch (edge) {
            case 0: paint.setShader(new LinearGradient(0, 0, reach, 0, dark, 0, Shader.TileMode.CLAMP)); break;
            case 1: paint.setShader(new LinearGradient(0, h, 0, h - reach, dark, 0, Shader.TileMode.CLAMP)); break;
            case 2: paint.setShader(new LinearGradient(w, 0, w - reach, 0, dark, 0, Shader.TileMode.CLAMP)); break;
            default: paint.setShader(new LinearGradient(0, 0, 0, reach, dark, 0, Shader.TileMode.CLAMP)); break;
        }
        canvas.drawRect(0, 0, w, h, paint);
    }

    static int rotationSteps(int r) {
        switch (r) {
            case Surface.ROTATION_90: return 1;
            case Surface.ROTATION_180: return 2;
            case Surface.ROTATION_270: return 3;
            default: return 0;
        }
    }

    private static float clamp(float v) { return FoldEffect.clamp01(v); }
}
