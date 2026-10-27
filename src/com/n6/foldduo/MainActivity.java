package com.n6.foldduo;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Settings, setup instructions and a live preview slider. Built in code to avoid layout XML. */
public class MainActivity extends Activity {
    static final String ADB_ENABLE =
            "adb shell settings put secure enabled_accessibility_services com.n6.foldduo/.FoldService\n"
                    + "adb shell settings put secure accessibility_enabled 1";

    private SharedPreferences prefs;
    private LinearLayout root;
    private TextView status, learned;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int pad;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        pad = (int) (16 * getResources().getDisplayMetrics().density);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 3, pad, pad * 3);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        build();
    }

    private void build() {
        root.removeAllViews();
        heading("N6 Fold Duo");
        status = text("");

        heading("Setup");
        text("Turn on the accessibility service. From your PC, run:");
        TextView cmd = text(ADB_ENABLE);
        cmd.setTextIsSelectable(true);
        cmd.setTypeface(android.graphics.Typeface.MONOSPACE);
        Button a11y = button("Open accessibility settings");
        a11y.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        });

        heading("Shizuku (for the angled left page)");
        text("Angling the left page needs a live picture of the screen, which needs Shizuku. "
                + "Install Shizuku, start it with Wireless debugging, then tap Authorize. "
                + "Shizuku has to be started again after every reboot.");
        Button shizuku = button("Authorize Shizuku");
        shizuku.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!CaptureClient.shizukuRunning()) {
                    toast("Shizuku isn't running. Open the Shizuku app and start it first.");
                } else if (!CaptureClient.permitted()) {
                    rikka.shizuku.Shizuku.requestPermission(CaptureClient.PERMISSION_REQUEST);
                } else {
                    FoldService s = FoldService.instance;
                    if (s != null && s.capture != null) s.capture.connect();
                    toast("Shizuku is already authorized.");
                }
            }
        });

        heading("Look");
        Button duo = button("Match iPhone Duo");
        duo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.applyIphoneDuo(prefs);
                build();
                toast("iPhone Duo look applied: blurred, shaded left page with no on-screen warp.");
            }
        });

        heading("Window projection");
        text("Shows the left page as a window you open onto the flat screen behind it, using the real "
                + "hinge angle. Replaces the trapezoid and width settings below while on.");
        check("Window projection", Prefs.WINDOW, false);
        slider("Viewing distance", Prefs.VIEW_DISTANCE, 15, 80, 35, " cm");
        slider("Sideways stretch", Prefs.WINDOW_STRETCH, 0, 100, 0, "%");
        slider("Eye position right of the hinge (affects stretch)", Prefs.EYE_OFFSET, 0, 100, 50, "% of page width");

        heading("Left page fold");
        check("Angle the left page while folding (needs Shizuku)", Prefs.WARP, true);
        slider("Outer edge shorter by", Prefs.TAPER, 0, 90, 40, "%");
        slider("Left page width (from the middle)", Prefs.PAGE_WIDTH, 30, 100, 100, "%");
        slider("Corner rounding", Prefs.CORNER_RADIUS, 0, 800, 250, "px");
        slider("Edge softness", Prefs.EDGE_SOFTNESS, 0, 150, 40, "px");
        slider("Blur past the hinge", Prefs.HINGE_BLEND, 0, 15, 0, "% of width");
        slider("Left page shade (darkens toward the outer edge)", Prefs.SHADE, 0, 100, 0, "%");

        heading("Effect");
        check("Animation enabled", Prefs.ENABLED, true);
        check("Also animate the outer (cover) screen", Prefs.COVER_EFFECT, false);
        slider("Blur", Prefs.BLUR, 0, 300, 60, "px");
        slider("Blur fade width (from the hinge)", Prefs.BLUR_FADE, 5, 100, 100, "% of page");
        slider("Unfolding: clear blur when held still above", Prefs.HOLD_CLEAR_ANGLE, 90, 171, 150, "°");
        slider("Held still for", Prefs.HOLD_CLEAR_MS, 100, 2000, 500, " ms");
        slider("Blur fade-out when held", Prefs.BLUR_FADE_OUT_MS, 50, 1500, 400, " ms");
        slider("Movement to bring the blur back", Prefs.REARM_DEG, 2, 30, 6, "°");
        slider("Blur fade shape (lower = stays heavy longer)", Prefs.BLUR_CURVE, 20, 300, 150, "%");
        slider("Vertical blur (lower = sideways smear)", Prefs.BLUR_VERTICAL, 5, 100, 100, "%");
        slider("Shade build-up (higher = only near the switch)", Prefs.SHADE_CURVE, 20, 300, 100, "%");
        check("Blur only the left half of the inner screen", Prefs.BLUR_LEFT_ONLY, true);
        slider("Hinge shadow", Prefs.SHADOW, 0, 100, 60, "%");
        slider("Glass sheen", Prefs.GLASS, 0, 200, 100, "%");
        slider("Motion smoothing", Prefs.SMOOTH_MS, 0, 200, 20, " ms");
        slider("Capture resolution (lower is faster)", Prefs.CAPTURE_SCALE, 20, 100, 40, "%");

        heading("Hinge");
        slider("Fully open at", Prefs.OPEN, 150, 180, 172, "°");
        slider("Fully closed at", Prefs.CLOSED, 0, 15, 2, "°");
        check("Cover hinge is on the right edge", Prefs.HINGE_RIGHT, false);
        learned = text("");
        Button forget = button("Re-learn screen switch angles");
        forget.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { prefs.edit().remove(Prefs.CLOSE_SWITCH).remove(Prefs.OPEN_SWITCH).apply(); }
        });

        heading("Preview");
        text("Drag to see the effect on this screen at a given hinge angle. Release to go back to live.");
        final TextView previewLabel = text("Angle: live");
        SeekBar preview = new SeekBar(this);
        preview.setMax(180);
        preview.setProgress(90);
        preview.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (!user) return;
                FoldService.previewAngle = v;
                previewLabel.setText("Angle: " + v + "°");
                kickService();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                FoldService.previewAngle = s.getProgress();
                kickService();
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                FoldService.previewAngle = Float.NaN;
                previewLabel.setText("Angle: live");
                kickService();
            }
        });
        root.addView(preview);

        heading("Debug");
        check("Show angle readout on screen", Prefs.HUD, false);
        Button reset = button("Reset all settings");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().clear().apply();
                build();
            }
        });
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
    }

    private static void kickService() {
        FoldService s = FoldService.instance;
        if (s != null) s.kick();
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            FoldService s = FoldService.instance;
            status.setText(s == null
                    ? "Service is OFF. Enable it with the adb commands below, or in Accessibility settings."
                    : s.debug());
            float close = prefs.getFloat(Prefs.CLOSE_SWITCH, Float.NaN), open = prefs.getFloat(Prefs.OPEN_SWITCH, Float.NaN);
            learned.setText("Screen switch measured at: closing "
                    + (Float.isNaN(close) ? "not yet (fold once)" : String.format("%.1f°", close))
                    + ", opening " + (Float.isNaN(open) ? "not yet (unfold once)" : String.format("%.1f°", open)));
            main.postDelayed(this, 300);
        }
    };

    @Override protected void onResume() { super.onResume(); main.post(refresh); }
    @Override protected void onPause() { super.onPause(); main.removeCallbacks(refresh); }

    private TextView heading(String s) {
        TextView t = text(s);
        t.setTextSize(20);
        t.setPadding(0, pad, 0, pad / 2);
        return t;
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        root.addView(t);
        return t;
    }

    private Button button(String s) {
        Button btn = new Button(this);
        btn.setText(s);
        root.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return btn;
    }

    private void check(String label, final String key, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(prefs.getBoolean(key, def));
        c.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton v, boolean on) { prefs.edit().putBoolean(key, on).apply(); }
        });
        root.addView(c);
    }

    /** SeekBar has no min below API 26, so the value is offset by {@code min}. */
    private void slider(final String label, final String key, final int min, int max, int def, final String unit) {
        final TextView t = text("");
        SeekBar s = new SeekBar(this);
        s.setMax(max - min);
        int value = prefs.getInt(key, def);
        s.setProgress(value - min);
        t.setText(label + ": " + value + unit);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int v, boolean user) {
                t.setText(label + ": " + (v + min) + unit);
                if (user) prefs.edit().putInt(key, v + min).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        root.addView(s);
        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, pad / 2));
    }
}
