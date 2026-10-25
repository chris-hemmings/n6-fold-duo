package com.n6.foldduo;

import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.IWindowManager;
import android.view.SurfaceControl;
import android.window.ScreenCapture;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.ObjIntConsumer;

/**
 * Runs as the shell user inside a Shizuku user-service process. Captures logical display 0 while
 * leaving out the app's own overlay surface, so the overlay never captures itself.
 * Only the app that bound it may call it.
 */
public class ShellService extends Binder {
    static final String DESCRIPTOR = "com.n6.foldduo.ShellService";
    static final int CAPTURE = 1, PING = 2;
    /** Shizuku's user-service "destroy" transaction. */
    static final int SHIZUKU_DESTROY = 16777115;

    private int owner = -1;
    private IWindowManager wm;
    /** Native code holds the capture listener weakly; these fields keep it alive until the frame arrives. */
    private Object pendingCallback, pendingListener;

    public ShellService() {
        attachInterface(null, DESCRIPTOR);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(DESCRIPTOR);
            return true;
        }
        if (code == SHIZUKU_DESTROY) {
            System.exit(0);
            return true;
        }
        data.enforceInterface(DESCRIPTOR);
        int caller = Binder.getCallingUid();
        synchronized (this) {
            if (owner < 0) owner = caller;
        }
        if (caller != owner) throw new SecurityException("Wrong caller");
        switch (code) {
            case PING:
                reply.writeNoException();
                reply.writeInt(android.os.Process.myUid());
                return true;
            case CAPTURE:
                capture(data, reply);
                return true;
            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    /**
     * In: SurfaceControl exclude, int left, top, right, bottom (screen region), float scale.
     * Out: int ok; then HardwareBuffer (ok) or String error.
     */
    private void capture(Parcel data, Parcel reply) {
        SurfaceControl exclude = data.readTypedObject(SurfaceControl.CREATOR);
        Rect crop = new Rect(data.readInt(), data.readInt(), data.readInt(), data.readInt());
        float scale = data.readFloat();
        long identity = Binder.clearCallingIdentity();
        HardwareBuffer buffer = null;
        try {
            if (exclude == null || !exclude.isValid()) throw new IllegalStateException("Overlay surface not ready");
            if (wm == null) {
                IBinder b = ServiceManager.getService("window");
                wm = IWindowManager.Stub.asInterface(b);
            }
            @SuppressWarnings({"rawtypes"})
            ScreenCapture.CaptureArgs.Builder builder = new ScreenCapture.CaptureArgs.Builder();
            builder.setSourceCrop(crop);
            builder.setFrameScale(scale);
            builder.setExcludeLayers(new SurfaceControl[]{exclude});
            ScreenCapture.CaptureArgs args = builder.build();
            final ScreenCapture.ScreenshotHardwareBuffer[] out = new ScreenCapture.ScreenshotHardwareBuffer[1];
            final CountDownLatch done = new CountDownLatch(1);
            ObjIntConsumer<ScreenCapture.ScreenshotHardwareBuffer> callback =
                    new ObjIntConsumer<ScreenCapture.ScreenshotHardwareBuffer>() {
                        @Override public void accept(ScreenCapture.ScreenshotHardwareBuffer shot, int status) {
                            out[0] = shot;
                            done.countDown();
                        }
                    };
            ScreenCapture.ScreenCaptureListener listener = new ScreenCapture.ScreenCaptureListener(callback);
            pendingCallback = callback;
            pendingListener = listener;
            try {
                wm.captureDisplay(0, args, listener);
                if (!done.await(250, TimeUnit.MILLISECONDS)) throw new IllegalStateException("Capture timed out");
            } finally {
                pendingCallback = null;
                pendingListener = null;
            }
            if (out[0] == null || out[0].getHardwareBuffer() == null) throw new IllegalStateException("No frame");
            buffer = out[0].getHardwareBuffer();
            reply.writeNoException();
            reply.writeInt(1);
            reply.writeTypedObject(buffer, 0);
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null) cause = cause.getCause();
            reply.writeNoException();
            reply.writeInt(0);
            reply.writeString(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } finally {
            // The parcel holds its own handle to the buffer, so ours can be dropped now.
            if (buffer != null) buffer.close();
            if (exclude != null) exclude.release();
            Binder.restoreCallingIdentity(identity);
        }
    }
}
