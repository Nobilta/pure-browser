package com.mybrowser.validation;

import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.StringWriter;
import org.xmlpull.v1.XmlSerializer;

/** Shell-only UI snapshot: video progress updates must not wait for a 10-second idle period. */
public final class FastUiDump {
    private static int count;

    public static void main(String[] args) throws Exception {
        HandlerThread thread = new HandlerThread("pure-ui-snapshot");
        thread.start();
        Class<?> connectionInterface = Class.forName("android.app.IUiAutomationConnection");
        Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
        UiAutomation automation = UiAutomation.class.getConstructor(Looper.class, connectionInterface)
                .newInstance(thread.getLooper(), connection);
        try {
            UiAutomation.class.getMethod("connect").invoke(automation);
            AccessibilityServiceInfo service = automation.getServiceInfo();
            service.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            automation.setServiceInfo(service);
            // WebView enables its accessibility tree asynchronously after a service connects.
            Thread.sleep(150);
            AccessibilityNodeInfo root = null;
            for (int i = 0; i < 10 && root == null; i++) {
                root = automation.getRootInActiveWindow();
                if (root == null) Thread.sleep(50);
            }
            if (root == null) throw new IllegalStateException("No active UI root");
            StringWriter output = new StringWriter();
            XmlSerializer xml = Xml.newSerializer();
            xml.setOutput(output);
            xml.startDocument("UTF-8", true);
            xml.startTag("", "hierarchy");
            dump(root, xml, 0);
            xml.endTag("", "hierarchy");
            xml.endDocument();
            System.out.println(output);
        } finally {
            UiAutomation.class.getMethod("disconnect").invoke(automation);
            thread.quitSafely();
        }
        System.exit(0);
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
        attribute(xml, "clickable", node.isClickable());
        attribute(xml, "enabled", node.isEnabled());
        attribute(xml, "scrollable", node.isScrollable());
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
