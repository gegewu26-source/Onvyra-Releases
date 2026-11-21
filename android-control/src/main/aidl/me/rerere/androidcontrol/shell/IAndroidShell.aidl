package me.rerere.androidcontrol.shell;

import android.view.Surface;

/** Private capability Binder. Every transaction is restricted to the pinned app UID. */
interface IAndroidShell {
    int createDisplay(in Surface surface, int width, int height, int dpi);
    void releaseDisplay(int displayId);
    // 0 = launched; 1 = user is using app on main; 2 = no launcher; 3 = error; 7 = other agent display.
    int startApp(int displayId, String packageName);
    String topActivity(int displayId);
    String topPackage(int displayId);
    boolean injectTap(int displayId, int x, int y);
    boolean injectKey(int displayId, int keyCode);
    boolean injectStroke(int displayId, in int[] xs, in int[] ys, in long[] times);
    // Same as injectStroke but a rotated display keeps per-point MotionEvent injection
    // instead of degrading to a linear `input swipe` fallback. Used by solve_slider.
    boolean injectStrokeRotated(int displayId, in int[] xs, in int[] ys, in long[] times);
    int[] identity();
    void releaseAll();
    // Only an explicit user takeover action may call this; never expose as an agent tool.
    boolean returnToMainDisplay(int displayId);
    // Interrupts current input immediately; this display then accepts no further input.
    void cancelDisplayInput(int displayId);
    void destroy();
    // Preview only: null restores the original drain surface; never accepts a main display.
    boolean setPreviewSurface(int displayId, in Surface surface);
    // One display-scoped finger held across observation calls. 0 means no DOWN was accepted.
    long beginDrag(int displayId, int x, int y);
    // Moves the same finger without UP; only the token returned by beginDrag is accepted.
    boolean moveDrag(int displayId, long dragId, int x, int y, int durationMs);
    // cancel=true emits CANCEL; false emits UP. Stale tokens never affect a later drag.
    boolean endDrag(int displayId, long dragId, boolean cancel);
}
