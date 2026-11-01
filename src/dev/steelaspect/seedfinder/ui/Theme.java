package dev.steelaspect.seedfinder.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.MetalLookAndFeel;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.List;

/** The dark look: colours, fonts and Swing defaults. */
public final class Theme {
    public static final Color BG = new Color(0x16181D);
    public static final Color SIDEBAR = new Color(0x1B1E24);
    public static final Color PANEL = new Color(0x20242B);
    public static final Color FIELD = new Color(0x272C34);
    public static final Color FIELD_HOVER = new Color(0x2E343E);
    public static final Color BORDER = new Color(0x323842);
    public static final Color BORDER_STRONG = new Color(0x464E5B);
    public static final Color TEXT = new Color(0xE7E9EE);
    public static final Color TEXT2 = new Color(0xA3AAB6);
    public static final Color TEXT3 = new Color(0x6F7784);
    public static final Color ACCENT = new Color(0x4682B4);
    public static final Color ACCENT_HOVER = new Color(0x5A97CB);
    public static final Color ACCENT_SOFT = new Color(70, 130, 180, 46);
    public static final Color ON_ACCENT = new Color(0xF4F7FC);
    /** Background of selected rows and text (the accent, dimmed). */
    public static final Color SELECTED = new Color(0x25384B);
    public static final Color SUCCESS = new Color(0x3FB27F);
    public static final Color DANGER = new Color(0xE5534B);
    public static final Color WARNING = new Color(0xD9A441);
    public static final int RADIUS = 8;

    public static final Font FONT;
    public static final Font FONT_SMALL;
    public static final Font FONT_BOLD;
    public static final Font FONT_TITLE;
    public static final Font FONT_MONO;

    static {
        List<String> have = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        String family = Font.SANS_SERIF;
        for (String f : List.of("Inter", "Segoe UI", "Noto Sans", "Cantarell", "DejaVu Sans", "Helvetica Neue"))
            if (have.contains(f)) {
                family = f;
                break;
            }
        FONT = new Font(family, Font.PLAIN, 13);
        FONT_SMALL = new Font(family, Font.PLAIN, 12);
        FONT_BOLD = new Font(family, Font.BOLD, 13);
        FONT_TITLE = new Font(family, Font.BOLD, 16);
        FONT_MONO = new Font(have.contains("JetBrains Mono") ? "JetBrains Mono" : have.contains("Consolas") ? "Consolas" : have.contains("DejaVu Sans Mono") ? "DejaVu Sans Mono" : Font.MONOSPACED, Font.PLAIN, 12);
    }

    private Theme() {}

    public static void install() {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        UIManager.put("swing.boldMetal", Boolean.FALSE);
        try {
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (Exception ignored) {
            // Metal ships with every JDK.
        }
        FontUIResource f = new FontUIResource(FONT);
        for (Object key : UIManager.getLookAndFeelDefaults().keySet().toArray())
            if (key.toString().endsWith(".font")) UIManager.put(key, f);
        put(BG, "Panel.background", "OptionPane.background", "Viewport.background", "SplitPane.background", "RootPane.background");
        put(PANEL, "Table.background", "List.background", "PopupMenu.background", "MenuItem.background", "Menu.background",
                "ToolTip.background", "ScrollPane.background");
        put(TEXT, "Label.foreground", "Table.foreground", "List.foreground", "MenuItem.foreground", "Menu.foreground",
                "OptionPane.messageForeground", "ToolTip.foreground", "CheckBox.foreground", "TextField.foreground",
                "TextArea.foreground", "ComboBox.foreground", "TitledBorder.titleColor", "Button.foreground", "RadioButton.foreground");
        put(FIELD, "TextField.background", "TextArea.background", "ComboBox.background", "FormattedTextField.background");
        put(TEXT, "TextField.caretForeground", "TextArea.caretForeground", "FormattedTextField.foreground");
        put(ACCENT_SOFT_SOLID(), "Table.selectionBackground", "List.selectionBackground", "MenuItem.selectionBackground",
                "ComboBox.selectionBackground", "TextField.selectionBackground", "TextArea.selectionBackground", "Menu.selectionBackground");
        put(TEXT, "Table.selectionForeground", "List.selectionForeground", "MenuItem.selectionForeground", "ComboBox.selectionForeground",
                "TextField.selectionForeground", "Menu.selectionForeground");
        put(BORDER, "Table.gridColor", "Separator.foreground", "SplitPane.dividerFocusColor");
        put(TEXT3, "TextField.inactiveForeground", "Label.disabledForeground", "ComboBox.disabledForeground");
        put(BG, "CheckBox.background", "RadioButton.background");
        UIManager.put("ToolTip.border", BorderFactory.createLineBorder(BORDER));
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(BORDER_STRONG));
        UIManager.put("MenuItem.border", BorderFactory.createEmptyBorder(6, 10, 6, 10));
        UIManager.put("MenuItem.acceleratorForeground", new ColorUIResource(TEXT3));
        UIManager.put("ScrollPane.border", BorderFactory.createEmptyBorder());
        UIManager.put("SplitPane.border", BorderFactory.createEmptyBorder());
        UIManager.put("SplitPaneDivider.border", BorderFactory.createEmptyBorder());
        UIManager.put("Table.focusCellHighlightBorder", BorderFactory.createEmptyBorder(0, 10, 0, 10));
        UIManager.put("OptionPane.buttonFont", f);
        UIManager.put("ComboBox.buttonBackground", new ColorUIResource(FIELD));
        UIManager.put("ComboBox.buttonDarkShadow", new ColorUIResource(TEXT2));
        UIManager.put("Button.background", new ColorUIResource(FIELD));
        UIManager.put("Button.select", new ColorUIResource(FIELD_HOVER));
        UIManager.put("Button.focus", new ColorUIResource(FIELD));
        UIManager.put("FileChooser.listViewBackground", new ColorUIResource(PANEL));
    }

    private static Color ACCENT_SOFT_SOLID() {
        return SELECTED;
    }

    private static void put(Color c, String... keys) {
        for (String k : keys) UIManager.put(k, new ColorUIResource(c));
    }

    public static void pad(JComponent c, int top, int left, int bottom, int right) {
        c.setBorder(BorderFactory.createEmptyBorder(top, left, bottom, right));
    }

}
