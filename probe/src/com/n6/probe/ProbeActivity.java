package com.n6.probe;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shows every hinge/fold sensor live so we can see which one reports a smooth angle. */
public class ProbeActivity extends Activity implements SensorEventListener, DisplayManager.DisplayListener {
    static final String TAG = "N6PROBE";
    static final int TYPE_HINGE_ANGLE = 36;
    static final int QTI_HINGE_DETECT = 33171038;
    static final int QTI_FOLD_STATE = 33171063;

    static final class Stat {
        final Sensor sensor;
        float[] last = new float[0];
        long count, windowCount, windowStart = SystemClock.elapsedRealtime();
        float hz;
        final Set<String> distinctFirst = new HashSet<>();
        Stat(Sensor s) { sensor = s; }
    }

    private SensorManager sensors;
    private DisplayManager displays;
    private final Map<Sensor, Stat> stats = new LinkedHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView text;
    private String sensorList = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        text = new TextView(this);
        text.setTextSize(12);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad * 3, pad, pad * 3);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);

        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        displays = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);

        StringBuilder sb = new StringBuilder();
        for (Sensor s : sensors.getSensorList(Sensor.TYPE_ALL)) {
            String n = (s.getName() + " " + s.getStringType()).toLowerCase();
            boolean interesting = s.getType() == TYPE_HINGE_ANGLE || s.getType() == QTI_HINGE_DETECT
                    || s.getType() == QTI_FOLD_STATE || n.contains("hinge") || n.contains("fold")
                    || n.contains("angle") || n.contains("hall") || n.contains("posture");
            if (!interesting) continue;
            String line = "type=" + s.getType() + " " + s.getStringType() + " | " + s.getName()
                    + " | res=" + s.getResolution() + " max=" + s.getMaximumRange()
                    + " minDelay=" + s.getMinDelay() + "us wake=" + s.isWakeUpSensor();
            sb.append(line).append('\n');
            Log.i(TAG, "SENSOR " + line);
            if (!stats.containsKey(s)) stats.put(s, new Stat(s));
        }
        sensorList = sb.toString();
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (Stat st : stats.values()) {
            boolean ok = sensors.registerListener(this, st.sensor, SensorManager.SENSOR_DELAY_FASTEST);
            Log.i(TAG, "REGISTER " + st.sensor.getName() + " ok=" + ok);
        }
        displays.registerDisplayListener(this, main);
        logDisplays("resume");
        main.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensors.unregisterListener(this);
        displays.unregisterDisplayListener(this);
        main.removeCallbacks(refresh);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        Stat st = stats.get(e.sensor);
        if (st == null) return;
        st.last = e.values.clone();
        st.count++;
        st.windowCount++;
        if (e.values.length > 0 && st.distinctFirst.size() < 1000) st.distinctFirst.add(String.valueOf(e.values[0]));
        long now = SystemClock.elapsedRealtime();
        if (now - st.windowStart >= 1000) {
            st.hz = st.windowCount * 1000f / (now - st.windowStart);
            st.windowCount = 0;
            st.windowStart = now;
        }
        float[] head = Arrays.copyOf(e.values, Math.min(e.values.length, 6));
        Log.i(TAG, "EV t=" + (e.timestamp / 1000000L) + " type=" + e.sensor.getType() + " " + Arrays.toString(head));
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    @Override public void onDisplayAdded(int id) { logDisplays("added " + id); }
    @Override public void onDisplayRemoved(int id) { logDisplays("removed " + id); }
    @Override public void onDisplayChanged(int id) { logDisplays("changed " + id); }

    private String displayReport() {
        List<String> lines = new ArrayList<>();
        for (Display d : displays.getDisplays("android.hardware.display.category.ALL_INCLUDING_DISABLED")) {
            lines.add(describe(d));
        }
        if (lines.isEmpty()) for (Display d : displays.getDisplays()) lines.add(describe(d));
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    private static String describe(Display d) {
        android.graphics.Point p = new android.graphics.Point();
        d.getRealSize(p);
        return "D" + d.getDisplayId() + " " + p.x + "x" + p.y + " state=" + d.getState()
                + " rot=" + d.getRotation() + " flags=0x" + Integer.toHexString(d.getFlags());
    }

    private void logDisplays(String why) {
        Log.i(TAG, "DISPLAYS (" + why + ")\n" + displayReport());
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            StringBuilder sb = new StringBuilder();
            sb.append("N6 HINGE PROBE\nFold slowly. Watch which row changes smoothly.\n\n");
            for (Stat st : stats.values()) {
                sb.append("[").append(st.sensor.getType()).append("] ").append(st.sensor.getName()).append('\n');
                sb.append("  values: ").append(Arrays.toString(Arrays.copyOf(st.last, Math.min(st.last.length, 6)))).append('\n');
                sb.append(String.format("  events=%d  rate=%.1f Hz  distinct v0=%d%n%n", st.count, st.hz, st.distinctFirst.size()));
            }
            sb.append("DISPLAYS\n").append(displayReport()).append('\n');
            sb.append("SENSORS FOUND\n").append(sensorList);
            text.setText(sb);
            main.postDelayed(this, 100);
        }
    };
}
