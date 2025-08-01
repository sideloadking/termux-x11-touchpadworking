// Path: app/src/main/java/com/termux/x11/input/TouchInputHandler.java
// Copyright 2013 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package com.termux.x11.input;

import static android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC;
import static android.view.KeyEvent.*;
import static android.view.WindowManager.LayoutParams.*;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PointF;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.math.MathUtils;
import androidx.viewpager.widget.ViewPager;

import com.termux.x11.LoriePreferences;
import com.termux.x11.LorieView;
import com.termux.x11.MainActivity;
import com.termux.x11.Prefs;
import com.termux.x11.R;
import com.termux.x11.utils.SamsungDexUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * This class is responsible for handling Touch input from the user.  Touch events which manipulate
 * the local canvas are handled in this class and any input which should be sent to the remote host
 * are passed to the InputStrategyInterface implementation set by the DesktopView.
 */
public class TouchInputHandler {
    private static final float EPSILON = 0.001f;
    public static int STYLUS_INPUT_HELPER_MODE = 1; // 1 = Left Click, 2 Middle Click, 4 Right Click

    /** Used to set/store the selected input mode. */
    @SuppressWarnings("unused")
    @IntDef({InputMode.TRACKPAD, InputMode.SIMULATED_TOUCH, InputMode.TOUCH})
    @Retention(RetentionPolicy.SOURCE)
    public @interface InputMode {
        // Values are starting from 0 and don't have gaps.
        int TRACKPAD = 1;
        int SIMULATED_TOUCH = 2;
        int TOUCH = 3;
    }

    @IntDef({CapturedPointerTransformation.AUTO, CapturedPointerTransformation.NONE, CapturedPointerTransformation.COUNTER_CLOCKWISE, CapturedPointerTransformation.UPSIDE_DOWN, CapturedPointerTransformation.CLOCKWISE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CapturedPointerTransformation {
        // values correspond to transformation needed given getRotation(), e.g. getRotation() = 1 requires counter-clockwise transformation
        int AUTO = -1;
        int NONE = 0;
        int COUNTER_CLOCKWISE = 1;
        int UPSIDE_DOWN = 2;
        int CLOCKWISE = 3;
    }

    private final RenderData mRenderData;
    private final GestureDetector mScroller;
    private final TapGestureDetector mTapDetector;
    private final StylusListener mStylusListener = new StylusListener();
    private final HardwareMouseListener mHMListener = new HardwareMouseListener();
    private final DexListener mDexListener;
    private final TouchInputHandler mTouchpadHandler;

    /** Used to disambiguate a 2-finger gesture as a swipe or a pinch. */
    private final SwipeDetector mSwipePinchDetector;

    private InputStrategyInterface mInputStrategy;
    private final InputEventSender mInjector;
    private final MainActivity mActivity;
    private final DisplayMetrics mMetrics = new DisplayMetrics();
    private final BiConsumer<Integer, Boolean> noAction = (key, down) -> {};
    private BiConsumer<Integer, Boolean> swipeUpAction = noAction, swipeDownAction = noAction,
            volumeUpAction = noAction, volumeDownAction = noAction, backButtonAction = noAction,
            mediaKeysAction = noAction;
    private static final int KEY_BACK = 158;
    private boolean keyIntercepting = false;

    /**
     * Used for tracking swipe gestures. Only the Y-direction is needed for responding to swipe-up
     * or swipe-down.
     */
    private float mTotalMotionY;

    /**
     * Distance in pixels beyond which a motion gesture is considered to be a swipe. This is
     * initialized using the Context passed into the constructor.
     */
    private final float mSwipeThreshold;

    /**
     * Set to true to prevent any further movement of the cursor, for example, when showing the
     * keyboard to prevent the cursor wandering from the area where keystrokes should be sent.
     */
    private boolean mSuppressCursorMovement;

    /**
     * Set to true when 3-finger swipe gesture is complete, so that further movement doesn't
     * trigger more swipe actions.
     */
    private boolean mSwipeCompleted;

    /**
     * Set to true when a 1 finger pan gesture originates with a long-press.  This means the user
     * is performing a drag operation.
     */
    private boolean mIsDragging;

    private static DisplayManager mDisplayManager;
    private static int mDisplayRotation;
    private static final DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            mDisplayRotation = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY).getRotation() % 4;
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            mDisplayRotation = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY).getRotation() % 4;
        }

        @Override
        public void onDisplayChanged(int displayId) {
            mDisplayRotation = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY).getRotation() % 4;
        }
    };

    @CapturedPointerTransformation static int capturedPointerTransformation = CapturedPointerTransformation.NONE;

    private final int[][] buttons = {
            {MotionEvent.BUTTON_PRIMARY, InputStub.BUTTON_LEFT},
            {MotionEvent.BUTTON_TERTIARY, InputStub.BUTTON_MIDDLE},
            {MotionEvent.BUTTON_SECONDARY, InputStub.BUTTON_RIGHT}
    };
    private int savedBS = 0;
    private int currentBS = 0;
    boolean isMouseButtonChanged(int mask) {
        return (savedBS & mask) != (currentBS & mask);
    }
    boolean mouseButtonDown(int mask) {
        return ((currentBS & mask) != 0);
    }

    // START MODIFICATION: Variables to track scrolling state for trackpads
    private float mLastScrollX;
    private float mLastScrollY;
    private boolean mIsTrackpadScroll;
    // END MODIFICATION

    private TouchInputHandler(MainActivity activity, RenderData renderData,
                              final InputEventSender injector, boolean isTouchpad) {
        if (injector == null)
            throw new NullPointerException();
        mRenderData = renderData != null ? renderData :new RenderData();
        mInjector = injector;
        mActivity = activity;
        if (mDisplayManager == null) {
            mDisplayManager = (DisplayManager) mActivity.getSystemService(Context.DISPLAY_SERVICE);
            mDisplayRotation = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY).getRotation() % 4;
            mDisplayManager.registerDisplayListener(mDisplayListener, null);
        }
        GestureListener listener = new GestureListener();
        mScroller = new GestureDetector(/*desktop*/ activity, listener, null, false);
        mScroller.setIsLongpressEnabled(false);
        mTapDetector = new TapGestureDetector(/*desktop*/ activity, listener);
        mSwipePinchDetector = new SwipeDetector(/*desktop*/ activity);
        float density = /*desktop*/ activity.getResources().getDisplayMetrics().density;
        mSwipeThreshold = 40 * density;
        setInputMode(InputMode.TRACKPAD);
        mDexListener = new DexListener(activity);
        mTouchpadHandler = isTouchpad ? null : new TouchInputHandler(activity, mRenderData, injector, true);
        refreshInputDevices();
        ((InputManager) mActivity.getSystemService(Context.INPUT_SERVICE)).registerInputDeviceListener(new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int deviceId) {
                refreshInputDevices();
            }

            @Override
            public void onInputDeviceRemoved(int deviceId) {
                refreshInputDevices();
            }

            @Override
            public void onInputDeviceChanged(int deviceId) {
                refreshInputDevices();
            }
        }, null);
    }

    public TouchInputHandler(MainActivity activity, final InputEventSender injector) {
        this(activity, null, injector, false);
    }

    static public void refreshInputDevices() {
        AtomicBoolean stylusAvailable = new AtomicBoolean(false);
        AtomicBoolean externalKeyboardAvailable = new AtomicBoolean(false);
        Arrays.stream(InputDevice.getDeviceIds())
                .mapToObj(InputDevice::getDevice)
                .filter(Objects::nonNull)
                .forEach((device) -> {
                    if (device.supportsSource(InputDevice.SOURCE_STYLUS))
                        stylusAvailable.set(true);
                    if (device.supportsSource(InputDevice.SOURCE_KEYBOARD) && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC && isExternal(device))
                        externalKeyboardAvailable.set(true);
                });
        LorieView.requestStylusEnabled(stylusAvailable.get());
        MainActivity.getInstance().setExternalKeyboardConnected(externalKeyboardAvailable.get());
    }

    // START MODIFICATION: Add helper for detecting classified scroll gestures
    @SuppressLint({"WrongConstant", "InlinedApi"})
    private static boolean isClassifiedScrollEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_TOUCHPAD) != InputDevice.SOURCE_TOUCHPAD) {
            return false;
        }
        // On older Samsung DeX, two-finger scroll gestures are sent with this flag.
        if ((e.getFlags() & 0x14000000) != 0) {
            return true;
        }
        // On Android 10+ and standard touchpads, this classification is used.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e.getClassification() == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE) {
            return true;
        }
        return false;
    }
    // END MODIFICATION

    boolean isDexEvent(MotionEvent event) {
        return ((event.getSource() & InputDevice.SOURCE_MOUSE) ==
    InputDevice.SOURCE_MOUSE)
                && ((event.getSource() & InputDevice.SOURCE_TOUCHPAD) != InputDevice.SOURCE_TOUCHPAD)
                && (event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_FINGER);
    }

    public boolean handleTouchEvent(View view0, View view, MotionEvent event) {
        // Regular touchpads and Dex touchpad (in captured mode) send events as finger too,
        // but they should be handled as touchscreens with trackpad mode.
        if (mTouchpadHandler != null && ((event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_FINGER &&
                (event.getSource() & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) || isDexEvent(event)))
            return mTouchpadHandler.handleTouchEvent(view0, view, event);

        if (view0 != view) {
            int[] view0Location = new int[2];
            int[] viewLocation = new int[2];
            view0.getLocationInWindow(view0Location);
            view.getLocationInWindow(viewLocation);
            int offsetX = viewLocation[0] - view0Location[0];
            int offsetY = viewLocation[1] - view0Location[1];
            event.offsetLocation(-offsetX, -offsetY);
        }

        if (!view.isFocused() && event.getAction() == MotionEvent.ACTION_DOWN)
            view.requestFocus();

        if (event.getAction() == MotionEvent.ACTION_UP)
            setCapturingEnabled(true);

        if (event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_STYLUS
                || event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_ERASER)
            return mStylusListener.onTouch(event);

        if (!isDexEvent(event) && (event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_MOUSE
                || (event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE)
                || (event.getSource() & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE)
            return mHMListener.onTouch(view, event);

        if (event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_FINGER) {
            if (isDexEvent(event) && mDexListener.onTouch(view, event))
                return true;

            // START MODIFICATION: Direct scroll handling for classified swipes
            // The gesture is correctly identified as a two-finger swipe on the touchpad,
            // but it's not generating ACTION_SCROLL events with AXIS_VSCROLL/HSCROLL.
            // We need to handle the scroll directly here by calculating the delta and sending the scroll event.
            if (isClassifiedScrollEvent(event)) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    mLastScrollX = event.getX();
                    mLastScrollY = event.getY();
                    mIsTrackpadScroll = true;
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && mIsTrackpadScroll) {
                    float deltaX = event.getX() - mLastScrollX;
                    float deltaY = event.getY() - mLastScrollY;
                    // Store last coordinates for next delta calculation
                    mLastScrollX = event.getX();
                    mLastScrollY = event.getY();

                    // FIX: Send the scroll event directly to the injector,
                    // similar to how MotionEvent.ACTION_SCROLL is handled,
                    // instead of relying on mInputStrategy.onScroll or GestureDetector.
                    // Invert deltaX/deltaY if needed to match expected scroll direction.
                    // Scale factor (e.g., 100.0f) might need adjustment based on sensitivity.
                    // Using the same scaling factor as ACTION_SCROLL for consistency.
                    float scrollX = deltaX; // May need sign adjustment based on testing
                    float scrollY = deltaY; // May need sign adjustment based on testing

                    // Send the calculated scroll deltas directly
                    // Inverting Y might be necessary depending on expected direction (common for vertical scroll)
                    // The scaling factor might need tuning. 100 is used as it matches the ACTION_SCROLL handler.
                    // Adjust signs/scaling as needed based on testing.
                    mInjector.sendMouseWheelEvent(-scrollX * 100, -scrollY * 100);

                } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                     // Reset scroll state on finger lift or cancel
                     mIsTrackpadScroll = false;
                }
                // Don't pass to other detectors if we handled it as a scroll.
                return true;
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                mIsTrackpadScroll = false;
            }
            // END MODIFICATION

            if (mInputStrategy instanceof InputStrategyInterface.NullInputStrategy)
                mInjector.sendTouchEvent(event, mRenderData);
            else
                mInputStrategy.onMotionEvent(event);

            mScroller.onTouchEvent(event);
            mTapDetector.onTouchEvent(event);
            mSwipePinchDetector.onTouchEvent(event);

            if ((event.getSource() & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
                currentBS = event.getButtonState();
                for (int[] button: buttons)
                    if (isMouseButtonChanged(button[0]))
                        mInjector.sendMouseEvent(null, button[1], mouseButtonDown(button[0]), true);
                savedBS = currentBS;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mSuppressCursorMovement = false;
                    mSwipeCompleted = false;
                    mIsDragging = false;
                    break;
                case MotionEvent.ACTION_SCROLL: // This handles explicit scroll events (e.g., from physical mouse wheel)
                    float scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    float scrollX = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
                    if (scrollY == 0 && scrollX == 0) {
                        // Try negated values if initial read is zero
                        scrollY = -event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                        scrollX = -event.getAxisValue(MotionEvent.AXIS_HSCROLL);
                    }
                    // Scale factor applied here as well
                    scrollY *= -100; // Invert Y for standard scroll direction
                    scrollX *= -100; // Invert X if needed
                    mInjector.sendMouseWheelEvent(scrollX, scrollY);
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    mTotalMotionY = 0;
                    break;
                default:
                    break;
            }
            return true;
        }
        return false;
    }


    private void resetTransformation() {
        if (mRenderData.imageWidth == 0 || mRenderData.imageHeight == 0) return;
        float sx = (float) mRenderData.screenWidth / (float) mRenderData.imageWidth;
        float sy = (float) mRenderData.screenHeight / (float) mRenderData.imageHeight;
        mRenderData.scale.set(sx, sy);
    }

    public void handleClientSizeChanged(int w, int h) {
        mRenderData.screenWidth = w;
        mRenderData.screenHeight = h;
        if (mTouchpadHandler != null)
            mTouchpadHandler.handleClientSizeChanged(w, h);
        resetTransformation();
    }

    public void handleHostSizeChanged(int w, int h) {
        mRenderData.imageWidth = w;
        mRenderData.imageHeight = h;
        if (mTouchpadHandler != null)
            mTouchpadHandler.handleHostSizeChanged(w, h);
        resetTransformation();
        MainActivity.getRealMetrics(mMetrics);
    }

    public void setInputMode(@InputMode int inputMode) {
        if (mTouchpadHandler == null)
            mInputStrategy = new InputStrategyInterface.TrackpadInputStrategy(mInjector);
        else if (inputMode == InputMode.TOUCH)
            mInputStrategy = new InputStrategyInterface.NullInputStrategy();
        else if (inputMode == InputMode.SIMULATED_TOUCH)
            mInputStrategy = new InputStrategyInterface.SimulatedTouchInputStrategy(mRenderData, mInjector, mActivity);
        else
            mInputStrategy = new InputStrategyInterface.TrackpadInputStrategy(mInjector);
    }

    public void setCapturingEnabled(boolean enabled) {
        if (mInjector.pointerCapture && enabled)
            mActivity.getLorieView().requestPointerCapture();
        else
            mActivity.getLorieView().releasePointerCapture();
        if (mInjector.pauseKeyInterceptingWithEsc) {
            if (mInjector.dexMetaKeyCapture)
                SamsungDexUtils.dexMetaKeyCapture(mActivity, enabled);
            keyIntercepting = enabled;
        }
    }

    public static boolean isExternal(InputDevice d) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            return d.isExternal();
        try {
            // isExternal is a hidden method that is not accessible through the SDK_INT before Android Q
            //noinspection DataFlowIssue
            return (Boolean) InputDevice.class.getMethod("isExternal").invoke(d);
        } catch (NullPointerException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            return false;
        }
    }

    public void reloadPreferences(Prefs p) {
        setInputMode(Integer.parseInt(p.touchMode.get()));
        mInjector.tapToMove = p.tapToMove.get();
        mInjector.preferScancodes = p.preferScancodes.get();
        mInjector.pointerCapture = p.pointerCapture.get();
        mInjector.scaleTouchpad = p.scaleTouchpad.get() &&
                "1".equals(p.touchMode.get()) &&
                !"native".equals(p.displayResolutionMode.get());
        mInjector.capturedPointerSpeedFactor = ((float) p.capturedPointerSpeedFactor.get())/100;
        mInjector.dexMetaKeyCapture = p.dexMetaKeyCapture.get();
        mInjector.stylusIsMouse = p.stylusIsMouse.get();
        mInjector.stylusButtonContactModifierMode = p.stylusButtonContactModifierMode.get();
        mInjector.pauseKeyInterceptingWithEsc = p.pauseKeyInterceptingWithEsc.get();
        switch (p.transformCapturedPointer.get()) {
            case "c":
                capturedPointerTransformation = CapturedPointerTransformation.CLOCKWISE;
                break;
            case "cc":
                capturedPointerTransformation = CapturedPointerTransformation.COUNTER_CLOCKWISE;
                break;
            case "ud":
                capturedPointerTransformation = CapturedPointerTransformation.UPSIDE_DOWN;
                break;
            case "at":
                capturedPointerTransformation = CapturedPointerTransformation.AUTO;
                break;
            default:
                capturedPointerTransformation = CapturedPointerTransformation.NONE;
        }
        MainActivity.getRealMetrics(mMetrics);
        if (!p.pointerCapture.get() && mActivity.getLorieView().hasPointerCapture())
            mActivity.getLorieView().releasePointerCapture();
        keyIntercepting = !mInjector.pauseKeyInterceptingWithEsc || mActivity.getLorieView().hasPointerCapture();
        SamsungDexUtils.dexMetaKeyCapture(mActivity, mInjector.dexMetaKeyCapture && keyIntercepting);
        swipeUpAction = extractUserActionFromPreferences(p, "swipeUp");
        swipeDownAction = extractUserActionFromPreferences(p, "swipeDown");
        volumeUpAction = extractUserActionFromPreferences(p, "volumeUp");
        volumeDownAction = extractUserActionFromPreferences(p, "volumeDown");
        backButtonAction = extractUserActionFromPreferences(p, "backButton");
        mediaKeysAction = extractUserActionFromPreferences(p, "mediaKeys");
        if(mTouchpadHandler != null)
            mTouchpadHandler.reloadPreferences(p);
    }

    public BiConsumer<Integer, Boolean> extractUserActionFromPreferences(Prefs p, String name) {
        LoriePreferences.PrefsProto.Preference pref = p.keys.get(name + "Action");
        if (pref == null)
            return noAction;
        switch(pref.asList().get()) {
            case "toggle soft keyboard": return (key, down) -> { if (down) MainActivity.toggleKeyboardVisibility(mActivity); };
            case "toggle additional key bar": return (key, down) -> { if (down) mActivity.toggleExtraKeys(); };
            case "open preferences": return (key, down) -> { if (down) mActivity.startActivity(new Intent(mActivity, LoriePreferences.class) {{ setAction(Intent.ACTION_MAIN); }}); };
            case "release pointer and keyboard capture": return (key, down) -> { if (down) setCapturingEnabled(false); };
            case "toggle fullscreen": return (key, down) -> { if (down) MainActivity.prefs.fullscreen.put(!MainActivity.prefs.fullscreen.get()); };
            case "exit": return (key, down) -> { if (down) mActivity.finish(); };
            case "send volume up": return (key, down) -> mActivity.getLorieView().sendKeyEvent(0, KEYCODE_VOLUME_UP, down);
            case "send volume down": return (key, down) -> mActivity.getLorieView().sendKeyEvent(0, KEYCODE_VOLUME_DOWN, down);
            case "send media action": return (key, down) -> mActivity.getLorieView().sendKeyEvent(0, key, down);
            default: return noAction;
        }
    }

    public PendingIntent extractIntentFromPreferences(Prefs p, String name, int requestCode) {
        LoriePreferences.PrefsProto.Preference pref = p.keys.get(name + "Action");
        if (pref == null)
            return null;
        switch(pref.asList().get()) {
            case "open preferences":
                return PendingIntent.getActivity(mActivity, requestCode, new Intent(mActivity, LoriePreferences.class) {{
                    putExtra("key", "value");
                    setPackage(mActivity.getPackageName());
                    setAction(Intent.ACTION_MAIN);
                }}, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            case "restart activity":
                return PendingIntent.getActivity(mActivity, requestCode,
                        Intent.makeRestartActivityTask(mActivity.getComponentName()), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            case "exit":
            case "toggle soft keyboard":
            case "toggle additional key bar":
            case "release pointer and keyboard capture":
                return PendingIntent.getBroadcast(mActivity, requestCode, new Intent(MainActivity.ACTION_CUSTOM) {{
                    putExtra("what", name);
                    setPackage(mActivity.getPackageName());
                }}, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            default: return null;
        }
    }

    @SuppressLint("DiscouragedApi")
    public String extractTitleFromPreferences(Prefs p, String name) {
        LoriePreferences.PrefsProto.Preference pref = p.keys.get(name + "Action");
        if (pref == null)
            return null;
        String key = pref.asList().get().replace(' ', '_');
        int id = mActivity.getResources().getIdentifier("notification_" + key, "string", mActivity.getPackageName());
        return id == 0 ? null : mActivity.getResources().getString(id);
    }

    public NotificationCompat.Builder setupNotification(Prefs prefs, NotificationCompat.Builder builder) {
        PendingIntent i;
        if ((i = extractIntentFromPreferences(prefs, "notificationTap", 0)) != null)
            builder.setContentIntent(i);
        if ((i = extractIntentFromPreferences(prefs, "notificationButton0", 1)) != null)
            builder.addAction(0, extractTitleFromPreferences(prefs, "notificationButton0"), i);
        if ((i = extractIntentFromPreferences(prefs, "notificationButton1", 2)) != null)
            builder.addAction(0, extractTitleFromPreferences(prefs, "notificationButton1"), i);
        return builder;
    }

    public boolean shouldInterceptKeys() {
        return !mInjector.pauseKeyInterceptingWithEsc || keyIntercepting;
    }

    private void moveCursorByOffset(float deltaX, float deltaY) {
        if (mInputStrategy instanceof InputStrategyInterface.TrackpadInputStrategy)
            mInjector.sendCursorMove(-deltaX, -deltaY, true);
        else if (mInputStrategy instanceof InputStrategyInterface.SimulatedTouchInputStrategy) {
            PointF cursorPos = mRenderData.getCursorPosition();
            cursorPos.offset(-deltaX, -deltaY);
            cursorPos.set(MathUtils.clamp(cursorPos.x, 0, mRenderData.screenWidth), MathUtils.clamp(cursorPos.y, 0, mRenderData.screenHeight));
            if (mRenderData.setCursorPosition(cursorPos.x, cursorPos.y))
                mInjector.sendCursorMove((int) cursorPos.x, (int) cursorPos.y, false);
        }
    }

    /** Moves the cursor to the specified position on the screen. */
    private void moveCursorToScreenPoint(float screenX, float screenY) {
        if (mInputStrategy instanceof InputStrategyInterface.TrackpadInputStrategy || mInputStrategy instanceof InputStrategyInterface.SimulatedTouchInputStrategy) {
            float[] imagePoint = {screenX * mRenderData.scale.x, screenY * mRenderData.scale.y};
            if (mRenderData.setCursorPosition(imagePoint[0], imagePoint[1]))
                mInjector.sendCursorMove((int) imagePoint[0], imagePoint[1], false);
        }
    }

    /** Processes a (multi-finger) swipe gesture. */
    private boolean onSwipe() {
        if (mTotalMotionY > mSwipeThreshold)
            swipeDownAction.accept(0, true);
        else if (mTotalMotionY < -mSwipeThreshold)
            swipeUpAction.accept(0, true);
        else
            return false;
        mSuppressCursorMovement = true;
        mSwipeCompleted = true;
        return true;
    }

    /** Responds to touch events filtered by the gesture detectors.
     * @noinspection NullableProblems*/
    private class GestureListener extends GestureDetector.SimpleOnGestureListener
            implements TapGestureDetector.OnTapListener {
        private final Handler mGestureListenerHandler = new Handler(msg -> {
            if (msg.what == InputStub.BUTTON_LEFT)
                mInputStrategy.onTap(InputStub.BUTTON_LEFT);
            return true;
        });

        /**
         * Called when the user drags one or more fingers across the touchscreen.
         */
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            int pointerCount = e2.getPointerCount();
            if (pointerCount >= 3 && !mSwipeCompleted) {
                mTotalMotionY -= distanceY;
                return onSwipe();
            }
            if (pointerCount == 2 && mSwipePinchDetector.isSwiping()) {
                if (!(mInputStrategy instanceof InputStrategyInterface.TrackpadInputStrategy)) {
                    moveCursorToScreenPoint(e1.getX(), e1.getY());
                }
                mInputStrategy.onScroll(distanceX, distanceY);
                mSuppressCursorMovement = true;
                return true;
            }
            if (pointerCount != 1 || mSuppressCursorMovement)
                return false;
            if (mInputStrategy instanceof InputStrategyInterface.TrackpadInputStrategy) {
                if (mInjector.scaleTouchpad) {
                    distanceX *= mRenderData.scale.x;
                    distanceY *= mRenderData.scale.y;
                }
                moveCursorByOffset(distanceX, distanceY);
            }
            if (!(mInputStrategy instanceof InputStrategyInterface.TrackpadInputStrategy) && mIsDragging) {
                moveCursorToScreenPoint(e2.getX(), e2.getY());
            }
            return true;
        }

        /** Called whenever a gesture starts. Always accepts the gesture so it isn't ignored. */
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        /**
         * Called when the user taps the screen with one or more fingers.
         */
        @Override
        public void onTap(int pointerCount, float x, float y) {
            int button = mouseButtonFromPointerCount(pointerCount);
            if (button == InputStub.BUTTON_UNDEFINED)
                return;
            if (!(mInputStrategy instanceof InputStrategyInterface.TrackpadInputStrategy)) {
                if (screenPointLiesOutsideImageBoundary(x, y))
                    return;
                moveCursorToScreenPoint(x, y);
            }
            if (button != InputStub.BUTTON_LEFT || !(mInjector.tapToMove && mInputStrategy instanceof InputStrategyInterface.TrackpadInputStrategy))
                mInputStrategy.onTap(button);
            else
                mGestureListenerHandler.sendEmptyMessageDelayed(InputStub.BUTTON_LEFT, ViewConfiguration.getDoubleTapTimeout());
        }

        private float mLastFocusX;
        private float mLastFocusY;
        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            if (e.getPointerCount() == 1) {
                switch(e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (mInjector.tapToMove && mInputStrategy instanceof InputStrategyInterface.TrackpadInputStrategy) {
                            mGestureListenerHandler.removeMessages(InputStub.BUTTON_LEFT);
                            if (mInputStrategy.onPressAndHold(InputStub.BUTTON_LEFT, true))
                                mIsDragging = true;
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        onScroll(null, e, mLastFocusX - e.getX(), mLastFocusY - e.getY());
                        break;
                }
                mLastFocusX = e.getX();
                mLastFocusY = e.getY();
            }
            return true;
        }

        /** Called when a long-press is triggered for one or more fingers. */
        @Override
        public void onLongPress(int pointerCount, float x, float y) {
            int button = mouseButtonFromPointerCount(pointerCount);
            if (button == InputStub.BUTTON_UNDEFINED) {
                return;
            }
            if (!(mInputStrategy instanceof InputStrategyInterface.TrackpadInputStrategy)) {
                if (screenPointLiesOutsideImageBoundary(x, y))
                    return;
                moveCursorToScreenPoint(x, y);
            }
            if (mInputStrategy.onPressAndHold(button, false))
                mIsDragging = true;
        }

        /** Maps the number of fingers in a tap or long-press gesture to a mouse-button. */
        private int mouseButtonFromPointerCount(int pointerCount) {
            switch (pointerCount) {
                case 1:
                    return InputStub.BUTTON_LEFT;
                case 2:
                    return InputStub.BUTTON_RIGHT;
                case 3:
                    return InputStub.BUTTON_MIDDLE;
                default:
                    return InputStub.BUTTON_UNDEFINED;
            }
        }

        /** Determines whether the given screen point lies outside the desktop image. */
        private boolean screenPointLiesOutsideImageBoundary(float screenX, float screenY) {
            float scaledX = screenX * mRenderData.scale.x, scaledY = screenY * mRenderData.scale.y;
            float imageWidth = (float) mRenderData.imageWidth + EPSILON;
            float imageHeight = (float) mRenderData.imageHeight + EPSILON;
            return scaledX < -EPSILON || scaledX > imageWidth || scaledY < -EPSILON || scaledY > imageHeight;
        }
    }

    /**
     * It is a copy of {@link android.view.KeyEvent#isMediaSessionKey} to be used on Android 30 and below.
     * Returns whether this key will be sent to the
     * {@link android.media.session.MediaSession.Callback} if not handled.
     */
    public static boolean isMediaSessionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return true;
        }
        return false;
    }

    public boolean sendKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (!MainActivity.isConnected()) {
            if (e.getKeyCode() == KEYCODE_BACK)
                mActivity.finish();
            return false;
        }
        if (isMediaSessionKey(k)) {
            if (mediaKeysAction == noAction)
                return false;
            mediaKeysAction.accept(k, e.getAction() == KeyEvent.ACTION_DOWN);
            return true;
        }
        if (k == KEYCODE_VOLUME_DOWN) {
            if (volumeDownAction == noAction)
                return false;
            volumeDownAction.accept(k, e.getAction() == KeyEvent.ACTION_DOWN);
            return true;
        }
        if (k == KEYCODE_VOLUME_UP) {
            if (volumeUpAction == noAction)
                return false;
            volumeUpAction.accept(k, e.getAction() == KeyEvent.ACTION_DOWN);
            return true;
        }
        if (k == KEYCODE_BACK) {
            if (e.isFromSource(InputDevice.SOURCE_MOUSE) || e.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) {
                if (e.getRepeatCount() != 0) // ignore auto-repeat
                    return true;
                if (e.getAction() == KeyEvent.ACTION_UP || e.getAction() == KeyEvent.ACTION_DOWN)
                    mActivity.getLorieView().sendMouseEvent(-1, -1, InputStub.BUTTON_RIGHT, e.getAction() == KeyEvent.ACTION_DOWN, true);
                return true;
            }
            if (e.getScanCode() == KEY_BACK && e.getDevice().getKeyboardType() != KEYBOARD_TYPE_ALPHABETIC || e.getScanCode() == 0) {
                backButtonAction.accept(k, e.getAction() == KeyEvent.ACTION_DOWN);
                return true;
            }
        }
        return mInjector.sendKeyEvent(e);
    }

    private class HardwareMouseListener {
        private int savedBS = 0;
        private int currentBS = 0;
        // START MODIFICATION
        private float lastX, lastY;
        // END MODIFICATION

        boolean isMouseButtonChanged(int mask) {
            return (savedBS & mask) != (currentBS & mask);
        }
        boolean mouseButtonDown(int mask) {
            return ((currentBS & mask) != 0);
        }

        private final int[][] buttons = {
                {MotionEvent.BUTTON_PRIMARY, InputStub.BUTTON_LEFT},
                {MotionEvent.BUTTON_TERTIARY, InputStub.BUTTON_MIDDLE},
                {MotionEvent.BUTTON_SECONDARY, InputStub.BUTTON_RIGHT}
        };

        /** @noinspection ReassignedVariable, SuspiciousNameCombination*/
        @SuppressLint("ClickableViewAccessibility")
        boolean onTouch(View v, MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_SCROLL) {
                float scrollY = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                float scrollX = e.getAxisValue(MotionEvent.AXIS_HSCROLL);
                if (scrollY == 0 && scrollX == 0) {
                    scrollY = -e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    scrollX = -e.getAxisValue(MotionEvent.AXIS_HSCROLL);
                }
                scrollY *= -100;
                scrollX *= -100;
                mInjector.sendMouseWheelEvent(scrollX, scrollY);
                return true;
            }
            if (!v.hasPointerCapture()) {
                float scaledX = e.getX() * mRenderData.scale.x, scaledY = e.getY() * mRenderData.scale.y;
                if (mRenderData.setCursorPosition(scaledX, scaledY))
                    mInjector.sendCursorMove(scaledX, scaledY, false);
            } else if (e.getAction() == MotionEvent.ACTION_MOVE && e.getPointerCount() == 1) {
                boolean axis_relative_x = e.getDevice().getMotionRange(MotionEvent.AXIS_RELATIVE_X) != null;
                boolean mouse_relative = (e.getSource() & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE;
                float x, y;
                if (axis_relative_x || mouse_relative) {
                    x = axis_relative_x ? e.getAxisValue(MotionEvent.AXIS_RELATIVE_X) : e.getX();
                    y = axis_relative_x ? e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y) : e.getY();
                } else {
                    // START MODIFICATION: Add this else block to handle devices that don't
                    // Calculate relative motion from absolute coordinates
                    float deltaX = e.getX() - lastX;
                    float deltaY = e.getY() - lastY;
                    x = deltaX;
                    y = deltaY;
                }
                // Store the current position for the next event
                lastX = e.getX();
                lastY = e.getY();
                // END MODIFICATION
                float temp;
                switch (capturedPointerTransformation) {
                    case CapturedPointerTransformation.CLOCKWISE:
                        temp = x; x = -y; y = temp; break;
                    case CapturedPointerTransformation.COUNTER_CLOCKWISE:
                        temp = x; x = y; y = -temp; break;
                    case CapturedPointerTransformation.UPSIDE_DOWN:
                        x = -x; y = -y; break;
                    default:
                        break;
                }
                x *= mInjector.capturedPointerSpeedFactor * mMetrics.density;
                y *= mInjector.capturedPointerSpeedFactor * mMetrics.density;
                mInjector.sendCursorMove(x, y, true);
                if (axis_relative_x && mTouchpadHandler != null)
                    mTouchpadHandler.mTapDetector.onTouchEvent(e);
            }
            // START MODIFICATION
            else if (e.getAction() == MotionEvent.ACTION_DOWN) {
                // Initialize last coordinates on the first touch event in captured mode
                lastX = e.getX();
                lastY = e.getY();
            }
            // END MODIFICATION
            currentBS = e.getButtonState();
            for (int[] button: buttons)
                if (isMouseButtonChanged(button[0]))
                    mInjector.sendMouseEvent(null, button[1], mouseButtonDown(button[0]), true);
            savedBS = currentBS;
            return true;
        }
    }

    private class StylusListener {
        private float x = 0, y = 0, pressure = 0, tilt = 0, orientation = 0;
        private int buttons = 0;
        private int convertOrientation(float value) {
            int newValue = (int) (((value * 180 / Math.PI) + 360) % 360);
            if (newValue > 180)
                newValue = (newValue - 360) % 360;
            return newValue;
        }
        private boolean hasButton(MotionEvent e, int button) {
            return (e.getButtonState() & button) == button;
        }
        int extractButtons(MotionEvent e) {
            if (mInjector.stylusButtonContactModifierMode) {
                if (e.getPressure() > 0) {
                    if (hasButton(e, MotionEvent.BUTTON_STYLUS_SECONDARY))
                        return (1 << 1);
                    if (hasButton(e, MotionEvent.BUTTON_STYLUS_PRIMARY))
                        return (1 << 2);
                    else
                        return STYLUS_INPUT_HELPER_MODE;
                } else return 0;
            } else {
                int buttons = 0;
                if (e.getPressure() > 0)
                    buttons = STYLUS_INPUT_HELPER_MODE;
                if (hasButton(e, MotionEvent.BUTTON_STYLUS_SECONDARY))
                    buttons |= (1 << 1);
                if (hasButton(e, MotionEvent.BUTTON_STYLUS_PRIMARY))
                    buttons |= (1 << 2);
                return buttons;
            }
        }
        @SuppressLint("ClickableViewAccessibility")
        boolean onTouch(MotionEvent e) {
            int action = e.getAction();
            int tiltX = 0, tiltY = 0;
            int newButtons = extractButtons(e);
            float newX = e.getX(e.getActionIndex()), newY = e.getY(e.getActionIndex());
            InputDevice dev = e.getDevice();
            InputDevice.MotionRange rangeX = dev.getMotionRange(MotionEvent.AXIS_X);
            InputDevice.MotionRange rangeY = dev.getMotionRange(MotionEvent.AXIS_Y);
            boolean hasTilt = e.getDevice().getMotionRange(MotionEvent.AXIS_TILT) != null;
            boolean hasOrientation = e.getDevice().getMotionRange(MotionEvent.AXIS_ORIENTATION) != null;
            if (MainActivity.getInstance().getLorieView().hasPointerCapture() &&
                    isExternal(dev) && rangeX != null && rangeY != null) {
                newX *= mRenderData.imageWidth / rangeX.getMax();
                newY *= mRenderData.imageHeight / rangeY.getMax();
            } else {
                newX *= mRenderData.scale.x;
                newY *= mRenderData.scale.y;
            }
            if (x == newX && y == newY && pressure == e.getPressure() && tilt == e.getAxisValue(MotionEvent.AXIS_TILT) &&
                    orientation == e.getAxisValue(MotionEvent.AXIS_ORIENTATION) && buttons == newButtons)
                return true;
            if (hasTilt && hasOrientation) {
                orientation = e.getAxisValue(MotionEvent.AXIS_ORIENTATION);
                tilt = e.getAxisValue(MotionEvent.AXIS_TILT);
                tiltX = (int) Math.round((float) Math.asin(-Math.sin(orientation) * Math.sin(tilt)) * 63.5 - 0.5);
                tiltY = (int) Math.round((float) Math.asin( Math.cos(orientation) * Math.sin(tilt)) * 63.5 - 0.5);
            }
            android.util.Log.d("STYLUS_EVENT", "action " + action + " x " + newX + " y " + newY + " pressure " + e.getPressure() + " tilt " + e.getAxisValue(MotionEvent.AXIS_TILT) + " orientation " + e.getAxisValue(MotionEvent.AXIS_ORIENTATION) + " buttonState " + e.getButtonState() + " extractedButtons " + newButtons);
            mInjector.sendStylusEvent(
                    x = newX,
                    y = newY,
                    (int) ((pressure = e.getPressure()) * 65535),
                    tiltX,
                    tiltY,
                    convertOrientation(orientation),
                    buttons = newButtons,
                    e.getToolType(e.getActionIndex()) == MotionEvent.TOOL_TYPE_ERASER,
                    mInjector.stylusIsMouse);
            return true;
        }
    }

    /** @noinspection NullableProblems*/
    private class DexListener extends GestureDetector.SimpleOnGestureListener {
        private final GestureDetector mScroller;
        private int savedBS = 0;
        private int currentBS = 0;
        private boolean onTap = false;
        private boolean mIsDragging = false;
        private boolean mIsScrolling = false;

        DexListener(Context ctx) {
            mScroller = new GestureDetector(ctx, this, null, false);
        }

        private final Handler handler = new Handler();
        private final Runnable mouseDownRunnable = () -> mInjector.sendMouseEvent(mRenderData.getCursorPosition(), InputStub.BUTTON_LEFT, true, false);

        private final int[][] buttons = {
                {MotionEvent.BUTTON_PRIMARY, InputStub.BUTTON_LEFT},
                {MotionEvent.BUTTON_TERTIARY, InputStub.BUTTON_MIDDLE},
                {MotionEvent.BUTTON_SECONDARY, InputStub.BUTTON_RIGHT}
        };

        boolean isMouseButtonChanged(int mask) {
            return (savedBS & mask) != (currentBS & mask);
        }
        boolean mouseButtonDown(int mask) {
            return ((currentBS & mask) != 0);
        }

        boolean checkButtons(MotionEvent e) {
            boolean isHandled = false;
            currentBS = e.getButtonState();
            for (int[] button: buttons) {
                if (isMouseButtonChanged(button[0])) {
                    mInjector.sendMouseEvent(mRenderData.getCursorPosition(), button[1], mouseButtonDown(button[0]), false);
                    isHandled = true;
                }
            }
            savedBS = currentBS;
            return isHandled;
        }

        private boolean hasFlags(MotionEvent e, int flags) {
            return (e.getFlags() & flags) == flags;
        }

        @SuppressLint({"WrongConstant", "InlinedApi"})
        private boolean isScrollingEvent(MotionEvent e) {
            return hasFlags(e, 0x14000000) || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e.getClassification() == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE);
        }

        boolean onTouch(@SuppressWarnings("unused") View v, MotionEvent e) {
            boolean isButtonHandled;
            switch(e.getActionMasked()) {
                case MotionEvent.ACTION_BUTTON_PRESS:
                case MotionEvent.ACTION_BUTTON_RELEASE:
                    mScroller.onGenericMotionEvent(e);
                    handler.removeCallbacks(mouseDownRunnable);
                    onTap = e.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS;
                    mIsDragging = false;
                    checkButtons(e);
                    return true;
                case MotionEvent.ACTION_HOVER_MOVE: {
                    float scaledX = e.getX() * mRenderData.scale.x, scaledY = e.getY() * mRenderData.scale.y;
                    if (mRenderData.setCursorPosition(scaledX, scaledY))
                        mInjector.sendCursorMove(scaledX, scaledY, false);
                    return true;
                }
                case MotionEvent.ACTION_DOWN:
                    isButtonHandled = checkButtons(e);
                    if (isScrollingEvent(e)) {
                        mIsScrolling = true;
                        mScroller.onTouchEvent(e);
                    } else if (hasFlags(e, 0x4000000)) {
                        mIsDragging = true;
                        handler.postDelayed(mouseDownRunnable, 0);
                    } else if (!isButtonHandled) {
                        onTap = true;
                        mInjector.sendMouseEvent(mRenderData.getCursorPosition(), InputStub.BUTTON_LEFT, true, false);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    isButtonHandled = checkButtons(e);
                    if (isScrollingEvent(e)) {
                        mScroller.onTouchEvent(e);
                        mIsScrolling = false;
                    }
                    else if (hasFlags(e, 0x4000000)) {
                        mInjector.sendMouseEvent(mRenderData.getCursorPosition(), InputStub.BUTTON_LEFT, false, false);
                        mIsDragging = false;
                    } else if (!isButtonHandled && onTap) {
                        mInjector.sendMouseEvent(mRenderData.getCursorPosition(), InputStub.BUTTON_LEFT, false, false);
                        onTap = false;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mIsScrolling && isScrollingEvent(e))
                        mScroller.onTouchEvent(e);
                    else if ((mIsDragging && hasFlags(e, 0x4000000)) || onTap) {
                        float scaledX = e.getX() * mRenderData.scale.x, scaledY = e.getY() * mRenderData.scale.y;
                        if (mRenderData.setCursorPosition(scaledX, scaledY))
                            mInjector.sendCursorMove(scaledX, scaledY, false);
                    }
                    return true;
                case MotionEvent.ACTION_HOVER_EXIT: // when the user removes their hand from the trackpad, all states should be reset
                case MotionEvent.ACTION_CANCEL:
                    onTap = false;
                    mIsScrolling = false;
                    mIsDragging = false;
                    return true;
            }
            return false;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            mInjector.sendMouseWheelEvent(distanceX, distanceY);
            return true;
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            onSingleTapConfirmed(e);
            onSingleTapConfirmed(e);
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            mInjector.sendMouseEvent(mRenderData.getCursorPosition(), InputStub.BUTTON_LEFT, true, false);
            mInjector.sendMouseEvent(mRenderData.getCursorPosition(), InputStub.BUTTON_LEFT, false, false);
            return true;
        }
    }
}
