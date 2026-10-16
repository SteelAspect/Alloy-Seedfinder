package dev.steelaspect.seedfinder.ui;

import dev.steelaspect.seedfinder.engine.Dim;
import dev.steelaspect.seedfinder.engine.Mc;
import dev.steelaspect.seedfinder.engine.WorldGen;
import dev.steelaspect.seedfinder.engine.WorldType;
import dev.steelaspect.seedfinder.engine.Workers;
import dev.steelaspect.seedfinder.store.History;
import dev.steelaspect.seedfinder.store.Settings;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The main window: top bar with the seed, sidebar with the tools, and the current page. */
public final class SeedFinderApp {
    public static final String NAME = "Cytra Seed Finder";
    public static final String VERSION = "2.1.0";

    private final Settings settings = Settings.load();
    private final JFrame frame = new JFrame(NAME);
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final Map<String, JComponent> pages = new LinkedHashMap<>();
    private final List<SearchPage> searchPages = new ArrayList<>();
    private final MapPage mapPage;
    private final HistoryPage history;
    private final SettingsPage settingsPage;
    private final Sidebar sidebar = new Sidebar();
    private final JComboBox<Settings.Seed> seedBox;
    private JComboBox<Workers.Speed> speedBox;
    private final JLabel engine = Ui.muted("Loading the world generator…");
    private boolean updatingSeeds;

    public SeedFinderApp() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setIconImage(AppIcon.image(64));
        frame.setMinimumSize(new Dimension(1100, 700));
        frame.setSize(1380, 880);
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BG);

        seedBox = Ui.combo(new Settings.Seed[0]);
        seedBox.setPreferredSize(new Dimension(300, 34));
        seedBox.addActionListener(e -> {
            if (updatingSeeds || seedBox.getSelectedIndex() < 0) return;
            settings.selected = seedBox.getSelectedIndex();
            settings.save();
            for (SearchPage p : searchPages) p.seedChanged();
            warmUp();
        });
        root.add(topBar(), BorderLayout.NORTH);

        mapPage = new MapPage(this);
        add("map", mapPage);
        for (SearchPage p : List.of(new Pages.Biomes(this), new Pages.Solid(this), new Pages.Huts(this), new Pages.Monuments(this), new Pages.Slime(this), new Pages.Nearest(this),
                new Pages.Nether(this), new Pages.Fortresses(this))) {
            p.build();
            searchPages.add(p);
            add(p.id, p);
        }
        history = new HistoryPage(this);
        add("history", history);
        settingsPage = new SettingsPage(this);
        add("settings", settingsPage);
        content.setBackground(Theme.BG);

        sidebar.section("Explore");
        sidebar.item("map", "Map", "map");
        sidebar.item("nearest", "Nearest structures", "list");
        sidebar.section("Overworld");
        sidebar.item("biomes", "Biomes", "mountain");
        sidebar.item("solid", "Solid areas", "square");
        sidebar.item("huts", "Witch huts", "hut");
        sidebar.item("monuments", "Quad finder", "waves");
        sidebar.item("slime", "Slime chunks", "slime");
        sidebar.section("Nether");
        sidebar.item("nether", "Nether biomes", "flame");
        sidebar.item("fortresses", "Fortresses", "castle");
        sidebar.section("App");
        sidebar.item("history", "Saved results", "clock");
        sidebar.item("settings", "Settings", "gear");
        root.add(sidebar, BorderLayout.WEST);
        root.add(content, BorderLayout.CENTER);
        frame.setContentPane(root);
        seedsChanged();
        setSpeed(settings.speed);
        show("map");
        warmUp();
    }

    void setSpeed(Workers.Speed s) {
        if (s == null) return;
        settings.speed = s;
        settings.save();
        Workers.set(s);
        MapView.setThreads(s.threads());
        if (speedBox != null && speedBox.getSelectedItem() != s) speedBox.setSelectedItem(s);
    }

    private void add(String id, JComponent page) {
        pages.put(id, page);
        content.add(page, id);
    }

    public void launch() {
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public JFrame frame() {
        return frame;
    }

    public Settings settings() {
        return settings;
    }

    public Settings.Seed seed() {
        return settings.seed();
    }

    /** Loads the world generator in the background so the first search and the map start quickly. */
    private void warmUp() {
        Settings.Seed s = seed();
        engine.setText("Loading the world generator…");
        Thread t = new Thread(() -> {
            Mc.lookup();
            WorldGen.of(s.value(), Dim.OVERWORLD, s.type);
            SwingUtilities.invokeLater(() -> engine.setText("Minecraft 1.21.11 world generator ready"));
        }, "warm-up");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ navigation

    void show(String id) {
        cards.show(content, id);
        sidebar.select(id);
        JComponent page = pages.get(id);
        if (page instanceof SearchPage p) p.shown();
        if (page == mapPage) mapPage.shown();
        if (page == history) history.reload();
        if (page == settingsPage) settingsPage.reload();
    }

    void open(History.Entry e) {
        JComponent page = pages.get(e.page);
        if (!(page instanceof SearchPage p)) return;
        for (int i = 0; i < settings.seeds.size(); i++)
            if (settings.seeds.get(i).value() == e.seed) {
                settings.selected = i;
                seedsChanged();
                break;
            }
        show(e.page);
        p.open(e);
    }

    void seedsChanged() {
        updatingSeeds = true;
        seedBox.removeAllItems();
        for (Settings.Seed s : settings.seeds) seedBox.addItem(s);
        seedBox.setSelectedIndex(Math.min(settings.selected, settings.seeds.size() - 1));
        updatingSeeds = false;
        settings.save();
        for (SearchPage p : searchPages) p.seedChanged();
        if (mapPage != null) mapPage.seedChanged();
        if (settingsPage != null) settingsPage.reload();
    }

    // ------------------------------------------------------------------ top bar and sidebar

    private JPanel topBar() {
        JPanel bar = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Theme.SIDEBAR);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Theme.BORDER);
                g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
            }
        };
        Theme.pad(bar, 10, 16, 10, 16);
        JLabel logo = new JLabel(new javax.swing.ImageIcon(AppIcon.image(30)));
        JLabel name = Ui.label(NAME);
        name.setFont(Theme.FONT_TITLE);
        JPanel left = Ui.row(10, logo, name);
        bar.add(left, BorderLayout.WEST);
        Ui.FlatButton add = Ui.button("", "plus", Ui.Kind.SECONDARY);
        add.setToolTipText("Add a seed");
        add.addActionListener(e -> {
            Settings.Seed n = editSeed(null);
            if (n != null) {
                settings.seeds.add(n);
                settings.selected = settings.seeds.size() - 1;
                seedsChanged();
                warmUp();
            }
        });
        JComboBox<Workers.Speed> speed = Ui.combo(Workers.Speed.values());
        speed.setSelectedItem(settings.speed);
        speed.setPreferredSize(new Dimension(118, 34));
        speed.setToolTipText("<html>" + String.join("<br>", java.util.Arrays.stream(Workers.Speed.values())
                .map(s -> "<b>" + s.label + "</b>: " + s.help).toList()) + "</html>");
        speed.addActionListener(e -> setSpeed((Workers.Speed) speed.getSelectedItem()));
        speedBox = speed;
        JPanel right = Ui.row(10, engine, Ui.muted("   Speed"), speed, Ui.muted("  Seed"), seedBox, add);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private final class Sidebar extends JPanel {
        private final Map<String, Item> items = new LinkedHashMap<>();

        Sidebar() {
            setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
            setBackground(Theme.SIDEBAR);
            Theme.pad(this, 14, 10, 14, 10);
            setPreferredSize(new Dimension(212, 100));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.setColor(Theme.BORDER);
            g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
        }

        void section(String text) {
            JLabel l = Ui.muted(text);
            l.setForeground(Theme.TEXT3);
            Theme.pad(l, items.isEmpty() ? 2 : 16, 12, 6, 0);
            l.setAlignmentX(LEFT_ALIGNMENT);
            add(l);
        }

        void item(String id, String text, String icon) {
            Item i = new Item(id, text, icon);
            items.put(id, i);
            add(i);
        }

        void select(String id) {
            items.forEach((k, v) -> v.setSelected(k.equals(id)));
        }
    }

    private final class Item extends JComponent {
        private final String id, text, icon;
        private boolean selected, hover;

        Item(String id, String text, String icon) {
            this.id = id;
            this.text = text;
            this.icon = icon;
            setAlignmentX(LEFT_ALIGNMENT);
            setPreferredSize(new Dimension(190, 36));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    SeedFinderApp.this.show(id);
                }

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

        void setSelected(boolean s) {
            selected = s;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.smooth(g);
            if (selected || hover) {
                g2.setColor(selected ? Theme.ACCENT_SOFT : Theme.FIELD);
                g2.fill(new RoundRectangle2D.Float(0, 2, getWidth(), getHeight() - 4, 14, 14));
            }
            Color c = selected ? Theme.ACCENT_HOVER : hover ? Theme.TEXT : Theme.TEXT2;
            Icons.get(icon, 16, c).paintIcon(this, g2, 12, (getHeight() - 16) / 2);
            g2.setFont(selected ? Theme.FONT_BOLD : Theme.FONT);
            g2.setColor(selected ? Theme.TEXT : c);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, 38, (getHeight() + fm.getAscent() - fm.getDescent()) / 2f);
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ dialogs and helpers

    JDialog dialog(String title) {
        JDialog d = new JDialog(frame, title, true);
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        d.getRootPane().setBorder(javax.swing.BorderFactory.createLineBorder(Theme.BORDER_STRONG));
        return d;
    }

    /** Add (existing == null) or edit a seed. Returns the seed, or null if cancelled. */
    Settings.Seed editSeed(Settings.Seed existing) {
        JDialog d = dialog(existing == null ? "Add a seed" : "Edit seed");
        JPanel body = Ui.clear(new BorderLayout(0, 16));
        Theme.pad(body, 20, 22, 18, 22);
        JTextField name = Ui.field(existing == null ? "" : existing.name, 0);
        JTextField seed = Ui.field(existing == null ? "" : existing.seed, 0);
        JComboBox<WorldType> type = Ui.combo(WorldType.values());
        if (existing != null) type.setSelectedItem(existing.type);
        name.setPreferredSize(new Dimension(380, 34));
        JPanel fields = Ui.clear(new GridLayout(0, 1, 0, 12));
        fields.add(Ui.labelled("Name", name));
        fields.add(Ui.labelled("Seed (type /seed in game to see it)", seed));
        fields.add(Ui.labelled("World type", type));
        JLabel error = Ui.muted(" ");
        error.setForeground(Theme.DANGER);
        body.add(Ui.title(existing == null ? "Add a seed" : "Edit seed"), BorderLayout.NORTH);
        body.add(fields, BorderLayout.CENTER);
        Settings.Seed[] out = {null};
        Ui.FlatButton cancel = Ui.button("Cancel", null, Ui.Kind.SECONDARY);
        Ui.FlatButton ok = Ui.button(existing == null ? "Add seed" : "Save", "check", Ui.Kind.PRIMARY);
        cancel.addActionListener(e -> d.dispose());
        ok.addActionListener(e -> {
            if (seed.getText().isBlank()) {
                error.setText("Enter a seed");
                return;
            }
            if (name.getText().isBlank()) {
                error.setText("Enter a name");
                return;
            }
            Settings.Seed s = existing != null ? existing : new Settings.Seed();
            s.name = name.getText().trim();
            s.seed = seed.getText().trim();
            s.type = (WorldType) type.getSelectedItem();
            out[0] = s;
            d.dispose();
        });
        JPanel bottom = Ui.clear(new BorderLayout());
        bottom.add(error, BorderLayout.WEST);
        bottom.add(Ui.row(8, cancel, ok), BorderLayout.EAST);
        body.add(bottom, BorderLayout.SOUTH);
        d.setContentPane(XaeroDialog.wrap(body));
        d.pack();
        d.setLocationRelativeTo(frame);
        d.setVisible(true);
        return out[0];
    }

    boolean confirm(String title, String text, String action) {
        JDialog d = dialog(title);
        JPanel body = Ui.clear(new BorderLayout(0, 12));
        Theme.pad(body, 20, 22, 18, 22);
        body.add(Ui.title(title), BorderLayout.NORTH);
        body.add(Ui.muted(text), BorderLayout.CENTER);
        boolean[] yes = {false};
        Ui.FlatButton cancel = Ui.button("Cancel", null, Ui.Kind.SECONDARY);
        Ui.FlatButton ok = Ui.button(action, null, Ui.Kind.PRIMARY);
        cancel.addActionListener(e -> d.dispose());
        ok.addActionListener(e -> {
            yes[0] = true;
            d.dispose();
        });
        JPanel bottom = Ui.clear(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        bottom.add(cancel);
        bottom.add(ok);
        body.add(bottom, BorderLayout.SOUTH);
        d.setContentPane(XaeroDialog.wrap(body));
        d.pack();
        d.setLocationRelativeTo(frame);
        d.setVisible(true);
        return yes[0];
    }

    /** First Verify: the test server needs the Minecraft EULA accepted. */
    boolean askEula() {
        JDialog d = dialog("Verify on a test server");
        JPanel body = Ui.clear(new BorderLayout(0, 14));
        Theme.pad(body, 20, 22, 18, 22);
        body.add(Ui.title("Verify on a test server"), BorderLayout.NORTH);
        JPanel text = Ui.clear(new GridLayout(0, 1, 0, 4));
        text.add(Ui.muted("Verify starts a hidden vanilla Minecraft 1.21.11 server with your seed, checks the results"));
        text.add(Ui.muted("against what the game really generates, then deletes it. It takes about 1-2 minutes."));
        text.add(Ui.muted(" "));
        text.add(Ui.muted("Running a Minecraft server means accepting the Minecraft EULA (aka.ms/MinecraftEULA)."));
        body.add(text, BorderLayout.CENTER);
        boolean[] yes = {false};
        Ui.FlatButton cancel = Ui.button("Cancel", null, Ui.Kind.SECONDARY);
        Ui.FlatButton ok = Ui.button("I accept the EULA", "check", Ui.Kind.PRIMARY);
        cancel.addActionListener(e -> d.dispose());
        ok.addActionListener(e -> {
            yes[0] = true;
            settings.eulaAccepted = true;
            settings.save();
            d.dispose();
        });
        JPanel bottom = Ui.clear(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        bottom.add(cancel);
        bottom.add(ok);
        body.add(bottom, BorderLayout.SOUTH);
        d.setContentPane(XaeroDialog.wrap(body));
        d.pack();
        d.setLocationRelativeTo(frame);
        d.setVisible(true);
        return yes[0];
    }

    void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    File chooseSaveFile(String name) {
        JFileChooser c = new JFileChooser(System.getProperty("user.home"));
        c.setSelectedFile(new File(System.getProperty("user.home"), name));
        return c.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION ? c.getSelectedFile() : null;
    }

    // ------------------------------------------------------------------ screenshots (for the README and testing)

    /** Draws the window into an image without showing it, after giving the map time to draw. */
    public BufferedImage screenshot(String page, History.Entry open, int waitMs) throws Exception {
        BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            frame.pack();
            frame.setSize(1380, 880);
            frame.getContentPane().setSize(1380, 880);
            if (open != null) open(open);
            else if (page.startsWith("map:")) {
                // map:<dim>[:<x>,<z>:<zoom level>]
                String[] p = page.split(":");
                show("map");
                mapPage.switchTo(Dim.valueOf(p[1].toUpperCase()));
                if (p.length >= 4) mapPage.view(Integer.parseInt(p[2].split(",")[0]), Integer.parseInt(p[2].split(",")[1]), Integer.parseInt(p[3]));
            } else show(page.replace("search:", ""));
            frame.getContentPane().validate();
            if (page.startsWith("search:") && pages.get(page.substring(7)) instanceof SearchPage p) p.pressSearch();
        });
        // Paint once so the map asks for its tiles, then again after they're drawn.
        for (int waited = 0; waited < waitMs; waited += 1000) {
            SwingUtilities.invokeAndWait(() -> {
                JComponent c = (JComponent) frame.getContentPane();
                c.setSize(1380, 880);
                c.validate();
                BufferedImage scratch = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = scratch.createGraphics();
                c.paint(g);
                g.dispose();
            });
            Thread.sleep(1000);
        }
        SwingUtilities.invokeAndWait(() -> {
            JComponent c = (JComponent) frame.getContentPane();
            c.setSize(1380, 880);
            c.validate();
            img[0] = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img[0].createGraphics();
            c.paint(g);
            g.dispose();
        });
        return img[0];
    }
}
