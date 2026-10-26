package com.n6.foldduo;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.HardwareBuffer;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;

/**
 * Reads the hinge angle sensor and drives a full-screen overlay on logical display 0.
 * On the Find N6, display 0 is always whichever panel is lit (inner when open, cover when closed),
 * so a single overlay follows the active screen.
 */
public class FoldService extends AccessibilityService implements SensorEventListener,
        DisplayManager.DisplayListener, Choreographer.FrameCallback,
        SharedPreferences.OnSharedPreferenceChangeListener, CaptureClient.FrameListener {
    static final String TAG = "N6DUO";
    static final int TYPE_HINGE_ANGLE = 36;

    /** Set by the settings screen to preview the effect at a fixed angle; NaN when off. */
    static volatile float previewAngle = Float.NaN;
    static volatile FoldService instance;
    static volatile String status = "Service not running";

    private final Handler main = new Handler(Looper.getMainLooper());
    private SensorManager sensors;
    private DisplayManager displays;
    private WindowManager wm;
    private SharedPreferences prefs;
    private FoldEffect.Config config;
    private FrameLayout root;
    private OverlaySurface view;
    /**
     * Separate window for the window-level blur (cover panel, or the inner left half when no
     * capture is available). Blur-behind is clipped to the window, so this sets the blurred area.
     */
    private View blurView;
    private WindowManager.LayoutParams blurLp;
    private boolean blurSupported, frameScheduled, lastCover;
    private int appliedBlur = -1, appliedGravity, appliedW, appliedH;
    private float shownAngle = Float.NaN;
    private long lastFrameNs, lastLogNs;
    /**
     * Hinge samples arrive on their own thread so drawing never delays them. Velocity (deg/s) lets
     * each frame predict where the hinge is now rather than where it was at the last sample.
     */
    private final HandlerThread sensorThread = new HandlerThread("n6-hinge");
    private volatile float rawAngle = Float.NaN, velocity;
    private volatile long sampleNs;
    private volatile int events;
    /** Predict at most this far past the last sample; older samples mean the hinge has stopped. */
    private static final long MAX_PREDICT_NS = 40_000_000L;
    /** Longest gap between frames in the last second, for the readout (stutter shows up here). */
    private float worstFrameMs, worstFrameShownMs;
    private long worstFrameWindowNs;
    private final Runnable kickOnMain = new Runnable() {
        @Override public void run() { kick(); }
    };
    CaptureClient capture;
    /** Previous capture, kept one more frame so it is not freed while still being drawn. */
    private Bitmap previousPage;

    @Override
    protected void onServiceConnected() {
        instance = this;
        prefs = Prefs.get(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        config = Prefs.config(prefs);
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        displays = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        blurSupported = wm.isCrossWindowBlurEnabled();

        Sensor hinge = sensors.getDefaultSensor(TYPE_HINGE_ANGLE);
        if (hinge == null) {
            status = "No hinge angle sensor found";
        } else {
            sensorThread.start();
            sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_FASTEST, new Handler(sensorThread.getLooper()));
            status = "Running";
        }
        displays.registerDisplayListener(this, main);
        lastCover = isCover();
        addOverlay();
        capture = new CaptureClient(this, this);
        capture.connect();
        Log.i(TAG, "connected; hinge=" + hinge + " blur=" + blurSupported + " cover=" + lastCover);
        kick();
    }

    private static WindowManager.LayoutParams overlayParams(String title) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.setTitle(title);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0);
        return lp;
    }

    private void addOverlay() {
        // Blur window first so the effect window sits above it.
        blurView = new View(this);
        blurLp = overlayParams("N6FoldDuoBlur");
        root = new FrameLayout(this);
        view = new OverlaySurface(this);
        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        try {
            wm.addView(blurView, blurLp);
            wm.addView(root, overlayParams("N6FoldDuo"));
        } catch (RuntimeException e) {
            status = "Overlay failed: " + e.getMessage();
            Log.e(TAG, "addView", e);
            view = null;
        }
    }

    /** Request frames until the effect settles. */
    void kick() {
        if (view == null || frameScheduled) return;
        frameScheduled = true;
        lastFrameNs = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void doFrame(long nowNs) {
        frameScheduled = false;
        if (view == null) return;
        float target = !Float.isNaN(previewAngle) ? previewAngle : predictedAngle();
        float dtMs = lastFrameNs == 0 ? 16f : (nowNs - lastFrameNs) / 1e6f;
        lastFrameNs = nowNs;
        worstFrameMs = Math.max(worstFrameMs, dtMs);
        if (nowNs - worstFrameWindowNs > 1_000_000_000L) {
            worstFrameShownMs = worstFrameMs;
            worstFrameMs = 0f;
            worstFrameWindowNs = nowNs;
        }
        int tau = prefs.getInt(Prefs.SMOOTH_MS, 20);
        if (Float.isNaN(shownAngle) || Float.isNaN(target) || tau <= 0 || !Float.isNaN(previewAngle)) {
            shownAngle = target;
        } else {
            shownAngle += (target - shownAngle) * (1f - (float) Math.exp(-dtMs / tau));
            if (Math.abs(target - shownAngle) < 0.05f) shownAngle = target;
        }

        boolean cover = isCover();
        boolean enabled = prefs.getBoolean(Prefs.ENABLED, true) || !Float.isNaN(previewAngle);
        view.fx.compute(cover, enabled ? shownAngle : Float.NaN, config);
        view.config = config;
        if (view.fx.strength > 0f && nowNs - lastLogNs > 100_000_000L) {
            lastLogNs = nowNs;
            Log.d(TAG, String.format("frame angle=%.1f shown=%.1f v=%.0f/s %s strength=%.2f",
                    rawAngle, shownAngle, velocity, cover ? "cover" : "inner", view.fx.strength));
        }
        view.cover = cover;
        view.hingeRight = prefs.getBoolean(Prefs.HINGE_RIGHT, false);
        view.hud = prefs.getBoolean(Prefs.HUD, false);
        Display d = view.getDisplay();
        view.rotation = d == null ? 0 : d.getRotation();

        // Live capture: the left page on the inner panel (to angle it), the whole cover screen (to blur it).
        boolean warp = prefs.getBoolean(Prefs.WARP, true);
        boolean capturing = (warp || cover) && view.wantsPage() && view.ready() && capture != null && capture.ready();
        Rect region = OverlaySurface.captureRegion(cover, view.rotation, view.getWidth(), view.getHeight(), config.hingeBlend);
        if (capture != null) capture.setActive(capturing, view.ready() ? view.getSurfaceControl() : null, region,
                prefs.getInt(Prefs.CAPTURE_SCALE, 40) / 100f);
        if (!capturing) dropPage();

        if (view.hud) {
            view.hudText = String.format("angle %.1f (shown %.1f)  %.0f°/s%nslowest frame %.0f ms (8 = smooth 120 Hz)%npanel %s  strength %.2f%nblur %dpx%s%nswitch close %.1f / open %.1f%n%s",
                    rawAngle, shownAngle, velocity, worstFrameShownMs, cover ? "COVER" : "INNER", view.fx.strength,
                    Math.round(view.fx.blurPx), blurSupported ? "" : " (blur unsupported)",
                    prefs.getFloat(Prefs.CLOSE_SWITCH, Float.NaN), prefs.getFloat(Prefs.OPEN_SWITCH, Float.NaN),
                    capture == null ? "" : capture.status);
        }
        // With a live page the blur is applied to the captured image itself, so the window blur is off.
        // Otherwise round the radius: every change relayouts the blur window, which can stutter.
        // Until the first capture arrives (right after the screens switch) the window blur stands
        // in, so the left half never flashes unwarped and sharp.
        int windowBlur = blurSupported && (!capturing || view.page == null) ? Math.round(view.fx.blurPx / 4f) * 4 : 0;
        applyBlur(windowBlur, cover, view.rotation);
        view.render();

        if (shownAngle != target || view.fx.strength > 0f || view.hud) kick();
    }

    /** Main thread: the last hinge sample, moved forward by its velocity to the present. */
    private float predictedAngle() {
        float angle = rawAngle;
        if (Float.isNaN(angle)) return angle;
        long age = SystemClock.elapsedRealtimeNanos() - sampleNs;
        if (age > 0 && age <= MAX_PREDICT_NS) angle += velocity * age / 1e9f;
        return Math.max(0f, Math.min(180f, angle));
    }

    @Override
    public void onFrame(HardwareBuffer frame, Rect crop) {
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.wrapHardwareBuffer(frame, ColorSpace.get(ColorSpace.Named.SRGB));
        } catch (RuntimeException e) {
            Log.w(TAG, "wrapHardwareBuffer", e);
        } finally {
            frame.close(); // the bitmap keeps its own reference
        }
        if (bitmap == null || view == null || !view.wantsPage()) {
            if (bitmap != null) bitmap.recycle();
            return;
        }
        if (previousPage != null) previousPage.recycle();
        previousPage = view.page;
        view.page = bitmap;
        view.pageCrop.set(crop);
        kick();
    }

    private void dropPage() {
        if (view.page == null && previousPage == null) return;
        if (previousPage != null) previousPage.recycle();
        previousPage = view.page;
        view.page = null;
    }

    private void applyBlur(int px, boolean cover, int rotation) {
        // Inner panel: blur only the left page (the half left of the hinge in the book-style
        // orientation, mapped through the current rotation). Cover panel: whole screen.
        int gravity = Gravity.TOP | Gravity.LEFT, w = WindowManager.LayoutParams.MATCH_PARENT, h = w;
        if (!cover && prefs.getBoolean(Prefs.BLUR_LEFT_ONLY, true)) {
            Point size = new Point();
            Display d = displays.getDisplay(Display.DEFAULT_DISPLAY);
            if (d != null) d.getRealSize(size);
            switch (OverlaySurface.rotationSteps(rotation)) {
                case 1: gravity = Gravity.BOTTOM | Gravity.LEFT; h = size.y / 2; break;
                case 2: gravity = Gravity.TOP | Gravity.RIGHT; w = size.x / 2; break;
                case 3: h = size.y / 2; break;
                default: w = size.x / 2; break;
            }
        }
        if (px == appliedBlur && gravity == appliedGravity && w == appliedW && h == appliedH) return;
        appliedBlur = px;
        appliedGravity = gravity;
        appliedW = w;
        appliedH = h;
        blurLp.gravity = gravity;
        blurLp.width = w;
        blurLp.height = h;
        if (px > 0) blurLp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        else blurLp.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        blurLp.setBlurBehindRadius(px);
        try {
            wm.updateViewLayout(blurView, blurLp);
        } catch (RuntimeException e) {
            Log.w(TAG, "updateViewLayout", e);
        }
    }

    /** The cover panel is tall and narrow; the inner panel is nearly square. */
    private boolean isCover() {
        Display d = displays.getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null) return false;
        Point p = new Point();
        d.getRealSize(p);
        return Math.min(p.x, p.y) / (float) Math.max(p.x, p.y) < 0.6f;
    }

    /** Sensor thread. Event timestamps share the elapsedRealtimeNanos clock. */
    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.values.length == 0) return;
        float angle = e.values[0];
        float previous = rawAngle;
        long dt = e.timestamp - sampleNs;
        if (!Float.isNaN(previous) && dt > 0 && dt < 200_000_000L) {
            float instant = (angle - previous) * 1e9f / dt;
            velocity = velocity * 0.5f + instant * 0.5f;
        } else {
            velocity = 0f;
        }
        sampleNs = e.timestamp;
        rawAngle = angle;
        events++;
        main.post(kickOnMain);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onDisplayChanged(int id) {
        if (id != Display.DEFAULT_DISPLAY) return;
        boolean cover = isCover();
        if (cover != lastCover) {
            // ColorOS just swapped panels: remember the hinge angle where that happens.
            if (!Float.isNaN(rawAngle) && rawAngle > 5f && rawAngle < 175f) {
                prefs.edit().putFloat(cover ? Prefs.CLOSE_SWITCH : Prefs.OPEN_SWITCH, rawAngle).apply();
            }
            Log.i(TAG, "panel switch -> " + (cover ? "cover" : "inner") + " at angle " + rawAngle);
            lastCover = cover;
        }
        kick();
    }

    @Override public void onDisplayAdded(int id) {}
    @Override public void onDisplayRemoved(int id) {}

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        config = Prefs.config(p);
        kick();
    }

    String debug() {
        return String.format("%s%nangle %.1f, %d sensor events%npanel %s, blur %s%n%s",
                status, rawAngle, events, isCover() ? "cover" : "inner",
                blurSupported ? "supported" : "NOT supported (check battery saver / developer options)",
                capture == null ? "" : capture.status);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        status = "Service not running";
        if (capture != null) capture.close();
        capture = null;
        if (sensors != null) sensors.unregisterListener(this);
        sensorThread.quitSafely();
        if (displays != null) displays.unregisterDisplayListener(this);
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (root != null) {
            try { wm.removeView(root); } catch (RuntimeException ignored) {}
            root = null;
            view = null;
        }
        if (blurView != null) {
            try { wm.removeView(blurView); } catch (RuntimeException ignored) {}
            blurView = null;
        }
        super.onDestroy();
    }
}
