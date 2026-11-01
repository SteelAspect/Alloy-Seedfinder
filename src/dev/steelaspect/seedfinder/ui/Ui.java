package dev.steelaspect.seedfinder.ui;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.function.Supplier;

/**
 * The widgets, drawn by hand in the SlimeByte look: square edges, hairline borders, one green accent, monospace
 * text. Every colour is read from {@link Theme} when the widget paints, so switching the theme just repaints.
 */
public final class Ui {
    private Ui() {}

    public static Graphics2D smooth(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g2;
    }

    // ------------------------------------------------------------------ labels

    /** A label whose colour comes from the theme each time it paints (unless one was set on it since). */
    public static final class Label extends JLabel {
        private Supplier<Color> role;
        private Color fixed;

        Label(String text, Font font, Supplier<Color> role) {
            super(text);
            this.role = role;
            setFont(font);
        }

        /** Colour this label by a theme role, e.g. {@code () -> Theme.DANGER}, so it follows theme changes. */
        public void role(Supplier<Color> r) {
            role = r;
            fixed = null;
            repaint();
        }

        @Override
        public void setForeground(Color c) {
            fixed = c;
            super.setForeground(c);
        }

        @Override
        public Color getForeground() {
            return fixed != null ? fixed : role != null ? role.get() : super.getForeground();
        }
    }

    public static Label label(String text) {
        return new Label(text, Theme.FONT, () -> Theme.TEXT);
    }

    public static Label muted(String text) {
        return new Label(text, Theme.FONT_SMALL, () -> Theme.TEXT2);
    }

    public static Label title(String text) {
        return new Label(text, Theme.FONT_TITLE, () -> Theme.TEXT);
    }

    /** Muted text that wraps to the width it's given (a form's description). */
    public static javax.swing.JTextArea paragraph(String text) {
        javax.swing.JTextArea t = new javax.swing.JTextArea(text) {
            @Override
            public Color getForeground() {
                return Theme.TEXT2 == null ? super.getForeground() : Theme.TEXT2;
            }
        };
        t.setFont(Theme.FONT_SMALL);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setEditable(false);
        t.setFocusable(false);
        t.setOpaque(false);
        t.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));
        return t;
    }

    /** Bold, in the accent colour: a value that matters. */
    public static Label strong(String text) {
        return new Label(text, Theme.FONT_BOLD, () -> Theme.TEXT);
    }

    // ------------------------------------------------------------------ panels

    public static JPanel row(int gap, Component... parts) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, gap, 0));
        p.setOpaque(false);
        for (Component c : parts) p.add(c);
        return p;
    }

    public static JPanel clear(LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.setOpaque(false);
        return p;
    }

    /** A column of parts, each as wide as the column. */
    public static JPanel column(int gap, Component... parts) {
        JPanel p = clear(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, gap, 0);
        for (Component part : parts) p.add(part, c);
        return p;
    }

    /**
     * A card: the panel colour, a hairline edge and, with a title, a bold title row with a line under it, like
     * SlimeByte's "World and search" box.
     */
    public static class Card extends JPanel {
        private final String title;
        private JComponent titleRight;
        /** Height of the title row (title text sits in it). */
        public static final int TITLE_H = 34;

        public Card(LayoutManager layout) {
            this(null, layout);
        }

        public Card(String title, LayoutManager layout) {
            super(layout);
            this.title = title;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(title == null ? 12 : TITLE_H + 12, 14, 12, 14));
        }

        /** Something in the title row's right end (a small toggle, a count). */
        public void titleRight(JComponent c) {
            titleRight = c;
            c.setBounds(0, 0, 0, 0);
            add(c);
        }

        @Override
        public void doLayout() {
            super.doLayout();
            if (titleRight != null) {
                Dimension d = titleRight.getPreferredSize();
                titleRight.setBounds(getWidth() - 14 - d.width, (TITLE_H - d.height) / 2 + 1, d.width, d.height);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            g2.setColor(Theme.PANEL);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(Theme.BORDER);
            g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            if (title != null) {
                g2.setFont(Theme.FONT_BOLD);
                g2.setColor(Theme.TEXT);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(title, 14, (TITLE_H + fm.getAscent() - fm.getDescent()) / 2f + 1);
                g2.setColor(Theme.BORDER);
                g2.drawLine(1, TITLE_H, getWidth() - 2, TITLE_H);
            }
            g2.dispose();
        }
    }

    /** A stat tile: small label, bold value, a muted note under it (the three tiles over SlimeByte's map). */
    public static final class Tile extends JComponent {
        private String label, value = "–", note = "";

        public Tile(String label) {
            this.label = label;
            setPreferredSize(new Dimension(200, 66));
        }

        public void set(String value, String note) {
            this.value = value == null || value.isEmpty() ? "–" : value;
            this.note = note == null ? "" : note;
            repaint();
        }

        public void label(String l) {
            label = l;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            g2.setColor(Theme.STAT_BG);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(Theme.STAT_EDGE);
            g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            g2.setFont(Theme.FONT_SMALL);
            g2.setColor(Theme.STAT_LABEL);
            g2.drawString(label, 12, 18);
            g2.setFont(Theme.FONT_BOLD);
            g2.setColor(Theme.STAT_ACCENT);
            g2.drawString(clip(g2, value, getWidth() - 24), 12, 38);
            g2.setFont(Theme.FONT_SMALL);
            g2.setColor(Theme.STAT_LABEL);
            g2.drawString(clip(g2, note, getWidth() - 24), 12, 56);
            g2.dispose();
        }
    }

    static String clip(Graphics2D g2, String s, int width) {
        FontMetrics fm = g2.getFontMetrics();
        if (fm.stringWidth(s) <= width) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > width) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    // ------------------------------------------------------------------ buttons

    public enum Kind { PRIMARY, SECONDARY, GHOST }

    /** Square buttons: the primary one is a green block with dark text, the others a hairline box. */
    public static class FlatButton extends JButton {
        private final Kind kind;
        private boolean hover, active;

        /** Shows the button as the chosen one of a group (accent text and edge). */
        public void setActive(boolean on) {
            active = on;
            repaint();
        }

        public FlatButton(String text, Icon icon, Kind kind) {
            super(text, icon);
            this.kind = kind;
            setFont(kind == Kind.PRIMARY ? Theme.FONT_BOLD : Theme.FONT);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setIconTextGap(7);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMargin(new Insets(0, 0, 0, 0));
            setBorder(BorderFactory.createEmptyBorder(0, text == null || text.isEmpty() ? 8 : 14, 0, text == null || text.isEmpty() ? 8 : 14));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(d.width, 34);
        }

        @Override
        public Color getForeground() {
            if (!isEnabled()) return Theme.TEXT3;
            if (active) return Theme.ACCENT_HOVER;
            return kind == Kind.PRIMARY ? Theme.ON_ACCENT : hover ? Theme.TEXT : Theme.TEXT;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            boolean on = isEnabled();
            int w = getWidth(), h = getHeight();
            switch (kind) {
                case PRIMARY -> {
                    g2.setColor(!on ? Theme.PANEL2 : hover || getModel().isPressed() ? Theme.ACCENT_HOVER : Theme.ACCENT);
                    g2.fillRect(0, 0, w, h);
                }
                case SECONDARY -> {
                    g2.setColor(active ? Theme.ACCENT_SOFT : hover && on ? Theme.FIELD_HOVER : Theme.PANEL);
                    g2.fillRect(0, 0, w, h);
                    g2.setColor(active ? Theme.SELECTED_EDGE : hover && on ? Theme.BORDER_STRONG : Theme.FIELD_EDGE);
                    g2.drawRect(0, 0, w - 1, h - 1);
                }
                case GHOST -> {
                    if (active || (hover && on)) {
                        g2.setColor(active ? Theme.ACCENT_SOFT : Theme.FIELD_HOVER);
                        g2.fillRect(0, 0, w, h);
                    }
                }
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** icon: kept for callers; this look draws buttons as text only. */
    public static FlatButton button(String text, String icon, Kind kind) {
        return new FlatButton(text == null || text.isEmpty() ? iconText(icon) : text, null, kind);
    }

    private static String iconText(String icon) {
        return icon == null ? "" : switch (icon) {
            case "plus" -> "+";
            case "x" -> "×";
            case "trash" -> "del";
            default -> icon;
        };
    }

    // ------------------------------------------------------------------ inputs

    /** Square field edge, accent-coloured while focused. */
    public static final class FieldBorder extends AbstractBorder {
        boolean focused;

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = smooth(g);
            g2.setColor(focused ? Theme.ACCENT : Theme.FIELD_EDGE);
            g2.drawRect(x, y, w - 1, h - 1);
            if (focused) g2.drawRect(x + 1, y + 1, w - 3, h - 3);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(6, 10, 6, 10);
        }
    }

    public static JTextField field(String text, int columns) {
        JTextField f = new JTextField(text, columns) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Theme.FIELD);
                g.fillRect(0, 0, getWidth(), getHeight());
                super.paintComponent(g);
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(super.getPreferredSize().width, 34);
            }

            @Override
            public Color getForeground() {
                return Theme.TEXT == null ? super.getForeground() : Theme.TEXT;
            }

            @Override
            public Color getCaretColor() {
                return Theme.TEXT == null ? super.getCaretColor() : Theme.TEXT;
            }

            @Override
            public Color getSelectionColor() {
                return Theme.SELECTED == null ? super.getSelectionColor() : Theme.SELECTED;
            }
        };
        f.setOpaque(false);
        f.setFont(Theme.FONT);
        FieldBorder border = new FieldBorder();
        f.setBorder(border);
        f.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                border.focused = true;
                f.repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                border.focused = false;
                f.repaint();
            }
        });
        return f;
    }

    public static <T> JComboBox<T> combo(T[] items) {
        JComboBox<T> c = new JComboBox<>(items) {
            @Override
            public Color getForeground() {
                return Theme.TEXT == null ? super.getForeground() : Theme.TEXT;
            }
        };
        c.setUI(new FlatComboUI());
        c.setFont(Theme.FONT);
        c.setOpaque(false);
        c.setBorder(new FieldBorder());
        c.setMaximumRowCount(16);
        ListCellRenderer<? super T> base = c.getRenderer();
        c.setRenderer((list, value, index, selected, focus) -> {
            Component comp = base.getListCellRendererComponent(list, value, index, selected, focus);
            if (comp instanceof JLabel l) {
                l.setFont(Theme.FONT);
                l.setBorder(BorderFactory.createEmptyBorder(5, index < 0 ? 0 : 10, 5, 10));
                l.setForeground(selected && index >= 0 ? Theme.ACCENT_HOVER : Theme.TEXT);
                l.setBackground(selected && index >= 0 ? Theme.SELECTED : index < 0 ? Theme.FIELD : Theme.PANEL);
                l.setOpaque(index >= 0);
            }
            return comp;
        });
        return c;
    }

    static final class FlatComboUI extends BasicComboBoxUI {
        @Override
        protected JButton createArrowButton() {
            JButton b = new JButton() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = smooth(g);
                    g2.setColor(Theme.TEXT2);
                    int cx = getWidth() / 2, cy = getHeight() / 2;
                    Path2D p = new Path2D.Float();
                    p.moveTo(cx - 4, cy - 2);
                    p.lineTo(cx + 4, cy - 2);
                    p.lineTo(cx, cy + 3);
                    p.closePath();
                    g2.fill(p);
                    g2.dispose();
                }
            };
            b.setContentAreaFilled(false);
            b.setBorderPainted(false);
            b.setFocusPainted(false);
            b.setOpaque(false);
            return b;
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            g.setColor(Theme.FIELD);
            g.fillRect(0, 0, c.getWidth(), c.getHeight());
            super.paint(g, c);
        }

        @Override
        public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {}

        @Override
        public Dimension getMinimumSize(JComponent c) {
            Dimension d = super.getMinimumSize(c);
            return new Dimension(d.width, 34);
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            Dimension d = super.getPreferredSize(c);
            return new Dimension(d.width + 6, 34);
        }

        @Override
        protected javax.swing.plaf.basic.ComboPopup createPopup() {
            javax.swing.plaf.basic.BasicComboPopup p = new javax.swing.plaf.basic.BasicComboPopup(comboBox) {
                @Override
                protected void configurePopup() {
                    super.configurePopup();
                    setBorder(BorderFactory.createLineBorder(Theme.BORDER_STRONG));
                }
            };
            return p;
        }
    }

    public static JCheckBox check(String text, boolean on) {
        JCheckBox b = new JCheckBox(text, on) {
            @Override
            public Color getForeground() {
                return Theme.TEXT == null ? super.getForeground() : Theme.TEXT;
            }
        };
        b.setOpaque(false);
        b.setFont(Theme.FONT);
        b.setFocusPainted(false);
        b.setIconTextGap(8);
        b.setIcon(new CheckIcon(false));
        b.setSelectedIcon(new CheckIcon(true));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private record CheckIcon(boolean on) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = smooth(g);
            g2.setColor(on ? Theme.ACCENT : Theme.FIELD);
            g2.fillRect(x, y, 16, 16);
            g2.setColor(on ? Theme.ACCENT : Theme.FIELD_EDGE);
            g2.drawRect(x, y, 15, 15);
            if (on) {
                g2.setColor(Theme.ON_ACCENT);
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D p = new Path2D.Float();
                p.moveTo(x + 4, y + 8.5);
                p.lineTo(x + 7, y + 11.5);
                p.lineTo(x + 12, y + 5);
                g2.draw(p);
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }

    // ------------------------------------------------------------------ scrolling and progress

    public static JScrollPane scroll(Component view) {
        JScrollPane s = new JScrollPane(view);
        s.setBorder(BorderFactory.createEmptyBorder());
        s.setViewport(new JViewport() {
            @Override
            public Color getBackground() {
                return Theme.PANEL == null ? super.getBackground() : Theme.PANEL;
            }
        });
        s.setViewportView(view);
        s.setOpaque(false);
        s.getVerticalScrollBar().setUI(new FlatScrollBarUI());
        s.getHorizontalScrollBar().setUI(new FlatScrollBarUI());
        s.getVerticalScrollBar().setPreferredSize(new Dimension(10, 10));
        s.getHorizontalScrollBar().setPreferredSize(new Dimension(10, 10));
        s.getVerticalScrollBar().setUnitIncrement(24);
        JPanel corner = clear(null);
        s.setCorner(JScrollPane.UPPER_RIGHT_CORNER, corner);
        return s;
    }

    static final class FlatScrollBarUI extends BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
            trackColor = Theme.PANEL;
            thumbColor = Theme.BORDER_STRONG;
        }

        @Override
        protected JButton createDecreaseButton(int o) {
            return zero();
        }

        @Override
        protected JButton createIncreaseButton(int o) {
            return zero();
        }

        private static JButton zero() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            return b;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(Theme.PANEL);
            g.fillRect(r.x, r.y, r.width, r.height);
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty()) return;
            g.setColor(isThumbRollover() ? Theme.TEXT3 : Theme.BORDER_STRONG);
            boolean v = scrollbar.getOrientation() == JScrollBar.VERTICAL;
            g.fillRect(r.x + (v ? 3 : 0), r.y + (v ? 0 : 3), r.width - (v ? 6 : 0), r.height - (v ? 0 : 6));
        }
    }

    /** A thin progress bar in the accent colour. */
    public static final class Progress extends JComponent {
        private double value;
        private Supplier<Color> color = () -> Theme.ACCENT;

        public Progress() {
            setPreferredSize(new Dimension(100, 6));
        }

        public void set(double v, Color c) {
            value = Math.max(0, Math.min(1, v));
            Color fixed = c;
            color = () -> fixed;
            repaint();
        }

        /** Like set, but the colour follows the theme. */
        public void set(double v, Supplier<Color> c) {
            value = Math.max(0, Math.min(1, v));
            color = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            int h = getHeight();
            g.setColor(Theme.PANEL2);
            g.fillRect(0, 0, getWidth(), h);
            g.setColor(color.get());
            g.fillRect(0, 0, (int) (getWidth() * value), h);
        }
    }

    /** A form field: bold label over the input. */
    public static JPanel labelled(String label, JComponent input) {
        return labelled(label, input, null);
    }

    /** A form field with a muted help line under it, like SlimeByte's forms. */
    public static JPanel labelled(String label, JComponent input, String help) {
        JPanel p = clear(new BorderLayout(0, 4));
        Label l = new Label(label, Theme.FONT_BOLD, () -> Theme.TEXT);
        p.add(l, BorderLayout.NORTH);
        p.add(input, BorderLayout.CENTER);
        if (help != null && !help.isEmpty()) {
            Label h = muted(help);
            Theme.pad(h, 2, 0, 0, 0);
            p.add(h, BorderLayout.SOUTH);
        }
        return p;
    }

    /** "key  what it does" for the footer, the key in bold. */
    public static JPanel hint(String key, String what) {
        Label k = new Label(key, Theme.FONT_BOLD, () -> Theme.TEXT);
        Label w = muted(what);
        return row(6, k, w);
    }

    /** A small text toggle like SlimeByte's "Light  Dark": the chosen word in the accent, the other muted. */
    public static JPanel textToggle(String[] options, int chosen, java.util.function.IntConsumer onPick) {
        JPanel p = row(14);
        AbstractButton[] bs = new AbstractButton[options.length];
        for (int i = 0; i < options.length; i++) {
            int idx = i;
            JButton b = new JButton(options[i]) {
                @Override
                public Color getForeground() {
                    return getClientProperty("on") == Boolean.TRUE ? Theme.ACCENT_HOVER : Theme.TEXT2;
                }

                @Override
                public Font getFont() {
                    return getClientProperty("on") == Boolean.TRUE ? Theme.FONT_BOLD : Theme.FONT;
                }
            };
            b.putClientProperty("on", i == chosen);
            b.setContentAreaFilled(false);
            b.setBorderPainted(false);
            b.setFocusPainted(false);
            b.setOpaque(false);
            b.setMargin(new Insets(0, 0, 0, 0));
            b.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 2));
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.addActionListener(e -> {
                for (int j = 0; j < bs.length; j++) bs[j].putClientProperty("on", j == idx);
                p.repaint();
                onPick.accept(idx);
            });
            bs[i] = b;
            p.add(b);
        }
        return p;
    }

    /** A legend entry: a coloured square and a word. */
    public static JComponent legend(Supplier<Color> color, String text, boolean round) {
        JComponent swatch = new JComponent() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(10, 10);
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = smooth(g);
                g2.setColor(color.get());
                if (round) g2.fillOval(0, 0, 10, 10);
                else g2.fillRect(0, 0, 10, 10);
                g2.dispose();
            }
        };
        JPanel wrap = clear(new java.awt.GridBagLayout());
        wrap.add(swatch);
        Label l = muted(text);
        l.setHorizontalAlignment(SwingConstants.LEFT);
        return row(6, wrap, l);
    }

    /** Lays children out top to bottom, each as wide as the panel, with a gap between them. */
    public static final class Stack implements LayoutManager {
        private final int gap;

        public Stack(int gap) {
            this.gap = gap;
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {}

        @Override
        public void removeLayoutComponent(Component comp) {}

        @Override
        public Dimension preferredLayoutSize(java.awt.Container parent) {
            // Width comes from the parent (children stretch to it); height is what the children need at that width.
            Insets in = parent.getInsets();
            int w = Math.max(10, parent.getWidth() - in.left - in.right), h = 0, n = 0;
            for (Component c : parent.getComponents()) {
                if (!c.isVisible()) continue;
                c.setSize(w, Math.max(1, c.getHeight()));
                h += c.getPreferredSize().height + (n++ > 0 ? gap : 0);
            }
            return new Dimension(in.left + in.right + 10, h + in.top + in.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(java.awt.Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(java.awt.Container parent) {
            Insets in = parent.getInsets();
            int w = parent.getWidth() - in.left - in.right, y = in.top;
            for (Component c : parent.getComponents()) {
                if (!c.isVisible()) continue;
                // Preferred height at this width (labels with <html> wrap).
                c.setSize(w, c.getPreferredSize().height);
                int h = c.getPreferredSize().height;
                c.setBounds(in.left, y, w, h);
                y += h + gap;
            }
        }
    }
}
