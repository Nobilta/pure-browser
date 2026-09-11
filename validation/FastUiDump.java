package com.mybrowser.validation;

import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Xml;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONArray;
import org.xmlpull.v1.XmlSerializer;

/** Shell-only UI snapshot: video progress updates must not wait for a 10-second idle period. */
public final class FastUiDump {
    private static int count;

    public static void main(String[] args) throws Exception {
        // API 37's accessibility client constructs a Handler on the main Looper even
        // for shell UiAutomation. app_process has no Application to prepare it for us.
        Looper.prepareMainLooper();
        new Thread(() -> {
            try { run(args); }
            catch (Throwable error) { error.printStackTrace(); System.exit(1); }
        }, "pure-ui-command").start();
        Looper.loop();
    }

    private static void run(String[] args) throws Exception {
        HandlerThread thread = new HandlerThread("pure-ui-snapshot");
        thread.start();
        Class<?> connectionInterface = Class.forName("android.app.IUiAutomationConnection");
        Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
        UiAutomation automation = UiAutomation.class.getConstructor(Looper.class, connectionInterface)
                .newInstance(thread.getLooper(), connection);
        try {
            if (args.length > 0 && args[0].startsWith("a11y")) {
                // Keep the real screen reader active while observing focus or injecting
                // a TalkBack double tap. Ordinary automation still uses its normal mode.
                UiAutomation.class.getMethod("connect", int.class).invoke(automation,
                        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            } else UiAutomation.class.getMethod("connect").invoke(automation);
            if (args.length == 2 && args[0].equals("sequence")) {
                JSONArray events = new JSONArray(new String(Base64.getDecoder().decode(args[1]), StandardCharsets.UTF_8));
                if (events.length() > 256) throw new IllegalArgumentException("Too many input events");
                long started = SystemClock.uptimeMillis();
                for (int i = 0; i < events.length(); i++) {
                    JSONArray event = events.getJSONArray(i);
                    switch (event.getString(0)) {
                        case "tap":
                            tap(automation, (float) event.getDouble(1), (float) event.getDouble(2));
                            break;
                        case "key":
                            long down = SystemClock.uptimeMillis();
                            for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                                if (!automation.injectInputEvent(new KeyEvent(down, SystemClock.uptimeMillis(), action,
                                        event.getInt(1), 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD,
                                        0, 0, InputDevice.SOURCE_KEYBOARD), true))
                                    throw new IllegalStateException("Key injection failed");
                            }
                            break;
                        case "wait":
                            int millis = event.getInt(1);
                            if (millis < 0 || millis > 2000) throw new IllegalArgumentException("Invalid input delay");
                            Thread.sleep(millis);
                            break;
                        default: throw new IllegalArgumentException("Unknown input event");
                    }
                }
                System.out.println("Sequence completed in " + (SystemClock.uptimeMillis() - started) + " ms");
            } else if (args.length == 3 && (args[0].equals("doubleTap") || args[0].equals("a11yDoubleTap"))) {
                doubleTap(automation, Float.parseFloat(args[1]), Float.parseFloat(args[2]));
                System.out.println("Double tapped");
            } else if (args.length == 1 && args[0].equals("selectAll")) {
                long down = SystemClock.uptimeMillis();
                for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                    automation.injectInputEvent(new KeyEvent(down, SystemClock.uptimeMillis(), action,
                            KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON, KeyCharacterMap.VIRTUAL_KEYBOARD,
                            0, 0, InputDevice.SOURCE_KEYBOARD), true);
                }
            } else {
            AccessibilityServiceInfo service = automation.getServiceInfo();
            service.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(service);
            // WebView enables its accessibility tree asynchronously after a service connects.
            Thread.sleep(150);
            AccessibilityNodeInfo root = null;
            for (int i = 0; i < 30 && root == null; i++) {
                root = automation.getRootInActiveWindow();
                if (root == null) Thread.sleep(50);
            }
            if (root == null) throw new IllegalStateException("No active UI root");
            if (args.length == 2 && args[0].equals("setText")) {
                AccessibilityNodeInfo input = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (input == null || !input.isEditable()) {
                    // A non-focusable suggestions Popup may be the active accessibility
                    // window while the actual editor keeps IME focus in the app window.
                    for (android.view.accessibility.AccessibilityWindowInfo window : automation.getWindows()) {
                        AccessibilityNodeInfo candidateRoot = window.getRoot();
                        AccessibilityNodeInfo candidate = candidateRoot == null ? null : candidateRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                        if (candidate != null && candidate.isEditable()) { input = candidate; break; }
                    }
                }
                if (input == null || !input.isEditable()) throw new IllegalStateException("No focused text input");
                Bundle values = new Bundle();
                values.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        new String(Base64.getDecoder().decode(args[1]), StandardCharsets.UTF_8));
                if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, values))
                    throw new IllegalStateException("Text input rejected");
                System.out.println("Text entered");
            } else if (args.length == 2 && (args[0].equals("tap") || args[0].equals("visible"))) {
                JSONArray labels = new JSONArray(new String(Base64.getDecoder().decode(args[1]), StandardCharsets.UTF_8));
                AccessibilityNodeInfo target = findVisible(root, labels, 0);
                // Keep one service connection alive while Chromium creates virtual
                // descendants; reconnecting for each snapshot can restart that work.
                long deadline = SystemClock.uptimeMillis() + 2000;
                while (target == null && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(100);
                    root = automation.getRootInActiveWindow();
                    if (root != null) {
                        root.refresh();
                        target = findVisible(root, labels, 0);
                    }
                }
                if (target != null && args[0].equals("visible")) {
                    System.out.println("Target visible");
                } else if (target != null) {
                    Rect bounds = new Rect();
                    target.getBoundsInScreen(bounds);
                    tap(automation, bounds.exactCenterX(), bounds.exactCenterY());
                    System.out.println("Tapped");
                } else System.out.println("Target not visible");
            } else {
            StringWriter output = new StringWriter();
            XmlSerializer xml = Xml.newSerializer();
            xml.setOutput(output);
            xml.startDocument("UTF-8", true);
            xml.startTag("", "hierarchy");
            if (args.length == 1 && args[0].equals("windows")) {
                for (android.view.accessibility.AccessibilityWindowInfo window : automation.getWindows()) {
                    AccessibilityNodeInfo windowRoot = window.getRoot();
                    if (windowRoot != null) dump(windowRoot, xml, 0);
                }
            } else dump(root, xml, 0);
            xml.endTag("", "hierarchy");
            xml.endDocument();
            System.out.println(output);
            }
            }
        } finally {
            UiAutomation.class.getMethod("disconnect").invoke(automation);
            thread.quitSafely();
        }
        System.exit(0);
    }

    private static AccessibilityNodeInfo findVisible(AccessibilityNodeInfo node, JSONArray labels, int depth) {
        if (depth > 50) return null;
        if (node.isVisibleToUser()) {
            for (int i = 0; i < labels.length(); i++) {
                String label = labels.optString(i);
                if (label.contentEquals(node.getText() == null ? "" : node.getText())
                        || label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())
                        || label.equals(node.getViewIdResourceName())) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    if (!bounds.isEmpty()) return node;
                }
            }
        }
        // Native fullscreen controls and modal sheets are the last/topmost children.
        // Visiting them first avoids a slow WebView tree consuming the hide timeout.
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findVisible(child, labels, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void tap(UiAutomation automation, float x, float y) {
        long down = SystemClock.uptimeMillis();
        for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            if (!automation.injectInputEvent(event, true)) throw new IllegalStateException("Touch injection failed");
            event.recycle();
            SystemClock.sleep(30);
        }
    }

    private static void doubleTap(UiAutomation automation, float x, float y) {
        for (int i = 0; i < 2; i++) {
            long down = SystemClock.uptimeMillis();
            for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                if (!automation.injectInputEvent(event, true)) throw new IllegalStateException("Touch injection failed");
                event.recycle();
                SystemClock.sleep(30);
            }
            SystemClock.sleep(60);
        }
    }

    private static void attribute(XmlSerializer xml, String key, Object value) throws Exception {
        String text = value == null ? "" : value.toString();
        // Website accessibility text can contain characters that XML cannot represent.
        text = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        xml.attribute("", key, text);
    }

    private static void dump(AccessibilityNodeInfo node, XmlSerializer xml, int depth) throws Exception {
        if (depth > 50 || ++count > 4000) return;
        xml.startTag("", "node");
        attribute(xml, "text", node.getText());
        attribute(xml, "content-desc", node.getContentDescription());
        attribute(xml, "resource-id", node.getViewIdResourceName());
        attribute(xml, "class", node.getClassName());
        attribute(xml, "checked", node.isChecked());
        attribute(xml, "checkable", node.isCheckable());
        attribute(xml, "selected", node.isSelected());
        attribute(xml, "clickable", node.isClickable());
        attribute(xml, "enabled", node.isEnabled());
        attribute(xml, "accessibility-focused", node.isAccessibilityFocused());
        attribute(xml, "reported-child-count", node.getChildCount());
        attribute(xml, "scrollable", node.isScrollable());
        attribute(xml, "visible-to-user", node.isVisibleToUser());
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        attribute(xml, "bounds", bounds.toShortString());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) dump(child, xml, depth + 1);
        }
        xml.endTag("", "node");
    }
}
