package com.n6.foldduo;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceControl;

import rikka.shizuku.Shizuku;

/**
 * App side of the Shizuku helper: binds {@link ShellService} and, while active, captures
 * display 0 in a loop and hands each frame to the main thread.
 */
final class CaptureClient {
    interface FrameListener { void onFrame(HardwareBuffer frame, Rect crop); }

    static final int PERMISSION_REQUEST = 7341;
    /** By name, so the app process never loads ShellService (it uses hidden shell-only APIs). */
    private static final String SHELL_SERVICE_CLASS = "com.n6.foldduo.ShellService";
    private static final String TAG = "N6DUO";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("n6-capture");
    private final Handler worker;
    private final FrameListener listener;
    private final Shizuku.UserServiceArgs args;

    private volatile IBinder shell;
    private volatile boolean active;
    private volatile SurfaceControl exclude;
    /** Screen region to capture (replaced, never mutated, so the worker sees a consistent rect). */
    private volatile Rect crop;
    private volatile float scale = 0.4f;
    private boolean bound, looping;
    volatile String status = "Shizuku: not connected";
    volatile float fps;
    private int framesThisSecond;
    private long secondStart;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            shell = binder;
            status = "Shizuku: connected";
            Log.i(TAG, "shell service connected");
            kickLoop();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            shell = null;
            bound = false;
            status = "Shizuku: helper disconnected";
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceived = new Shizuku.OnBinderReceivedListener() {
        @Override public void onBinderReceived() { connect(); }
    };
    private final Shizuku.OnBinderDeadListener binderDead = new Shizuku.OnBinderDeadListener() {
        @Override public void onBinderDead() {
            shell = null;
            bound = false;
            status = "Shizuku: stopped (restart it in the Shizuku app)";
        }
    };
    private final Shizuku.OnRequestPermissionResultListener permissionResult = new Shizuku.OnRequestPermissionResultListener() {
        @Override public void onRequestPermissionResult(int requestCode, int grantResult) { connect(); }
    };

    CaptureClient(Context context, FrameListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        thread.start();
        worker = new Handler(thread.getLooper());
        args = new Shizuku.UserServiceArgs(new ComponentName(this.context.getPackageName(), SHELL_SERVICE_CLASS))
                // Bump when the ShellService protocol changes so Shizuku restarts the helper.
                .daemon(false).processNameSuffix("shell").version(4);
        Shizuku.addBinderReceivedListenerSticky(binderReceived, main);
        Shizuku.addBinderDeadListener(binderDead, main);
        Shizuku.addRequestPermissionResultListener(permissionResult, main);
    }

    static boolean shizukuRunning() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    static boolean permitted() {
        try { return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; } catch (Throwable t) { return false; }
    }

    /** Bind the helper if Shizuku is running and we are authorized. Main thread. */
    void connect() {
        if (bound) return;
        if (!shizukuRunning()) {
            status = "Shizuku: not running";
            return;
        }
        if (!permitted()) {
            status = "Shizuku: not authorized (tap Authorize Shizuku in the app)";
            return;
        }
        try {
            Shizuku.bindUserService(args, connection);
            bound = true;
            status = "Shizuku: starting helper…";
        } catch (Throwable t) {
            status = "Shizuku: bind failed: " + t.getMessage();
        }
    }

    boolean ready() { return shell != null; }

    /**
     * Called every frame from the main thread with the surface to leave out of captures, the
     * screen region to capture and the capture scale (smaller is faster).
     */
    void setActive(boolean on, SurfaceControl overlay, Rect region, float captureScale) {
        exclude = overlay;
        if (region != null && !region.equals(crop)) crop = new Rect(region);
        scale = captureScale;
        if (on == active) return;
        active = on;
        if (on) kickLoop();
    }

    private void kickLoop() {
        worker.post(new Runnable() {
            @Override public void run() { loop(); }
        });
    }

    /** Worker thread: capture back-to-back while active. */
    private void loop() {
        if (looping) return;
        looping = true;
        try {
            while (active) {
                IBinder b = shell;
                SurfaceControl sc = exclude;
                final Rect r = crop;
                if (b == null || sc == null || !sc.isValid() || r == null || r.isEmpty()) {
                    SystemClock.sleep(8);
                    if (b == null) return;
                    continue;
                }
                HardwareBuffer frame = captureOnce(b, sc, r);
                if (frame != null) {
                    countFrame();
                    final HardwareBuffer f = frame;
                    main.post(new Runnable() {
                        @Override public void run() { listener.onFrame(f, r); }
                    });
                } else {
                    SystemClock.sleep(16);
                }
            }
        } finally {
            looping = false;
        }
    }

    private HardwareBuffer captureOnce(IBinder b, SurfaceControl sc, Rect r) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.n6.foldduo.ShellService");
            data.writeTypedObject(sc, 0);
            data.writeInt(r.left);
            data.writeInt(r.top);
            data.writeInt(r.right);
            data.writeInt(r.bottom);
            data.writeFloat(scale);
            b.transact(1 /* ShellService.CAPTURE */, data, reply, 0);
            reply.readException();
            if (reply.readInt() == 1) {
                status = String.format("Shizuku: capturing (%.0f fps)", fps);
                return reply.readTypedObject(HardwareBuffer.CREATOR);
            }
            status = "Shizuku: capture error: " + reply.readString();
        } catch (Throwable t) {
            status = "Shizuku: capture failed: " + t.getMessage();
            Log.w(TAG, "capture", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
        return null;
    }

    private void countFrame() {
        long now = SystemClock.elapsedRealtime();
        framesThisSecond++;
        if (now - secondStart >= 1000) {
            fps = framesThisSecond * 1000f / (now - secondStart);
            framesThisSecond = 0;
            secondStart = now;
        }
    }

    void close() {
        active = false;
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        if (bound) {
            try { Shizuku.unbindUserService(args, connection, true); } catch (Throwable ignored) {}
            bound = false;
        }
        shell = null;
        thread.quitSafely();
    }
}
