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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The look: the colours of kyoubitt's SlimeByte (which took them from the RedenMC slime farm generator), in its
 * light and dark versions, with everything in a monospace face like a terminal. Colours are fields that
 * {@link #apply} swaps, so the theme can change while the app runs: painting code reads them when it draws, and
 * the widgets in {@link Ui} ask for their colours at paint time too.
 */
public final class Theme {
    // Page and cards
    public static Color BG, PANEL, PANEL2, BORDER, BORDER_STRONG;
    /** The header and footer strips. */
    public static Color SIDEBAR;
    public static Color TEXT, TEXT2, TEXT3;
    /** The one accent: main buttons, the tab you're on, focus. ACCENT_HOVER is the stronger version. */
    public static Color ACCENT, ACCENT_HOVER, ACCENT_SOFT, ON_ACCENT;
    /** The chosen row of a table or list, and its edge. */
    public static Color SELECTED, SELECTED_EDGE;
    public static Color FIELD, FIELD_HOVER, FIELD_EDGE;
    public static Color SUCCESS, DANGER, WARNING, VIOLET;
    /** Stat tiles over the map. */
    public static Color STAT_BG, STAT_EDGE, STAT_LABEL, STAT_ACCENT;
    /** The footer's status box. */
    public static Color TOAST_BG, TOAST_TEXT;
    /** Terminal look: no rounding. */
    public static final int RADIUS = 0;

    /** The map keeps its own dark colours in both themes (SlimeByte does the same). */
    public static final Color MAP_BG = new Color(0x0f1712), MAP_PANEL = new Color(22, 32, 26, 225), MAP_TEXT = new Color(0xdbe5dd),
            MAP_TEXT2 = new Color(0x91a295), MAP_EDGE = new Color(0x2e4136), MAP_ACCENT = new Color(0x7cc08f), MAP_VIOLET = new Color(0xb4a2dc),
            MAP_AMBER = new Color(0xe2b46a), MAP_ON_ACCENT = new Color(0x0c1710);

    public static final Font FONT, FONT_SMALL, FONT_BOLD, FONT_TITLE, FONT_MONO, FONT_BIG;

    static {
        List<String> have = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        String family = Font.MONOSPACED;
        for (String f : List.of("JetBrains Mono", "Cascadia Mono", "Cascadia Code", "SF Mono", "Menlo", "Consolas", "Fira Code", "Fira Mono",
                "Noto Sans Mono", "DejaVu Sans Mono", "Ubuntu Mono", "Liberation Mono"))
            if (have.contains(f)) {
                family = f;
                break;
            }
        FONT = new Font(family, Font.PLAIN, 13);
        FONT_SMALL = new Font(family, Font.PLAIN, 12);
        FONT_BOLD = new Font(family, Font.BOLD, 13);
        FONT_TITLE = new Font(family, Font.BOLD, 14);
        FONT_BIG = new Font(family, Font.BOLD, 15);
        FONT_MONO = FONT;
    }

    private static String current = "dark";
    private static final List<Runnable> listeners = new ArrayList<>();

    private Theme() {}

    public static String current() {
        return current;
    }

    /** Called after a theme change, on the event thread (the app repaints and relays out). */
    public static void onChange(Runnable r) {
        listeners.add(r);
    }

    /** Sets every colour for "light" or "dark" and the Swing defaults with them. */
    public static void apply(String name) {
        current = "light".equals(name) ? "light" : "dark";
        if (current.equals("light")) {
            BG = c(0xf1f3ee);
            PANEL = c(0xffffff);
            PANEL2 = c(0xe9eee6);
            SIDEBAR = c(0xf1f3ee);
            BORDER = c(0xd5dcd2);
            BORDER_STRONG = c(0xb5c0b2);
            TEXT = c(0x26352b);
            TEXT2 = c(0x5f6d62);
            TEXT3 = c(0x8a978d);
            ACCENT = c(0x32623c);
            ACCENT_HOVER = c(0x244e2d);
            ACCENT_SOFT = c(0xeaf2e4);
            ON_ACCENT = c(0xffffff);
            SELECTED = c(0xeaf2e4);
            SELECTED_EDGE = c(0x91ac84);
            FIELD = c(0xffffff);
            FIELD_HOVER = c(0xedf3e8);
            FIELD_EDGE = c(0xb5c0b2);
            VIOLET = c(0x655080);
            WARNING = c(0x805716);
            DANGER = c(0xa93232);
            SUCCESS = c(0x32623c);
            STAT_BG = c(0xe5ecdf);
            STAT_EDGE = c(0xccd8c4);
            STAT_LABEL = c(0x56674f);
            STAT_ACCENT = c(0x294f31);
            TOAST_BG = c(0x294d33);
            TOAST_TEXT = c(0xffffff);
        } else {
            BG = c(0x0f1712);
            PANEL = c(0x16201a);
            PANEL2 = c(0x1c2821);
            SIDEBAR = c(0x0f1712);
            BORDER = c(0x2a3830);
            BORDER_STRONG = c(0x3b4c41);
            TEXT = c(0xdbe5dd);
            TEXT2 = c(0x91a295);
            TEXT3 = c(0x5f6f63);
            ACCENT = c(0x5c9f6e);
            ACCENT_HOVER = c(0x7cc08f);
            ACCENT_SOFT = c(0x1f3626);
            ON_ACCENT = c(0x0c1710);
            SELECTED = c(0x1f3626);
            SELECTED_EDGE = c(0x4f8a5f);
            FIELD = c(0x16201a);
            FIELD_HOVER = c(0x213027);
            FIELD_EDGE = c(0x3b4c41);
            VIOLET = c(0xb4a2dc);
            WARNING = c(0xe2b46a);
            DANGER = c(0xee7d70);
            SUCCESS = c(0x7cc08f);
            STAT_BG = c(0x1b2921);
            STAT_EDGE = c(0x2e4136);
            STAT_LABEL = c(0x91a295);
            STAT_ACCENT = c(0x9dd8ad);
            TOAST_BG = c(0x22392b);
            TOAST_TEXT = c(0xa9dcb5);
        }
        defaults();
        for (Runnable r : listeners) r.run();
    }

    private static Color c(int rgb) {
        return new Color(rgb);
    }

    public static void install() {
        install("dark");
    }

    /** Once: the look and feel and fonts. Then the colours for the saved theme. */
    public static void install(String theme) {
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
        UIManager.put("OptionPane.buttonFont", f);
        UIManager.put("ToolTip.border", BorderFactory.createEmptyBorder(6, 8, 6, 8));
        UIManager.put("MenuItem.border", BorderFactory.createEmptyBorder(6, 10, 6, 10));
        UIManager.put("ScrollPane.border", BorderFactory.createEmptyBorder());
        UIManager.put("SplitPane.border", BorderFactory.createEmptyBorder());
        UIManager.put("SplitPaneDivider.border", BorderFactory.createEmptyBorder());
        UIManager.put("Table.focusCellHighlightBorder", BorderFactory.createEmptyBorder(0, 10, 0, 10));
        apply(theme);
    }

    private static void defaults() {
        put(BG, "Panel.background", "OptionPane.background", "Viewport.background", "SplitPane.background", "RootPane.background",
                "CheckBox.background", "RadioButton.background");
        put(PANEL, "Table.background", "List.background", "PopupMenu.background", "MenuItem.background", "Menu.background",
                "ToolTip.background", "ScrollPane.background", "FileChooser.listViewBackground");
        put(TEXT, "Label.foreground", "Table.foreground", "List.foreground", "MenuItem.foreground", "Menu.foreground",
                "OptionPane.messageForeground", "ToolTip.foreground", "CheckBox.foreground", "TextField.foreground",
                "TextArea.foreground", "ComboBox.foreground", "TitledBorder.titleColor", "Button.foreground", "RadioButton.foreground",
                "TextField.caretForeground", "TextArea.caretForeground", "FormattedTextField.foreground",
                "Table.selectionForeground", "List.selectionForeground", "MenuItem.selectionForeground", "ComboBox.selectionForeground",
                "TextField.selectionForeground", "Menu.selectionForeground");
        put(FIELD, "TextField.background", "TextArea.background", "ComboBox.background", "FormattedTextField.background",
                "ComboBox.buttonBackground", "Button.background", "Button.focus");
        put(SELECTED, "Table.selectionBackground", "List.selectionBackground", "MenuItem.selectionBackground",
                "ComboBox.selectionBackground", "TextField.selectionBackground", "TextArea.selectionBackground", "Menu.selectionBackground");
        put(BORDER, "Table.gridColor", "Separator.foreground", "SplitPane.dividerFocusColor");
        put(TEXT3, "TextField.inactiveForeground", "Label.disabledForeground", "ComboBox.disabledForeground", "MenuItem.acceleratorForeground");
        put(TEXT2, "ComboBox.buttonDarkShadow");
        put(FIELD_HOVER, "Button.select");
        UIManager.put("ToolTip.border", BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER_STRONG), BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(BORDER_STRONG));
    }

    private static void put(Color c, String... keys) {
        for (String k : keys) UIManager.put(k, new ColorUIResource(c));
    }

    public static void pad(JComponent c, int top, int left, int bottom, int right) {
        c.setBorder(BorderFactory.createEmptyBorder(top, left, bottom, right));
    }
}
