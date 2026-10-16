package dev.steelaspect.seedfinder.ui;

import dev.steelaspect.seedfinder.store.History;
import dev.steelaspect.seedfinder.store.Settings;
import dev.steelaspect.seedfinder.verify.Verifier;

import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.net.URI;
import java.nio.file.Path;

/** Saved seeds, waypoint export, the Verify test server, and where files are kept. */
final class SettingsPage extends JPanel {
    private final SeedFinderApp app;
    private final DefaultListModel<Settings.Seed> seeds = new DefaultListModel<>();
    private final JList<Settings.Seed> seedList = new JList<>(seeds);
    private final JTextField xaeroDir = Ui.field("", 0);
    private final JTextField category = Ui.field("", 0);
    private final JCheckBox eula = Ui.check("I accept the Minecraft EULA for the test server", false);
    private final JTextField serverJar = Ui.field("", 0);
    private final JLabel saved = Ui.muted(" ");

    SettingsPage(SeedFinderApp app) {
        super(new BorderLayout(0, 14));
        this.app = app;
        setOpaque(false);
        Theme.pad(this, 20, 22, 18, 22);
        JPanel head = Ui.clear(new BorderLayout(0, 3));
        head.add(Ui.title("Settings"), BorderLayout.NORTH);
        head.add(Ui.muted("Saved seeds, waypoints and the Verify test server"), BorderLayout.SOUTH);
        add(head, BorderLayout.NORTH);

        JPanel column = Ui.clear(null);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.add(section("Seeds", "The seeds in the top bar. Text seeds work too, the same way the game reads them.", seedsPanel()));
        column.add(gap());
        column.add(section("Waypoints", "Where \"Add to Xaero's minimap\" writes, and the category for /cway add commands.", waypointsPanel()));
        column.add(gap());
        column.add(section("Verify", "Verify starts a hidden vanilla 1.21.11 server with your seed to check results, then deletes it.", verifyPanel()));
        column.add(gap());
        column.add(section("Map", "Map tiles you've looked at are kept on disk so they draw instantly next time (up to "
                + TileCache.LIMIT / 1024 / 1024 + " MB, oldest removed first).", mapPanel()));
        column.add(gap());
        column.add(section("Files", "Settings: " + Settings.FILE + "\nSaved results: " + History.DIR + "\nMap cache: " + TileCache.DIR, null));
        JPanel holder = Ui.clear(new BorderLayout());
        holder.add(column, BorderLayout.NORTH);
        javax.swing.JScrollPane scroll = Ui.scroll(holder);
        scroll.getViewport().setBackground(Theme.BG);
        scroll.setBackground(Theme.BG);
        add(scroll, BorderLayout.CENTER);

        Ui.FlatButton save = Ui.button("Save settings", "check", Ui.Kind.PRIMARY);
        save.addActionListener(e -> save());
        JPanel bottom = Ui.clear(new FlowLayout(FlowLayout.LEFT, 12, 0));
        bottom.add(save);
        bottom.add(saved);
        add(bottom, BorderLayout.SOUTH);
    }

    private static Component gap() {
        JPanel p = Ui.clear(null);
        p.setPreferredSize(new Dimension(1, 12));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 12));
        return p;
    }

    private JPanel section(String title, String help, JComponent content) {
        Ui.Card card = new Ui.Card(new BorderLayout(0, 12));
        JPanel head = Ui.clear(new BorderLayout(0, 4));
        JLabel t = Ui.label(title);
        t.setFont(Theme.FONT_BOLD);
        head.add(t, BorderLayout.NORTH);
        JPanel lines = Ui.clear(new java.awt.GridLayout(0, 1, 0, 2));
        for (String line : help.split("\n")) lines.add(Ui.muted(line));
        head.add(lines, BorderLayout.CENTER);
        card.add(head, BorderLayout.NORTH);
        if (content != null) card.add(content, BorderLayout.CENTER);
        card.setAlignmentX(LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height + 40));
        return card;
    }

    private JComponent seedsPanel() {
        seedList.setBackground(Theme.FIELD);
        seedList.setForeground(Theme.TEXT);
        seedList.setFont(Theme.FONT);
        seedList.setFixedCellHeight(30);
        seedList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        seedList.setCellRenderer((list, value, index, sel, focus) -> {
            JLabel l = new JLabel(value.name + "    " + value.seed + "    " + value.type.label);
            l.setFont(Theme.FONT);
            l.setOpaque(true);
            l.setForeground(Theme.TEXT);
            l.setBackground(sel ? new java.awt.Color(0x4A3125) : Theme.FIELD);
            Theme.pad(l, 0, 10, 0, 10);
            return l;
        });
        javax.swing.JScrollPane s = Ui.scroll(seedList);
        s.setPreferredSize(new Dimension(520, 124));
        Ui.FlatButton add = Ui.button("Add", "plus", Ui.Kind.SECONDARY);
        Ui.FlatButton edit = Ui.button("Edit", null, Ui.Kind.SECONDARY);
        Ui.FlatButton remove = Ui.button("Remove", "trash", Ui.Kind.SECONDARY);
        add.addActionListener(e -> {
            Settings.Seed n = app.editSeed(null);
            if (n != null) {
                app.settings().seeds.add(n);
                app.seedsChanged();
            }
        });
        edit.addActionListener(e -> {
            Settings.Seed sel = seedList.getSelectedValue();
            if (sel != null && app.editSeed(sel) != null) app.seedsChanged();
        });
        remove.addActionListener(e -> {
            Settings.Seed sel = seedList.getSelectedValue();
            if (sel == null || app.settings().seeds.size() <= 1) return;
            if (!app.confirm("Remove " + sel.name + "?", "Its saved searches stay in Saved results.", "Remove")) return;
            app.settings().seeds.remove(sel);
            app.settings().selected = 0;
            app.seedsChanged();
        });
        JPanel p = Ui.clear(new BorderLayout(12, 0));
        p.add(s, BorderLayout.CENTER);
        JPanel buttons = Ui.clear(new java.awt.GridLayout(0, 1, 0, 8));
        buttons.add(add);
        buttons.add(edit);
        buttons.add(remove);
        JPanel bw = Ui.clear(new BorderLayout());
        bw.add(buttons, BorderLayout.NORTH);
        p.add(bw, BorderLayout.EAST);
        return p;
    }

    private JComponent waypointsPanel() {
        xaeroDir.setPreferredSize(new Dimension(520, 34));
        category.setPreferredSize(new Dimension(200, 34));
        Ui.FlatButton browse = Ui.button("Browse", null, Ui.Kind.SECONDARY);
        browse.addActionListener(e -> {
            JFileChooser c = new JFileChooser(xaeroDir.getText().isBlank() ? System.getProperty("user.home") : xaeroDir.getText());
            c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) xaeroDir.setText(c.getSelectedFile().toString());
        });
        JPanel p = Ui.clear(new java.awt.GridLayout(0, 1, 0, 10));
        p.add(Ui.labelled("Xaero's minimap folder (…/xaero/minimap). Empty = find it automatically", Ui.row(8, xaeroDir, browse)));
        p.add(Ui.labelled("Cytra Waypoints category", Ui.row(0, category)));
        return p;
    }

    private final JLabel cacheSize = Ui.muted(" ");

    private JComponent mapPanel() {
        Ui.FlatButton clear = Ui.button("Clear map cache", "trash", Ui.Kind.SECONDARY);
        clear.addActionListener(e -> {
            MapView.clearCache();
            cacheSize.setText("Map cache cleared");
        });
        return Ui.row(12, clear, cacheSize);
    }

    private void showCacheSize() {
        Thread t = new Thread(() -> {
            long bytes = MapView.cacheSize();
            javax.swing.SwingUtilities.invokeLater(() -> cacheSize.setText(String.format(java.util.Locale.ROOT, "Using %.1f MB", bytes / 1048576.0)));
        }, "cache-size");
        t.setDaemon(true);
        t.start();
    }

    private JComponent verifyPanel() {
        serverJar.setPreferredSize(new Dimension(520, 34));
        Ui.FlatButton browse = Ui.button("Browse", null, Ui.Kind.SECONDARY);
        browse.addActionListener(e -> {
            JFileChooser c = new JFileChooser(System.getProperty("user.home"));
            if (c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) serverJar.setText(c.getSelectedFile().toString());
        });
        Ui.FlatButton read = Ui.button("Read the EULA", "open", Ui.Kind.GHOST);
        read.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(URI.create("https://aka.ms/MinecraftEULA"));
            } catch (Exception ex) {
                app.copy("https://aka.ms/MinecraftEULA");
                saved.setText("Copied the EULA link");
            }
        });
        Path found = Verifier.findServerJar();
        JPanel p = Ui.clear(new java.awt.GridLayout(0, 1, 0, 10));
        p.add(Ui.row(8, eula, read));
        p.add(Ui.labelled("Server jar. Empty = " + (found == null ? "not found; build any 1.21.11 mod once, or pick it" : "found automatically"),
                Ui.row(8, serverJar, browse)));
        return p;
    }

    /** Fills the fields from the saved settings. */
    void reload() {
        Settings s = app.settings();
        seeds.clear();
        for (Settings.Seed seed : s.seeds) seeds.addElement(seed);
        xaeroDir.setText(s.xaeroDir);
        category.setText(s.cwayCategory);
        eula.setSelected(s.eulaAccepted);
        serverJar.setText(s.serverJar);
        saved.setText(" ");
        showCacheSize();
    }

    private void save() {
        Settings s = app.settings();
        s.xaeroDir = xaeroDir.getText().trim();
        s.cwayCategory = category.getText().trim().isEmpty() ? "seedfinder" : category.getText().trim().replace(' ', '_');
        s.eulaAccepted = eula.isSelected();
        s.serverJar = serverJar.getText().trim();
        s.save();
        saved.setText("Saved");
        saved.setForeground(Theme.SUCCESS);
    }
}
