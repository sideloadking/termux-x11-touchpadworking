package com.termux.x11;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent; // <-- ADDED
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.HandwritingGesture;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.PreviewableHandwritingGesture;
import android.view.inputmethod.SurroundingText;
import android.view.inputmethod.TextAttribute;
import android.view.inputmethod.TextBoundsInfoResult;
import android.view.inputmethod.TextSnapshot;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.core.math.MathUtils;

import com.termux.x11.input.InputStub;
import com.termux.x11.input.TouchInputHandler;

import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.PatternSyntaxException;

import static java.nio.charset.StandardCharsets.UTF_8;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

class InputConnectionWrapper implements InputConnection {
    private static final String TAG = "InputConnectionWrapper";
    private final InputConnection wrapped;

    public InputConnectionWrapper(InputConnection wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public CharSequence getTextBeforeCursor(int n, int flags) {
        Log.d(TAG, "getTextBeforeCursor(" + n + ", " + flags + ")");
        return wrapped.getTextBeforeCursor(n, flags);
    }

    @Override
    public CharSequence getTextAfterCursor(int n, int flags) {
        Log.d(TAG, "getTextAfterCursor(" + n + ", " + flags + ")");
        return wrapped.getTextAfterCursor(n, flags);
    }

    @Override
    public CharSequence getSelectedText(int flags) {
        Log.d(TAG, "getSelectedText(" + flags + ")");
        return wrapped.getSelectedText(flags);
    }

    @Override
    public SurroundingText getSurroundingText(int beforeLength, int afterLength, int flags) {
        Log.d(TAG, "getSurroundingText(" + beforeLength + ", " + afterLength + ", " + flags + ")");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return wrapped.getSurroundingText(beforeLength, afterLength, flags);
        } else return null;
    }

    @Override
    public int getCursorCapsMode(int reqModes) {
        Log.d(TAG, "getCursorCapsMode(" + reqModes + ")");
        return wrapped.getCursorCapsMode(reqModes);
    }

    @Override
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        Log.d(TAG, "getExtractedText(" + request + ", " + flags + ")");
        return wrapped.getExtractedText(request, flags);
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        Log.d(TAG, "deleteSurroundingText(" + beforeLength + ", " + afterLength + ")");
        return wrapped.deleteSurroundingText(beforeLength, afterLength);
    }

    @Override
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        Log.d(TAG, "deleteSurroundingTextInCodePoints(" + beforeLength + ", " + afterLength + ")");
        return wrapped.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        Log.d(TAG, "setComposingText(" + text + ", " + newCursorPosition + ")");
        return wrapped.setComposingText(text, newCursorPosition);
    }

    @Override
    public boolean setComposingText(@NonNull CharSequence text, int newCursorPosition, TextAttribute textAttribute) {
        Log.d(TAG, "setComposingText(" + text + ", " + newCursorPosition + ", " + textAttribute + ")");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return wrapped.setComposingText(text, newCursorPosition, textAttribute);
        } else return false;
    }

    @Override
    public boolean setComposingRegion(int start, int end) {
        Log.d(TAG, "setComposingRegion(" + start + ", " + end + ")");
        return wrapped.setComposingRegion(start, end);
    }

    @Override
    public boolean setComposingRegion(int start, int end, TextAttribute textAttribute) {
        Log.d(TAG, "setComposingRegion(" + start + ", " + end + ", " + textAttribute + ")");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return wrapped.setComposingRegion(start, end, textAttribute);
        } else return false;
    }

    @Override
    public boolean finishComposingText() {
        Log.d(TAG, "finishComposingText()");
        return wrapped.finishComposingText();
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        Log.d(TAG, "commitText(" + text + ", " + newCursorPosition + ")");
        return wrapped.commitText(text, newCursorPosition);
    }

    @Override
    public boolean commitText(@NonNull CharSequence text, int newCursorPosition, TextAttribute textAttribute) {
        Log.d(TAG, "commitText(" + text + ", " + newCursorPosition + ", " + textAttribute + ")");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return wrapped.commitText(text, newCursorPosition, textAttribute);
        } else return false;
    }

    @Override
    public boolean commitCompletion(CompletionInfo text) {
        Log.d(TAG, "commitCompletion(" + text + ")");
        return wrapped.commitCompletion(text);
    }

    @Override
    public boolean commitCorrection(CorrectionInfo correctionInfo) {
        Log.d(TAG, "commitCorrection(" + correctionInfo + ")");
        return wrapped.commitCorrection(correctionInfo);
    }

    @Override
    public boolean setSelection(int start, int end) {
        Log.d(TAG, "setSelection(" + start + ", " + end + ")");
        return wrapped.setSelection(start, end);
    }

    @Override
    public boolean performEditorAction(int editorAction) {
        Log.d(TAG, "performEditorAction(" + editorAction + ")");
        return wrapped.performEditorAction(editorAction);
    }

    @Override
    public boolean performContextMenuAction(int id) {
        Log.d(TAG, "performContextMenuAction(" + id + ")");
        return wrapped.performContextMenuAction(id);
    }

    @Override
    public boolean beginBatchEdit() {
        Log.d(TAG, "beginBatchEdit()");
        return wrapped.beginBatchEdit();
    }

    @Override
    public boolean endBatchEdit() {
        Log.d(TAG, "endBatchEdit()");
        return wrapped.endBatchEdit();
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        Log.d(TAG, "sendKeyEvent(" + event + ")");
        return wrapped.sendKeyEvent(event);
    }

    @Override
    public boolean clearMetaKeyStates(int states) {
        Log.d(TAG, "clearMetaKeyStates(" + states + ")");
        return wrapped.clearMetaKeyStates(states);
    }

    @Override
    public boolean reportFullscreenMode(boolean enabled) {
        Log.d(TAG, "reportFullscreenMode(" + enabled + ")");
        return wrapped.reportFullscreenMode(enabled);
    }

    @Override
    public boolean performSpellCheck() {
        Log.d(TAG, "performSpellCheck()");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return wrapped.performSpellCheck();
        } else return false;
    }

    @Override
    public boolean performPrivateCommand(String action, Bundle data) {
        Log.d(TAG, "performPrivateCommand(" + action + ", " + data + ")");
        return wrapped.performPrivateCommand(action, data);
    }

    @Override
    public void performHandwritingGesture(@NonNull HandwritingGesture gesture, Executor executor, IntConsumer consumer) {
        Log.d(TAG, "performHandwritingGesture(" + gesture + ", " + executor + ", " + consumer + ")");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            wrapped.performHandwritingGesture(gesture, executor, consumer);
        }
    }

    @Override
    public boolean previewHandwritingGesture(@NonNull PreviewableHandwritingGesture gesture, CancellationSignal cancellationSignal) {
        Log.d(TAG, "previewHandwritingGesture(" + gesture + ", " + cancellationSignal + ")");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return wrapped.previewHandwritingGesture(gesture, cancellationSignal);
        } else return false;
    }

    @Override
    public boolean requestCursorUpdates(int cursorUpdateMode) {
        Log.d(TAG, "requestCursorUpdates(" + cursorUpdateMode + ")");
        return wrapped.requestCursorUpdates(cursorUpdateMode);
    }

    @Override
    public boolean requestCursorUpdates(int cursorUpdateMode, int cursorUpdateFilter) {
        Log.d(TAG, "requestCursorUpdates(" + cursorUpdateMode + ", " + cursorUpdateFilter + ")");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return wrapped.requestCursorUpdates(cursorUpdateMode, cursorUpdateFilter);
        } else return false;
    }

    @Override
    public void requestTextBoundsInfo(@NonNull RectF bounds, @NonNull Executor executor, @NonNull Consumer<TextBoundsInfoResult> consumer) {
        Log.d(TAG, "requestTextBoundsInfo(" + bounds + ", " + executor + ", " + consumer + ")");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            wrapped.requestTextBoundsInfo(bounds, executor, consumer);
        }
    }

    @Override
    public Handler getHandler() {
        Log.d(TAG, "getHandler()");
        return wrapped.getHandler();
    }

    @Override
    public void closeConnection() {
        Log.d(TAG, "closeConnection()");
        wrapped.closeConnection();
    }

    @Override
    public boolean commitContent(@NonNull InputContentInfo inputContentInfo, int flags, Bundle opts) {
        Log.d(TAG, "commitContent(" + inputContentInfo + ", " + flags + ", " + opts + ")");
        return wrapped.commitContent(inputContentInfo, flags, opts);
    }

    @Override
    public boolean setImeConsumesInput(boolean imeConsumesInput) {
        Log.d(TAG, "setImeConsumesInput(" + imeConsumesInput + ")");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return wrapped.setImeConsumesInput(imeConsumesInput);
        } else return false;
    }

    @Override
    public TextSnapshot takeSnapshot() {
        Log.d(TAG, "takeSnapshot()");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return wrapped.takeSnapshot();
        } else return null;
    }

    @Override
    public boolean replaceText(int start,
                               int end,
                               @NonNull CharSequence text,
                               int newCursorPosition,
                               TextAttribute textAttribute) {
        Log.d(TAG, "replaceText(" + start + ", " + end + ", " + text + ", " + newCursorPosition + ", " + textAttribute + ")");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return wrapped.replaceText(start, end, text, newCursorPosition, textAttribute);
        } else return false;
    }
}

@Keep @SuppressLint("WrongConstant")
@SuppressWarnings("deprecation")
public class LorieView extends SurfaceView implements InputStub {
    public interface Callback {
        void changed(int surfaceWidth, int surfaceHeight, int screenWidth, int screenHeight);
    }

    interface PixelFormat {
        int BGRA_8888 = 5; // Stands for HAL_PIXEL_FORMAT_BGRA_8888
    }

    private ClipboardManager clipboard;
    private long lastClipboardTimestamp = System.currentTimeMillis();
    private static boolean clipboardSyncEnabled = false;
    private static boolean hardwareKbdScancodesWorkaround = false;
    private final InputMethodManager mIMM = (InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    private Callback mCallback;
    private final Point p = new Point();
    boolean commitedText = false;
    private final InputConnection mConnection = new InputConnectionWrapper(new BaseInputConnection(this, false) {
        private final MainActivity a = MainActivity.getInstance();
        private CharSequence currentComposingText = null;

        @Override public Editable getEditable() { return null; }
        private int mBatchEditNesting = 0;
        @Override
        public boolean beginBatchEdit() {
            synchronized (this) {
                if (mBatchEditNesting >= 0) {
                    mBatchEditNesting++;
                    if (mBatchEditNesting == 1) {
                        resetCursorPosition = false;
                        requestedPos = -1;
                    }
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean endBatchEdit() {
            synchronized (this) {
                if (mBatchEditNesting > 0) {
                    mBatchEditNesting--;
                    if (mBatchEditNesting == 0) {
                        sendCursorPosition();
                        requestedPos = -1;
                    }
                    return mBatchEditNesting > 0;
                }
            }
            return false;
        }

        int currentPos = 1, requestedPos;
        boolean resetCursorPosition;
        void sendCursorPosition() {
            if (resetCursorPosition) {
                mIMM.updateSelection(LorieView.this, -1, -1, -1, -1);
                currentPos = 1;
            }
            mIMM.updateSelection(LorieView.this, currentPos, currentPos, -1, -1);
            Log.d("InputConnectionWrapper", "SENDING CURSOR POS " + currentPos);
        }

        @Override public CharSequence getTextBeforeCursor(int length, int flags) { return " "; }
        @Override public CharSequence getTextAfterCursor(int length, int flags) { return " "; }
        @Override public boolean setComposingRegion(int start, int end) { return true; }

        @Override
        public SurroundingText getSurroundingText(int beforeLength, int afterLength, int flags) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                return new SurroundingText(beforeLength == 0 || afterLength == 0 ? " " : "  ", 1, 1, -1);
            else
                return null;
        }

        void sendKey(int k) {
            LorieView.this.sendKeyEvent(0, k, true);
            LorieView.this.sendKeyEvent(0, k, false);
        }

        @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            if (requestedPos != -1 && requestedPos > currentPos && beforeLength > 0) {
                requestedPos -= beforeLength;
                return true;
            }

            if (beforeLength == 1 && mBatchEditNesting > 0) {
                keyReleaseHandler.removeMessages(KeyEvent.KEYCODE_DEL);
            }

            for (int i=0; i<beforeLength; i++)
                sendKey(KeyEvent.KEYCODE_DEL);
            for (int i=0; i<afterLength; i++)
                sendKey(KeyEvent.KEYCODE_FORWARD_DEL);

            currentPos -= beforeLength;
            if (currentPos <= 1)
                resetCursorPosition = true;

            return true;
        }

        boolean replaceText(CharSequence newText, boolean reuse) {
            int oldLen = currentComposingText != null ? currentComposingText.length() : 0;
            int newLen = newText != null ? newText.length() : 0;
            if (oldLen > 0 && newLen > 0 && (currentComposingText.toString().startsWith(newText.toString())
                    || newText.toString().startsWith(currentComposingText.toString()))) {
                for (int i=0; i < oldLen - newLen; i++)
                    sendKey(KeyEvent.KEYCODE_DEL);
                for (int i=oldLen; i<newLen; i++)
                    sendTextEvent(String.valueOf(newText.charAt(i)).getBytes(UTF_8));
            } else {
                for (int i = 0; i < oldLen; i++)
                    sendKey(KeyEvent.KEYCODE_DEL);
                if (newText != null)
                    sendTextEvent(newText.toString().getBytes(UTF_8));
            }

            currentComposingText = reuse ? newText : null;

            if (a.useTermuxEKBarBehaviour && a.mExtraKeys != null)
                a.mExtraKeys.unsetSpecialKeys();
            commitedText = true;
            return true;
        }

        public boolean setSelection(int start, int end) {
            if (mBatchEditNesting == 0) { 
                if (start == end) {
                    if (start < 1)
                        sendKey(KeyEvent.KEYCODE_DPAD_LEFT);
                    else if (start > 1)
                        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT);
                }

                mIMM.updateSelection(LorieView.this, -1, -1, -1, -1);
                mIMM.updateSelection(LorieView.this, 1, 1, -1, -1);
                currentPos = 1;
            } else if (mBatchEditNesting > 0){
                if (start == end && start > currentPos)
                    requestedPos = start;
            }
            return true;
        }

        @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
            return replaceText(text, true);
        }

        @Override
        public boolean commitText(CharSequence text, int newPos) {
            Log.d("InputConnectionWrapper", newPos + " - 1 + " + currentPos + " + " + text.length());
            Log.d("InputConnectionWrapper", "OLD " + currentPos + " NEW " + Math.max(1, newPos - 1 + currentPos + text.length()) + " mBatchEditNesting " + mBatchEditNesting);
            if (newPos > 0)
                currentPos = Math.max(1, newPos - 1 + currentPos + text.length());
            else
                resetCursorPosition = true;
            if (mBatchEditNesting == 0)
                sendCursorPosition();

            return replaceText(text, false);
        }

        @Override
        public boolean finishComposingText() {
            currentComposingText = null;
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            return LorieView.this.dispatchKeyEvent(event);
        }

        @Override
        public boolean requestCursorUpdates(int cursorUpdateMode) {
            mIMM.updateCursorAnchorInfo(LorieView.this, new CursorAnchorInfo.Builder()
                    .setComposingText(-1, null)
                    .setSelectionRange(currentPos, currentPos)
                    .build());
            return true;
        }

        @Override
        public boolean requestCursorUpdates(int cursorUpdateMode, int cursorUpdateFilter) {
            return requestCursorUpdates(cursorUpdateMode);
        }
    });
    private final SurfaceHolder.Callback mSurfaceCallback = new SurfaceHolder.Callback() {
        @Override public void surfaceCreated(@NonNull SurfaceHolder holder) {
            holder.setFormat(PixelFormat.BGRA_8888);
        }

        @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int f, int width, int height) {
            LorieView.this.surfaceChanged(holder.getSurface());
            width = getMeasuredWidth();
            height = getMeasuredHeight();

            Log.d("SurfaceChangedListener", "Surface was changed: " + width + "x" + height);
            if (mCallback == null)
                return;

            getDimensionsFromSettings();
            if (mCallback != null)
                mCallback.changed(width, height, p.x, p.y);
        }

        @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            LorieView.this.surfaceChanged(null);
            if (mCallback != null)
                mCallback.changed(0, 0, 0, 0);
        }
    };

    public LorieView(Context context) { super(context); init(); }
    public LorieView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public LorieView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }
    @SuppressWarnings("unused")
    public LorieView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) { super(context, attrs, defStyleAttr, defStyleRes); init(); }

    private void init() {
        getHolder().addCallback(mSurfaceCallback);
        clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        nativeInit();
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
        triggerCallback();
    }

    public void triggerCallback() {
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();

        setBackground(new ColorDrawable(Color.TRANSPARENT) {
            public boolean isStateful() {
                return true;
            }
            public boolean hasFocusStateSpecified() {
                return true;
            }
        });

        Rect r = getHolder().getSurfaceFrame();
        MainActivity.getInstance().runOnUiThread(() -> mSurfaceCallback.surfaceChanged(getHolder(), PixelFormat.BGRA_8888, r.width(), r.height()));
    }

    void getDimensionsFromSettings() {
        Prefs prefs = MainActivity.getPrefs();
        int width = getMeasuredWidth();
        int height = getMeasuredHeight();
        int w = width;
        int h = height;
        switch(prefs.displayResolutionMode.get()) {
            case "scaled": {
                int scale = prefs.displayScale.get();
                w = width * 100 / scale;
                h = height * 100 / scale;
                break;
            }
            case "exact": {
                String[] resolution = prefs.displayResolutionExact.get().split("x");
                w = Integer.parseInt(resolution[0]);
                h = Integer.parseInt(resolution[1]);
                break;
            }
            case "custom": {
                try {
                    String[] resolution = prefs.displayResolutionCustom.get().split("x");
                    w = Integer.parseInt(resolution[0]);
                    h = Integer.parseInt(resolution[1]);
                } catch (NumberFormatException | PatternSyntaxException ignored) {
                    w = 1280;
                    h = 1024;
                }
                break;
            }
        }

        if (prefs.adjustResolution.get() && ((width < height && w > h) || (width > height && w < h)))
            p.set(h, w);
        else
            p.set(w, h);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        Prefs prefs = MainActivity.getPrefs();
        if (prefs.displayStretch.get()
              || "native".equals(prefs.displayResolutionMode.get())
              || "scaled".equals(prefs.displayResolutionMode.get())) {
            getHolder().setSizeFromLayout();
            return;
        }

        getDimensionsFromSettings();

        if (p.x <= 0 || p.y <= 0)
            return;

        int width = getMeasuredWidth();
        int height = getMeasuredHeight();

        if (prefs.adjustResolution.get() && ((width < height && p.x > p.y) || (width > height && p.x < p.y)))
            //noinspection SuspiciousNameCombination
            p.set(p.y, p.x);

        if (width > height * p.x / p.y)
            width = height * p.x / p.y;
        else
            height = width * p.y / p.x;

        getHolder().setFixedSize(p.x, p.y);
        setMeasuredDimension(width, height);
    }

    @Override
    public void sendMouseWheelEvent(float deltaX, float deltaY) {
        sendMouseEvent(deltaX, deltaY, BUTTON_SCROLL, false, true);
    }

    // --- ADDED: make sure generic-motion scroll is handled, for wheels/trackpads ---
    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_SCROLL) {
            float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            float h = e.getAxisValue(MotionEvent.AXIS_HSCROLL);

            // Fallback for devices that report 0 unless negated (seen on some vendor stacks)
            if (v == 0 && h == 0) {
                v = -e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                h = -e.getAxisValue(MotionEvent.AXIS_HSCROLL);
            }

            // Keep same inversion/scale as elsewhere in the app
            sendMouseWheelEvent(h * -100f, v * -100f);
            return true;
        }
        return super.onGenericMotionEvent(e);
    }
    // --- END ADDED ---

    static final Set<Integer> imeBuggyKeys = Set.of(
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT
    );

    Handler keyReleaseHandler = new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message msg) {
            if (msg.what != 0)
                sendKeyEvent(0, msg.what, false);
        }
    };

    @Override
    public boolean dispatchKeyEventPreIme(KeyEvent event) {
        if (imeBuggyKeys.contains(event.getKeyCode())) {
            int action = event.getAction();
            if (action == KeyEvent.ACTION_UP)
                keyReleaseHandler.sendEmptyMessageDelayed(event.getKeyCode(), 50);
        }

        if (hardwareKbdScancodesWorkaround)
            return false;

        return MainActivity.getInstance().handleKey(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (imeBuggyKeys.contains(event.getKeyCode())) {
            int action = event.getAction();
            if (action == KeyEvent.ACTION_UP)
                keyReleaseHandler.removeMessages(event.getKeyCode());
        }

        return super.dispatchKeyEvent(event);
    }

    ClipboardManager.OnPrimaryClipChangedListener clipboardListener = this::handleClipboardChange;

    public void reloadPreferences(Prefs p) {
        hardwareKbdScancodesWorkaround = p.hardwareKbdScancodesWorkaround.get();
        clipboardSyncEnabled = p.clipboardEnable.get();
        setClipboardSyncEnabled(clipboardSyncEnabled, clipboardSyncEnabled);
        TouchInputHandler.refreshInputDevices();
    }

    void setClipboardText(String text) {
        clipboard.setPrimaryClip(ClipData.newPlainText("X11 clipboard", text));
        lastClipboardTimestamp = System.currentTimeMillis() + 150;
    }

    void requestClipboard() {
        if (!clipboardSyncEnabled) {
            sendClipboardEvent("".getBytes(UTF_8));
            return;
        }

        CharSequence clip = clipboard.getText();
        if (clip != null) {
            String text = String.valueOf(clipboard.getText());
            sendClipboardEvent(text.getBytes(UTF_8));
            Log.d("CLIP", "sending clipboard contents: " + text);
        }
    }

    public void handleClipboardChange() {
        checkForClipboardChange();
    }

    public void checkForClipboardChange() {
        ClipDescription desc = clipboard.getPrimaryClipDescription();
        if (clipboardSyncEnabled && desc != null &&
                lastClipboardTimestamp < desc.getTimestamp() &&
                desc.getMimeTypeCount() == 1 &&
                (desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                        desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))) {
            lastClipboardTimestamp = desc.getTimestamp();
            sendClipboardAnnounce();
            Log.d("CLIP", "sending clipboard announce");
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        requestFocus();

        if (clipboardSyncEnabled && hasFocus) {
            clipboard.addPrimaryClipChangedListener(clipboardListener);
            checkForClipboardChange();
        } else
            clipboard.removePrimaryClipChangedListener(clipboardListener);

        TouchInputHandler.refreshInputDevices();
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (MainActivity.getPrefs().enforceCharBasedInput.get())
            outAttrs.inputType = InputType.TYPE_NULL;
        else
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_NORMAL;
        outAttrs.actionLabel = "↵";
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return mConnection;
    }

    @Keep void resetIme() {
        if (!commitedText)
            return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            mIMM.invalidateInput(this);
        else
            mIMM.restartInput(this);
    }

    @FastNative private native void nativeInit();
    @FastNative private native void surfaceChanged(Surface surface);
    @FastNative static native void connect(int fd);
    @CriticalNative static native boolean connected();
    @FastNative static native void startLogcat(int fd);
    @FastNative static native void setClipboardSyncEnabled(boolean enabled, boolean ignored);
    @FastNative public native void sendClipboardAnnounce();
    @FastNative public native void sendClipboardEvent(byte[] text);
    @FastNative static native void sendWindowChange(int width, int height, int framerate, String name);
    @FastNative public native void sendMouseEvent(float x, float y, int whichButton, boolean buttonDown, boolean relative);
    @FastNative public native void sendTouchEvent(int action, int id, int x, int y);
    @FastNative public native void sendStylusEvent(float x, float y, int pressure, int tiltX, int tiltY, int orientation, int buttons, boolean eraser, boolean mouseMode);
    @FastNative static public native void requestStylusEnabled(boolean enabled);
    public boolean sendKeyEvent(int scanCode, int keyCode, boolean keyDown) {
        return sendKeyEvent(scanCode, keyCode, keyDown, 0);
    }
    @FastNative public native boolean sendKeyEvent(int scanCode, int keyCode, boolean keyDown, int a);
    @FastNative public native void sendTextEvent(byte[] text);
    @CriticalNative public static native boolean requestConnection();

    static {
        System.loadLibrary("Xlorie");
    }
}
