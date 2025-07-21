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

public class TouchInputHandler {
    private static final float EPSILON = 0.001f;

    public static int STYLUS_INPUT_HELPER_MODE = 1; // 1 = Left Click, 2 Middle Click, 4 Right Click

    @IntDef({InputMode.TRACKPAD, InputMode.SIMULATED_TOUCH, InputMode.TOUCH})
    @Retention(RetentionPolicy.SOURCE)
    public @interface InputMode {
        int TRACKPAD = 1;
        int SIMULATED_TOUCH = 2;
        int TOUCH = 3;
    }

    @IntDef({CapturedPointerTransformation.AUTO, CapturedPointerTransformation.NONE, CapturedPointerTransformation.COUNTER_CLOCKWISE, CapturedPointerTransformation.UPSIDE_DOWN, CapturedPointerTransformation.CLOCKWISE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CapturedPointerTransformation {
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
    private final DexListener mDexListener;
    private final TouchInputHandler mTouchpadHandler;

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
    private float mTotalMotionY;
    private final float mSwipeThreshold;
    private boolean mSuppressCursorMovement;
    private boolean mSwipeCompleted;
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

    // START MODIFICATION: Variables for robust input handling
    private float mLastX;
    private float mLastY;
    private boolean mIsTrackpadScroll;
    // END MODIFICATION

    private TouchInputHandler(MainActivity activity, RenderData renderData, final InputEventSender injector, boolean isTouchpad) {
        if (injector == null)
            throw new NullPointerException();

        mRenderData = renderData != null ? renderData : new RenderData();
        mInjector = injector;
        mActivity = activity;
        if (mDisplayManager == null) {
            mDisplayManager = (DisplayManager) mActivity.getSystemService(Context.DISPLAY_SERVICE);
            mDisplayRotation = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY).getRotation() % 4;
            mDisplayManager.registerDisplayListener(mDisplayListener, null);
        }

        GestureListener listener = new GestureListener();
        mScroller = new GestureDetector(activity, listener, null, false);
        mScroller.setIsLongpressEnabled(false);

        mTapDetector = new TapGestureDetector(activity, listener);
        mSwipePinchDetector = new SwipeDetector(activity);

        float density = activity.getResources().getDisplayMetrics().density;
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
                    if (device.supportsSource(InputDevice.SOURCE_KEYBOARD) && device.getKeyboardType() == KEYBOARD_TYPE_ALPHABETIC && isExternal(device))
                        externalKeyboardAvailable.set(true);
                });
        LorieView.requestStylusEnabled(stylusAvailable.get());
        MainActivity.getInstance().setExternalKeyboardConnected(externalKeyboardAvailable.get());
    }

    // START MODIFICATION: Centralized scroll detection
    @SuppressLint({"WrongConstant", "InlinedApi"})
    private static boolean isSystemClassifiedScroll(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_TOUCHPAD) != InputDevice.SOURCE_TOUCHPAD) {
            return false;
        }

        // On older Samsung DeX, two-finger scroll gestures are sent with this flag.
        if ((e.getFlags() & 0x14000000) != 0) {
            return true;
        }

        // On Android 10+ and standard touchpads, this classification is used.
        // It is often sent with a pointer count of 1, which confuses standard detectors.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            e.getClassification() == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE) {
            return true;
        }
        return false;
    }
    // END MODIFICATION

    boolean isDexEvent(MotionEvent event) {
        return ((event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE)
                && ((event.getSource() & InputDevice.SOURCE_TOUCHPAD) != InputDevice.SOURCE_TOUCHPAD)
                && (event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_FINGER);
    }

    @SuppressLint("ClickableViewAccessibility")
    public boolean handleTouchEvent(View view0, View view, MotionEvent event) {
        // START MODIFICATION: Major overhaul of input dispatching
        
        // Offset event coordinates if the touch view is not the root view
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

        // Handle stylus events first as they are distinct.
        if (event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_STYLUS
                || event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_ERASER) {
            return mStylusListener.onTouch(event);
        }

        // --- Captured Pointer Logic ---
        // If pointer is captured, we handle relative motion directly and bypass all other logic.
        if (mInjector.pointerCapture && view.hasPointerCapture()) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mLastX = event.getX();
                    mLastY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    // Always calculate delta manually for consistency.
                    float deltaX = event.getX() - mLastX;
                    float deltaY = event.getY() - mLastY;
                    mLastX = event.getX();
                    mLastY = event.getY();

                    int transform = capturedPointerTransformation == CapturedPointerTransformation.AUTO ?
                            mDisplayRotation : capturedPointerTransformation;
                    switch (transform) {
                        case CapturedPointerTransformation.CLOCKWISE:
                            float temp = deltaX; deltaX = -deltaY; deltaY = temp; break;
                        case CapturedPointerTransformation.COUNTER_CLOCKWISE:
                            temp = deltaX; //noinspection SuspiciousNameCombination
                            deltaX = deltaY; deltaY = -temp; break;
                        case CapturedPointerTransformation.UPSIDE_DOWN:
                            deltaX = -deltaX; deltaY = -deltaY; break;
                    }

                    deltaX *= mInjector.capturedPointerSpeedFactor;
                    deltaY *= mInjector.capturedPointerSpeedFactor;

                    mInjector.sendCursorMove(deltaX, deltaY, true); // Send as relative motion
                    break;
            }

            // Handle physical button clicks on the touchpad
            int currentButtonState = event.getButtonState();
            for (int[] buttonMap : new int[][]{{MotionEvent.BUTTON_PRIMARY, InputStub.BUTTON_LEFT}, {MotionEvent.BUTTON_TERTIARY, InputStub.BUTTON_MIDDLE}, {MotionEvent.BUTTON_SECONDARY, InputStub.BUTTON_RIGHT}}) {
                if ((currentButtonState & buttonMap[0]) != (savedBS & buttonMap[0])) {
                    mInjector.sendMouseEvent(null, buttonMap[1], (currentButtonState & buttonMap[0]) != 0, true);
                }
            }
            savedBS = currentButtonState;
            return true;
        }

        // --- Non-Captured Logic ---

        // DeX touchpad in non-captured mode should be handled by its own listener.
        if (isDexEvent(event) && mDexListener.onTouch(view, event)) {
            return true;
        }

        // --- Two-Finger Scroll Interception ---
        // This is the core fix for scrolling. We detect it before the standard gesture detectors.
        if (isSystemClassifiedScroll(event)) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    mLastX = event.getX();
                    mLastY = event.getY();
                    mIsTrackpadScroll = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mIsTrackpadScroll) {
                        float dx = event.getX() - mLastX;
                        float dy = event.getY() - mLastY;
                        mLastX = event.getX();
                        mLastY = event.getY();
                        // Negative values because onScroll expects distance, not delta.
                        mInputStrategy.onScroll(-dx, -dy);
                    }
                    break;
            }
            return true; // Event handled, do not pass to other detectors.
        } else {
             // Reset scroll flag if the gesture is something else
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                mIsTrackpadScroll = false;
            }
        }

        // Handle standard mice (non-captured).
        if ((event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            (event.getSource() & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE) {
            return new HardwareMouseListener().onTouch(view, event);
        }

        // --- Fallback to Standard Gesture Detectors ---
        // If it's not a special case, process it as a normal screen touch/pan/tap.
        if (event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_FINGER) {
            if (mInputStrategy instanceof InputStrategyInterface.NullInputStrategy) {
                mInjector.sendTouchEvent(event, mRenderData);
            } else {
                mInputStrategy.onMotionEvent(event);
            }

            mScroller.onTouchEvent(event);
            mTapDetector.onTouchEvent(event);
            mSwipePinchDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mSuppressCursorMovement = false;
                    mSwipeCompleted = false;
                    mIsDragging = false;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    mTotalMotionY = 0;
                    break;
            }
            return true;
        }
        // END MODIFICATION

        return false;
    }


    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (newConfig.orientation != orientation)
            inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getRootView().getWindowToken(), 0);

        orientation = newConfig.orientation;
        setTerminalToolbarView();
    }

    public static void getRealMetrics(DisplayMetrics m) {
        if (getInstance() != null &&
                getInstance().getLorieView() != null &&
                getInstance().getLorieView().getDisplay() != null)
            getInstance().getLorieView().getDisplay().getRealMetrics(m);
    }

    public static void setCapturingEnabled(boolean enabled) {
        if (getInstance() == null || getInstance().mInputHandler == null)
            return;

        getInstance().mInputHandler.setCapturingEnabled(enabled);
    }

    public boolean shouldInterceptKeys() {
        View textInput = findViewById(R.id.terminal_toolbar_text_input);
        if (mInputHandler == null || !hasWindowFocus() || (textInput != null && textInput.isFocused()))
            return false;

        return mInputHandler.shouldInterceptKeys();
    }

    private class HardwareMouseListener {
        private int savedBS = 0;
        private int currentBS = 0;

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

            // This listener is now only for non-captured mice.
            float scaledX = e.getX() * mRenderData.scale.x, scaledY = e.getY() * mRenderData.scale.y;
            if (mRenderData.setCursorPosition(scaledX, scaledY))
                mInjector.sendCursorMove(scaledX, scaledY, false);

            currentBS = e.getButtonState();
            for (int[] button: buttons)
                if (isMouseButtonChanged(button[0]))
                    mInjector.sendMouseEvent(null, button[1], mouseButtonDown(button[0]), false);
            savedBS = currentBS;
            return true;
        }
    }
}
