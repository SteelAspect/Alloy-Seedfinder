package dev.steelaspect.seedfinder.ui;

import dev.steelaspect.seedfinder.engine.Dim;
import dev.steelaspect.seedfinder.engine.StructureLayers;
import dev.steelaspect.seedfinder.store.Settings;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The explorer: a big biome map of the seed with structure icons you can switch on and off, a dimension switch,
 * a go-to box and the biome under the cursor. Clicking an icon shows its coordinates and a Copy /tp button.
 */
final class MapPage extends JPanel {
    private static final String ID = "map";
    private final SeedFinderApp app;
    private final MapView map = new MapView();
    private final Map<Dim, Ui.FlatButton> dimButtons = new EnumMap<>(Dim.class);
    private final Map<StructureLayers.Layer, JCheckBox> boxes = new EnumMap<>(StructureLayers.Layer.class);
    private final JPanel layerList = Ui.clear(null);
    private final JTextField goTo = Ui.field("", 0);
    private final JLabel status = Ui.muted(" ");
    private final JCheckBox slime = Ui.check("Slime chunks", false), grid = Ui.check("Chunk grid", false);
    private Dim dim = Dim.OVERWORLD;
    /** Where the view was in each dimension, so switching back returns there. */
    private final Map<Dim, int[]> views = new EnumMap<>(Dim.class);

    MapPage(SeedFinderApp app) {
        super(new BorderLayout(0, 14));
        this.app = app;
        setOpaque(false);
        Theme.pad(this, 12, 14, 12, 14);

        JPanel head = Ui.clear(new BorderLayout(0, 3));
        head.add(Ui.title("Map"), BorderLayout.NORTH);
        head.add(Ui.muted("Explore the seed. Drag to move, scroll to zoom, click an icon for its coordinates."), BorderLayout.SOUTH);

        // Toolbar: dimension switch, go-to box.
        JPanel dims = Ui.clear(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (Dim d : Dim.values()) {
            Ui.FlatButton b = Ui.button(d.label, null, Ui.Kind.SECONDARY);
            b.addActionListener(e -> switchTo(d));
            dimButtons.put(d, b);
            dims.add(b);
        }
        goTo.setPreferredSize(new Dimension(170, 34));
        goTo.setToolTipText("x, z  (or x y z)");
        goTo.addActionListener(e -> goTo());
        Ui.FlatButton go = Ui.button("Go", "target", Ui.Kind.SECONDARY);
        go.addActionListener(e -> goTo());
        Ui.FlatButton spawn = Ui.button("Spawn", null, Ui.Kind.GHOST);
        spawn.addActionListener(e -> map.goTo(0, 0));
        JPanel bar = Ui.clear(new BorderLayout(16, 0));
        bar.add(dims, BorderLayout.WEST);
        bar.add(Ui.row(8, Ui.muted("Go to x, z"), goTo, go, spawn), BorderLayout.CENTER);
        bar.add(status, BorderLayout.EAST);
        Ui.Card toolbar = new Ui.Card(new BorderLayout());
        Theme.pad(toolbar, 12, 14, 12, 14);
        toolbar.add(bar, BorderLayout.CENTER);

        JPanel top = Ui.clear(new BorderLayout(0, 14));
        top.add(head, BorderLayout.NORTH);
        top.add(toolbar, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        // Map (centre) and layer switches (right).
        layerList.setLayout(new BoxLayout(layerList, BoxLayout.Y_AXIS));
        for (StructureLayers.Layer l : StructureLayers.Layer.values()) {
            JCheckBox c = Ui.check(l.label, true);
            c.setIcon(new LayerCheck(l, false));
            c.setSelectedIcon(new LayerCheck(l, true));
            c.addActionListener(e -> {
                applyLayers();
                save();
            });
            c.setAlignmentX(LEFT_ALIGNMENT);
            boxes.put(l, c);
        }
        Ui.FlatButton all = Ui.button("All", null, Ui.Kind.GHOST);
        Ui.FlatButton none = Ui.button("None", null, Ui.Kind.GHOST);
        all.addActionListener(e -> setAll(true));
        none.addActionListener(e -> setAll(false));
        Ui.Card side = new Ui.Card("Structures", new BorderLayout(0, 10));
        side.setPreferredSize(new Dimension(240, 100));
        side.titleRight(Ui.row(0, all, none));
        JPanel listHolder = Ui.clear(new BorderLayout());
        listHolder.add(layerList, BorderLayout.NORTH);
        javax.swing.JScrollPane scroll = Ui.scroll(listHolder);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        side.add(scroll, BorderLayout.CENTER);
        JLabel overlays = Ui.strong("Overlays");
        JPanel bottom = Ui.clear(null);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{overlays, slime, grid, Ui.muted("<html>Positions use the game's own<br>placement and biome checks.</html>")}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            bottom.add(c);
            bottom.add(javax.swing.Box.createVerticalStrut(6));
        }
        for (JCheckBox c : new JCheckBox[]{slime, grid})
            c.addActionListener(e -> {
                applyLayers();
                save();
            });
        side.add(bottom, BorderLayout.SOUTH);

        JPanel centre = Ui.clear(new BorderLayout(14, 0));
        centre.add(map, BorderLayout.CENTER);
        centre.add(side, BorderLayout.EAST);
        add(centre, BorderLayout.CENTER);

        map.onStatus(s -> status.setText(s));
        map.onIcon(this::iconMenu);
        restore();
    }

    // ------------------------------------------------------------------ actions

    void shown() {
        Settings.Seed seed = app.seed();
        map.show(seed.value(), dim, seed.type, Set.of());
        applyLayers();
    }

    void seedChanged() {
        shown();
    }

    void switchTo(Dim d) {
        if (d == dim) return;
        views.put(dim, new int[]{map.centerBlockX(), map.centerBlockZ(), map.zoomLevel()});
        dim = d;
        int[] v = views.getOrDefault(d, new int[]{0, 0, 4});
        map.goTo(v[0], v[1]);
        map.setZoomLevel(v[2]);
        shown();
        save();
    }

    /** Moves the view (screenshots). */
    void view(int x, int z, int zoom) {
        map.goTo(x, z);
        map.setZoomLevel(zoom);
    }

    private void goTo() {
        String[] p = goTo.getText().trim().split("[,\\s]+");
        try {
            int x = Integer.parseInt(p[0]), z = Integer.parseInt(p[p.length == 3 ? 2 : 1]);
            if (Math.abs((long) x) > 30_000_000L || Math.abs((long) z) > 30_000_000L) throw new NumberFormatException();
            map.goTo(x, z);
            status.setForeground(Theme.TEXT2);
            status.setText("Went to " + x + ", " + z);
        } catch (RuntimeException e) {
            status.setForeground(Theme.DANGER);
            status.setText("Enter two numbers, like -1200, 3400");
        }
    }

    private void setAll(boolean on) {
        for (var e : boxes.entrySet()) if (e.getKey().dim == dim) e.getValue().setSelected(on);
        applyLayers();
        save();
    }

    /** Shows the switches for this dimension and tells the map which layers are on. */
    private void applyLayers() {
        for (var e : dimButtons.entrySet()) e.getValue().setActive(e.getKey() == dim);
        layerList.removeAll();
        Set<StructureLayers.Layer> on = EnumSet.noneOf(StructureLayers.Layer.class);
        for (var e : boxes.entrySet()) {
            if (e.getKey().dim != dim) continue;
            layerList.add(e.getValue());
            JPanel gap = Ui.clear(null);
            gap.setPreferredSize(new Dimension(1, 6));
            gap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
            gap.setAlignmentX(LEFT_ALIGNMENT);
            layerList.add(gap);
            if (e.getValue().isSelected()) on.add(e.getKey());
        }
        layerList.revalidate();
        layerList.repaint();
        map.setLayers(on);
        slime.setEnabled(dim == Dim.OVERWORLD);
        map.setSlimeChunks(slime.isSelected());
        map.setChunkGrid(grid.isSelected());
    }

    private void iconMenu(StructureLayers.Icon icon, java.awt.Point at) {
        JPopupMenu menu = new JPopupMenu();
        JLabel head = Ui.label("  " + icon.label() + "    " + icon.x() + ", " + icon.y() + ", " + icon.z() + "  ");
        head.setFont(Theme.FONT_BOLD);
        head.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 6, 8, 6));
        menu.add(head);
        menu.addSeparator();
        menu.add(item("Copy /tp", () -> {
            app.copy(icon.tp());
            status.setForeground(Theme.SUCCESS);
            status.setText("Copied " + icon.tp());
        }));
        menu.add(item("Copy coordinates", () -> {
            app.copy(icon.x() + " " + icon.y() + " " + icon.z());
            status.setForeground(Theme.SUCCESS);
            status.setText("Copied " + icon.x() + " " + icon.y() + " " + icon.z());
        }));
        menu.add(item("Zoom in here", () -> {
            map.goTo(icon.x(), icon.z());
            map.setZoomLevel(Math.max(0, Math.min(map.zoomLevel(), 2)));
        }));
        menu.show(map, at.x + 8, at.y + 8);
    }

    private static JMenuItem item(String text, Runnable action) {
        JMenuItem i = new JMenuItem(text);
        i.setFont(Theme.FONT);
        i.setForeground(Theme.TEXT);
        i.setBackground(Theme.PANEL);
        i.addActionListener(e -> action.run());
        return i;
    }

    // ------------------------------------------------------------------ remembering

    private void save() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("dim", dim.name());
        StringBuilder off = new StringBuilder();
        for (var e : boxes.entrySet()) if (!e.getValue().isSelected()) off.append(e.getKey().name()).append(',');
        m.put("hidden", off.toString());
        m.put("slime", Boolean.toString(slime.isSelected()));
        m.put("grid", Boolean.toString(grid.isSelected()));
        app.settings().pages.put(ID, m);
        app.settings().save();
    }

    private void restore() {
        Map<String, String> m = app.settings().pages.getOrDefault(ID, new HashMap<>());
        // The most common structures start off (they'd cover the map); everything else on.
        String off = m.getOrDefault("hidden", "TRIAL_CHAMBER,RUINED_PORTAL,SHIPWRECK,");
        for (var e : boxes.entrySet()) e.getValue().setSelected(!off.contains(e.getKey().name() + ","));
        slime.setSelected(Boolean.parseBoolean(m.getOrDefault("slime", "false")));
        grid.setSelected(Boolean.parseBoolean(m.getOrDefault("grid", "false")));
        try {
            dim = Dim.valueOf(m.getOrDefault("dim", "OVERWORLD"));
        } catch (IllegalArgumentException e) {
            dim = Dim.OVERWORLD;
        }
        map.setZoomLevel(4);
        SwingUtilities.invokeLater(this::applyLayers);
    }

    /** Check box icon in the layer's own colour. */
    private record LayerCheck(StructureLayers.Layer layer, boolean on) implements javax.swing.Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = Ui.smooth(g);
            Color col = new Color(layer.color);
            g2.setColor(on ? col : Theme.FIELD);
            g2.fill(new Ellipse2D.Float(x, y, 18, 18));
            g2.setColor(on ? new Color(0x16181D) : Theme.BORDER_STRONG);
            g2.draw(new Ellipse2D.Float(x + 0.5f, y + 0.5f, 17, 17));
            Icons.get(layer.icon, 12, on ? new Color(0x16181D) : Theme.TEXT3).paintIcon(c, g2, x + 3, y + 3);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }
    }
}
