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
import java.awt.geom.RoundRectangle2D;

/** Small flat widgets drawn in the app's own style. */
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

    public static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Theme.TEXT);
        l.setFont(Theme.FONT);
        return l;
    }

    public static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Theme.TEXT2);
        l.setFont(Theme.FONT_SMALL);
        return l;
    }

    public static JLabel title(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Theme.TEXT);
        l.setFont(Theme.FONT_TITLE);
        return l;
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

    /** A rounded panel with the card colour and a hairline border. */
    public static class Card extends JPanel {
        private final Color fill;

        public Card(LayoutManager layout) {
            this(layout, Theme.PANEL);
        }

        public Card(LayoutManager layout, Color fill) {
            super(layout);
            this.fill = fill;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            g2.setColor(fill);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 12, 12));
            g2.setColor(Theme.BORDER);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 2, getHeight() - 2, 12, 12));
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ buttons

    public enum Kind { PRIMARY, SECONDARY, GHOST }

    public static class FlatButton extends JButton {
        private final Kind kind;
        private boolean hover, active;

        /** Shows the button as the chosen one of a group (tinted, accent text). */
        public void setActive(boolean on) {
            active = on;
            repaint();
        }

        public FlatButton(String text, Icon icon, Kind kind) {
            super(text, icon);
            this.kind = kind;
            setFont(kind == Kind.PRIMARY ? Theme.FONT_BOLD : Theme.FONT);
            setForeground(kind == Kind.PRIMARY ? Theme.ON_ACCENT : Theme.TEXT);
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
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            boolean on = isEnabled();
            Color fill = active ? Theme.ACCENT_SOFT : switch (kind) {
                case PRIMARY -> !on ? Theme.FIELD : hover || getModel().isPressed() ? Theme.ACCENT_HOVER : Theme.ACCENT;
                case SECONDARY -> hover && on ? Theme.FIELD_HOVER : Theme.FIELD;
                case GHOST -> hover && on ? Theme.FIELD : null;
            };
            if (fill != null) {
                g2.setColor(fill);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), Theme.RADIUS * 2, Theme.RADIUS * 2));
            }
            if (kind == Kind.SECONDARY) {
                g2.setColor(active ? Theme.ACCENT : hover && on ? Theme.BORDER_STRONG : Theme.BORDER);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, Theme.RADIUS * 2, Theme.RADIUS * 2));
            }
            g2.dispose();
            setForeground(!on ? Theme.TEXT3 : active ? Theme.ACCENT_HOVER : kind == Kind.PRIMARY ? Theme.ON_ACCENT : Theme.TEXT);
            super.paintComponent(g);
        }
    }

    public static FlatButton button(String text, String icon, Kind kind) {
        Color c = kind == Kind.PRIMARY ? Theme.ON_ACCENT : Theme.TEXT2;
        return new FlatButton(text, icon == null ? null : Icons.get(icon, 15, c), kind);
    }

    // ------------------------------------------------------------------ inputs

    /** Rounded field border that turns accent-coloured while focused. */
    public static final class FieldBorder extends AbstractBorder {
        boolean focused;

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = smooth(g);
            g2.setColor(focused ? Theme.ACCENT : Theme.BORDER);
            g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1, Theme.RADIUS * 2, Theme.RADIUS * 2));
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
                Graphics2D g2 = smooth(g);
                g2.setColor(Theme.FIELD);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), Theme.RADIUS * 2, Theme.RADIUS * 2));
                g2.dispose();
                super.paintComponent(g);
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(super.getPreferredSize().width, 34);
            }
        };
        f.setOpaque(false);
        f.setFont(Theme.FONT);
        f.setForeground(Theme.TEXT);
        f.setCaretColor(Theme.TEXT);
        f.setSelectionColor(new Color(0x4A3125));
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
        JComboBox<T> c = new JComboBox<>(items);
        c.setUI(new FlatComboUI());
        c.setFont(Theme.FONT);
        c.setForeground(Theme.TEXT);
        c.setBackground(Theme.FIELD);
        c.setOpaque(false);
        c.setBorder(new FieldBorder());
        c.setMaximumRowCount(16);
        ListCellRenderer<? super T> base = c.getRenderer();
        c.setRenderer((list, value, index, selected, focus) -> {
            Component comp = base.getListCellRendererComponent(list, value, index, selected, focus);
            if (comp instanceof JLabel l) {
                l.setBorder(BorderFactory.createEmptyBorder(5, index < 0 ? 0 : 10, 5, 10));
                l.setForeground(Theme.TEXT);
                l.setBackground(selected && index >= 0 ? new Color(0x4A3125) : index < 0 ? Theme.FIELD : Theme.PANEL);
                l.setOpaque(index >= 0);
            }
            return comp;
        });
        return c;
    }

    static final class FlatComboUI extends BasicComboBoxUI {
        @Override
        protected JButton createArrowButton() {
            JButton b = new JButton(Icons.get("chevron", 12, Theme.TEXT2));
            b.setContentAreaFilled(false);
            b.setBorderPainted(false);
            b.setFocusPainted(false);
            b.setOpaque(false);
            return b;
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            Graphics2D g2 = smooth(g);
            g2.setColor(Theme.FIELD);
            g2.fill(new RoundRectangle2D.Float(0, 0, c.getWidth(), c.getHeight(), Theme.RADIUS * 2, Theme.RADIUS * 2));
            g2.dispose();
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
            javax.swing.plaf.basic.BasicComboPopup p = new javax.swing.plaf.basic.BasicComboPopup(comboBox);
            p.setBorder(BorderFactory.createLineBorder(Theme.BORDER_STRONG));
            return p;
        }
    }

    public static JCheckBox check(String text, boolean on) {
        JCheckBox b = new JCheckBox(text, on);
        b.setOpaque(false);
        b.setFont(Theme.FONT);
        b.setForeground(Theme.TEXT);
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
            g2.fill(new RoundRectangle2D.Float(x, y, 16, 16, 6, 6));
            if (!on) {
                g2.setColor(Theme.BORDER_STRONG);
                g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, 15, 15, 6, 6));
            } else {
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
        s.getViewport().setBackground(Theme.PANEL);
        s.setBackground(Theme.PANEL);
        s.getVerticalScrollBar().setUI(new FlatScrollBarUI());
        s.getHorizontalScrollBar().setUI(new FlatScrollBarUI());
        s.getVerticalScrollBar().setPreferredSize(new Dimension(10, 10));
        s.getHorizontalScrollBar().setPreferredSize(new Dimension(10, 10));
        s.getVerticalScrollBar().setUnitIncrement(24);
        JPanel corner = new JPanel();
        corner.setBackground(Theme.PANEL);
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
            Graphics2D g2 = smooth(g);
            g2.setColor(isThumbRollover() ? Theme.TEXT3 : Theme.BORDER_STRONG);
            boolean v = scrollbar.getOrientation() == JScrollBar.VERTICAL;
            g2.fill(new RoundRectangle2D.Float(r.x + (v ? 2 : 0), r.y + (v ? 0 : 2), r.width - (v ? 4 : 0), r.height - (v ? 0 : 4), 6, 6));
            g2.dispose();
        }
    }

    /** A thin rounded progress bar. */
    public static final class Progress extends JComponent {
        private double value;
        private Color color = Theme.ACCENT;

        public Progress() {
            setPreferredSize(new Dimension(100, 6));
        }

        public void set(double v, Color c) {
            value = Math.max(0, Math.min(1, v));
            color = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            int h = getHeight();
            g2.setColor(Theme.FIELD);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), h, h, h));
            g2.setColor(color);
            g2.fill(new RoundRectangle2D.Float(0, 0, (float) (getWidth() * value), h, h, h));
            g2.dispose();
        }
    }

    /** A small coloured pill, e.g. "verified". */
    public static JLabel pill(String text, Color color) {
        JLabel l = new JLabel(text, SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = smooth(g);
                g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 45));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), getHeight(), getHeight()));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setForeground(color);
        l.setFont(Theme.FONT_SMALL.deriveFont(Font.BOLD));
        l.setBorder(BorderFactory.createEmptyBorder(2, 9, 2, 9));
        return l;
    }

    /** Labelled form field: small grey label over the input. */
    public static JPanel labelled(String label, JComponent input) {
        JPanel p = clear(new BorderLayout(0, 5));
        p.add(muted(label), BorderLayout.NORTH);
        p.add(input, BorderLayout.CENTER);
        return p;
    }

    public static void hand(AbstractButton b) {
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }
}
