package dev.steelaspect.seedfinder.ui;

import dev.steelaspect.seedfinder.engine.Dim;
import dev.steelaspect.seedfinder.engine.Progress;
import dev.steelaspect.seedfinder.engine.Result;
import dev.steelaspect.seedfinder.engine.Search;
import dev.steelaspect.seedfinder.engine.Workers;
import dev.steelaspect.seedfinder.export.Exporters;
import dev.steelaspect.seedfinder.store.History;
import dev.steelaspect.seedfinder.store.Settings;
import dev.steelaspect.seedfinder.verify.Verifier;

import javax.imageio.ImageIO;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One search tool: settings row, Search button, progress, results list and map, plus the result actions
 * (copy /tp, waypoints, save, verify). Subclasses add their fields and run their search.
 */
public abstract class SearchPage extends JPanel {
    /** Search sizes offered in the size box: label -> radius. */
    protected static final Map<String, Integer> SIZES = new LinkedHashMap<>();

    static {
        SIZES.put("20k × 20k", 10_000);
        SIZES.put("40k × 40k", 20_000);
        SIZES.put("100k × 100k", 50_000);
        SIZES.put("200k × 200k", 100_000);
        SIZES.put("400k × 400k", 200_000);
        SIZES.put("1M × 1M", 500_000);
        SIZES.put("2M × 2M", 1_000_000);
    }

    /** Extra sizes for tools that can search far (only the placement maths runs everywhere). */
    protected static final Map<String, Integer> BIG_SIZES = new LinkedHashMap<>();

    static {
        BIG_SIZES.put("5M × 5M", 2_500_000);
        BIG_SIZES.put("10M × 10M", 5_000_000);
        BIG_SIZES.put("20M × 20M", 10_000_000);
        BIG_SIZES.put("Whole world", 30_000_000);
    }

    protected final SeedFinderApp app;
    public final String id;
    public final String title;
    private final String subtitle;
    protected final Map<String, JComponent> fields = new LinkedHashMap<>();
    private final ResultsTable table = new ResultsTable();
    protected final MapView map = new MapView();
    private final Ui.Progress bar = new Ui.Progress();
    private final JLabel status = Ui.muted(" ");
    private final JLabel empty = new JLabel("", SwingConstants.CENTER);
    private final Ui.FlatButton search = Ui.button("Search", "play", Ui.Kind.PRIMARY);
    private final Ui.FlatButton stop = Ui.button("Stop", "stop", Ui.Kind.SECONDARY);
    private final List<JComponent> resultActions = new ArrayList<>();
    private volatile boolean cancelled;
    private boolean busy;
    private History.Entry current;

    protected SearchPage(SeedFinderApp app, String id, String title, String subtitle) {
        super(new BorderLayout(0, 14));
        this.app = app;
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        setOpaque(false);
        Theme.pad(this, 20, 22, 18, 22);
    }

    /** Builds the page; called once by the app after construction. */
    final void build() {
        JPanel head = Ui.clear(new BorderLayout(0, 3));
        head.add(Ui.title(title), BorderLayout.NORTH);
        head.add(Ui.muted(subtitle), BorderLayout.SOUTH);

        JPanel form = Ui.clear(new WrapLayout(12, 4));
        buildForm(form);
        restore();
        stop.setVisible(false);
        search.addActionListener(e -> start());
        stop.addActionListener(e -> {
            cancelled = true;
            setStatus("Stopping…", Theme.TEXT2);
        });
        JPanel buttons = Ui.clear(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(stop);
        buttons.add(search);
        JPanel formRow = Ui.clear(new BorderLayout(16, 0));
        formRow.add(form, BorderLayout.CENTER);
        JPanel bw = Ui.clear(new BorderLayout());
        bw.add(buttons, BorderLayout.SOUTH);
        formRow.add(bw, BorderLayout.EAST);
        Ui.Card formCard = new Ui.Card(new BorderLayout(0, 12));
        formCard.add(formRow, BorderLayout.CENTER);
        JPanel progressRow = Ui.clear(new BorderLayout(12, 0));
        bar.setPreferredSize(new Dimension(220, 6));
        JPanel barWrap = Ui.clear(new java.awt.GridBagLayout());
        barWrap.add(bar);
        progressRow.add(barWrap, BorderLayout.WEST);
        progressRow.add(status, BorderLayout.CENTER);
        formCard.add(progressRow, BorderLayout.SOUTH);

        JPanel top = Ui.clear(new BorderLayout(0, 14));
        top.add(head, BorderLayout.NORTH);
        top.add(formCard, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        // Results (left) and map (right).
        Ui.Card resultsCard = new Ui.Card(new BorderLayout(0, 10));
        Theme.pad(resultsCard, 6, 6, 10, 6);
        JScrollPane scroll = Ui.scroll(table);
        JLayeredPane layered = new JLayeredPane() {
            @Override
            public void doLayout() {
                for (Component c : getComponents()) c.setBounds(0, 0, getWidth(), getHeight());
            }
        };
        empty.setForeground(Theme.TEXT3);
        empty.setFont(Theme.FONT);
        layered.add(scroll, JLayeredPane.DEFAULT_LAYER);
        layered.add(empty, JLayeredPane.PALETTE_LAYER);
        resultsCard.add(layered, BorderLayout.CENTER);
        resultsCard.add(actions(), BorderLayout.SOUTH);

        JPanel mapWrap = Ui.clear(new BorderLayout());
        mapWrap.add(map, BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, resultsCard, mapWrap);
        split.setResizeWeight(0.55);
        split.setBorder(null);
        split.setOpaque(false);
        split.setDividerSize(14);
        split.setUI(new BasicSplitPaneUI() {
            @Override
            public BasicSplitPaneDivider createDefaultDivider() {
                return new BasicSplitPaneDivider(this) {
                    @Override
                    public void paint(Graphics g) {
                        g.setColor(Theme.BG);
                        g.fillRect(0, 0, getWidth(), getHeight());
                    }
                };
            }
        });
        add(split, BorderLayout.CENTER);

        table.onSelect(i -> {
            List<Result> rs = table.results();
            Result r = i >= 0 && i < rs.size() ? rs.get(i) : null;
            // A result in another seed (slime seed hunt): the map shows that seed.
            if (r != null && r.seed != null) map.show(r.seed, r.dim != null ? r.dim : dim(), app.seed().type, highlight());
            // A nether row on an overworld page (Seed overview): the map follows the row's dimension.
            else if (r != null && r.dim != null) map.show(app.seed().value(), r.dim, app.seed().type, highlight());
            map.select(i);
            // Slime farms: zoom in far enough to see the chunks around the AFK spot.
            if (slimeChunks() && r != null) map.centerOn(r.x, r.z, 160);
        });
        map.onSelect(table::selectRow);
        map.onStatus(s -> setStatus(s, Theme.TEXT2));
        map.setSlimeChunks(slimeChunks());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) copyTp();
            }
        });
        showResults(List.of());
        setEmpty(emptyText());
    }

    // ------------------------------------------------------------------ for subclasses

    protected abstract void buildForm(JPanel form);

    protected abstract Dim dim();

    /** Biomes drawn bright on the map; the rest are greyed out. Empty = natural colours. */
    /** Shade slime chunks on this page's map. */
    protected boolean slimeChunks() {
        return false;
    }

    protected java.util.Set<String> highlight() {
        return java.util.Set.of();
    }

    /** Runs the search for this seed. */
    protected abstract List<Result> run(Settings.Seed seed, Progress progress) throws Exception;

    /** More buttons under the results, for this tool's results. */
    protected List<JComponent> extraActions() {
        return List.of();
    }

    protected String emptyText() {
        return "Pick your settings and press Search";
    }

    protected JTextField text(JPanel form, String key, String label, String value, int width) {
        JTextField f = Ui.field(value, 0);
        f.setPreferredSize(new Dimension(width, 34));
        f.addActionListener(e -> start());
        fields.put(key, f);
        form.add(Ui.labelled(label, f));
        return f;
    }

    protected <T> JComboBox<T> combo(JPanel form, String key, String label, T[] options, int width) {
        JComboBox<T> c = Ui.combo(options);
        c.setPreferredSize(new Dimension(width, 34));
        fields.put(key, c);
        form.add(Ui.labelled(label, c));
        return c;
    }

    protected JCheckBox check(JPanel form, String key, String label, boolean on) {
        JCheckBox c = Ui.check(label, on);
        fields.put(key, c);
        JPanel p = Ui.clear(new BorderLayout(0, 5));
        p.add(Ui.muted(" "), BorderLayout.NORTH);
        JPanel box = Ui.clear(new java.awt.GridBagLayout());
        box.setPreferredSize(new Dimension(c.getPreferredSize().width + 4, 34));
        box.add(c);
        p.add(box, BorderLayout.CENTER);
        form.add(p);
        return c;
    }

    /** Centre and size fields shared by most tools. */
    protected void areaFields(JPanel form, String defaultSize) {
        areaFields(form, defaultSize, false);
    }

    /** Where a nether search goes: around the centre, or past one of the overworld border's corners. */
    static final String[] WHERE = {"Around a point", "Border corner +x +z", "Border corner −x +z", "Border corner +x −z", "Border corner −x −z"};
    /** The overworld border is at 29,999,984; a nether portal past this (x8) lands on it. */
    static final int PORTAL_LINE = 3_750_000;

    /** big: also offer the BIG_SIZES. */
    protected void areaFields(JPanel form, String defaultSize, boolean big) {
        if (dim() == Dim.NETHER)
            combo(form, "where", "Where", WHERE, 180).setToolTipText("<html><b>Border corner</b>: the nether past 3,750,000 on both x and z"
                    + "<br>(the overworld border ÷ 8), so a portal built there goes to that corner of the overworld border."
                    + "<br>The search square starts at that line and goes out by the search size.</html>");
        text(form, "center", "Centre x, z", "0, 0", 150);
        List<String> sizes = new ArrayList<>(SIZES.keySet());
        if (big) sizes.addAll(BIG_SIZES.keySet());
        JComboBox<String> size = combo(form, "size", "Search size", sizes.toArray(String[]::new), 140);
        size.setSelectedItem(defaultSize);
    }

    protected String value(String key) {
        JComponent c = fields.get(key);
        if (c instanceof JTextField t) return t.getText().trim();
        if (c instanceof JComboBox<?> b) return String.valueOf(b.getSelectedItem());
        if (c instanceof JCheckBox b) return Boolean.toString(b.isSelected());
        return "";
    }

    protected int number(String key, String label) {
        try {
            return Integer.parseInt(value(key).replace(",", "").replace("_", "").trim());
        } catch (NumberFormatException e) {
            throw new BadInput("Enter a whole number for " + label);
        }
    }

    /** The centre field with this radius (pages without a size box). */
    protected Search areaFrom(Settings.Seed seed, int radius) {
        String[] p = value("center").split("[,\\s]+");
        try {
            return new Search(seed.value(), seed.type, Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), radius);
        } catch (RuntimeException e) {
            throw new BadInput("Enter the centre as two numbers, like 0, 0");
        }
    }

    /** The search square from the centre and size fields. */
    protected Search area(Settings.Seed seed) {
        String[] p = value("center").split("[,\\s]+");
        int cx, cz;
        try {
            cx = Integer.parseInt(p[0].trim());
            cz = Integer.parseInt(p[1].trim());
        } catch (RuntimeException e) {
            throw new BadInput("Enter the centre as two numbers, like 0, 0");
        }
        Integer radius = SIZES.getOrDefault(value("size"), BIG_SIZES.get(value("size")));
        int r = radius == null ? 50_000 : radius;
        int where = java.util.Arrays.asList(WHERE).indexOf(value("where"));
        if (where > 0) {
            // A square from the portal line outwards, kept inside the nether's own border.
            r = Math.min(r, (29_999_984 - PORTAL_LINE) / 2);
            int sx = where == 1 || where == 3 ? 1 : -1, sz = where <= 2 ? 1 : -1;
            return new Search(seed.value(), seed.type, sx * (PORTAL_LINE + r), sz * (PORTAL_LINE + r), r);
        }
        return new Search(seed.value(), seed.type, cx, cz, r);
    }

    /** A message for the user about a field they need to fix. */
    protected static final class BadInput extends RuntimeException {
        BadInput(String message) {
            super(message, null, false, false);
        }
    }

    // ------------------------------------------------------------------ running

    /** Presses Search (used by the screenshot tool). */
    void pressSearch() {
        start();
    }

    private void start() {
        if (busy) return;
        Settings.Seed seed = app.seed();
        try {
            Search a = area(seed);
            map.show(seed.value(), dim(), seed.type, highlight());
            map.setSearchArea(a.centerX(), a.centerZ(), a.radius());
        } catch (BadInput e) {
            setStatus(e.getMessage(), Theme.DANGER);
            return;
        }
        save();
        busy = true;
        cancelled = false;
        search.setVisible(false);
        stop.setVisible(true);
        setActionsEnabled(false);
        bar.set(0, Theme.ACCENT);
        setEmpty("Searching…");
        long started = System.nanoTime();
        Progress progress = new Progress() {
            long last;

            @Override
            public void update(double fraction, String message) {
                long now = System.nanoTime();
                if (now - last < 50_000_000L && fraction < 1) return;
                last = now;
                SwingUtilities.invokeLater(() -> {
                    if (!busy) return;
                    bar.set(fraction, Theme.ACCENT);
                    setStatus(message + "  ·  " + elapsed(started), Theme.TEXT2);
                });
            }

            @Override
            public boolean cancelled() {
                return cancelled;
            }
        };
        String[] note = {null};
        Thread t = new Thread(() -> {
            List<Result> results = null;
            String error = null;
            try {
                Progress tracking = new Progress() {
                    @Override
                    public void update(double f, String m) {
                        if (f >= 1) note[0] = m;
                        progress.update(f, m);
                    }

                    @Override
                    public boolean cancelled() {
                        return cancelled;
                    }
                };
                results = Workers.run(() -> run(seed, tracking));
            } catch (BadInput e) {
                error = e.getMessage();
            } catch (Progress.Cancelled e) {
                error = "Stopped";
            } catch (OutOfMemoryError e) {
                error = "Not enough memory for a search this big. Try a smaller size or less detail.";
            } catch (Throwable e) {
                error = "Search failed: " + e;
            }
            List<Result> done = results;
            String err = error;
            double seconds = (System.nanoTime() - started) / 1e9;
            SwingUtilities.invokeLater(() -> finish(seed, done, err, note[0], seconds));
        }, "search-" + id);
        t.setDaemon(true);
        t.start();
    }

    private void finish(Settings.Seed seed, List<Result> results, String error, String note, double seconds) {
        busy = false;
        search.setVisible(true);
        stop.setVisible(false);
        if (error != null) {
            bar.set(0, Theme.ACCENT);
            setStatus(error, error.equals("Stopped") ? Theme.TEXT2 : Theme.DANGER);
            setEmpty(table.results().isEmpty() ? emptyText() : "");
            setActionsEnabled(!table.results().isEmpty());
            return;
        }
        bar.set(1, Theme.SUCCESS);
        String msg = results.isEmpty() ? "Nothing found" : "Found " + results.size() + (note != null && !note.equals("Done") ? "  ·  " + note : "");
        setStatus(msg + "  ·  " + String.format(Locale.ROOT, "%.1f s", seconds), results.isEmpty() ? Theme.WARNING : Theme.TEXT2);
        showResults(results);
        map.fit();
        History.Entry e = new History.Entry();
        e.page = id;
        e.title = title;
        e.seedName = seed.name;
        e.seed = seed.value();
        e.type = seed.type;
        e.params = values();
        e.time = System.currentTimeMillis();
        e.seconds = seconds;
        e.note = note;
        e.results = results;
        History.save(e);
        current = e;
    }

    private static String elapsed(long started) {
        long s = (System.nanoTime() - started) / 1_000_000_000L;
        return s < 60 ? s + " s" : s / 60 + " min " + s % 60 + " s";
    }

    private void showResults(List<Result> results) {
        table.setResults(results);
        // Results in other seeds (slime seed hunt): the map starts on the first one's seed.
        if (!results.isEmpty() && results.get(0).seed != null) map.show(results.get(0).seed, dim(), app.seed().type, highlight());
        map.setResults(results);
        setEmpty(results.isEmpty() ? (busy ? "Searching…" : emptyText()) : "");
        setActionsEnabled(!results.isEmpty());
    }

    private void setEmpty(String text) {
        empty.setText(text);
        empty.setVisible(!text.isEmpty());
    }

    protected void setStatus(String text, Color color) {
        status.setText(text);
        status.setForeground(color);
    }

    /** Called when the seed in the top bar changes. */
    void seedChanged() {
        Settings.Seed seed = app.seed();
        map.show(seed.value(), dim(), seed.type, highlight());
        if (!busy && !table.results().isEmpty()) {
            showResults(List.of());
            setStatus("Seed changed. Earlier results are in Saved results.", Theme.TEXT2);
        }
    }

    /** Called when the page is shown. */
    void shown() {
        Settings.Seed seed = app.seed();
        map.show(seed.value(), dim(), seed.type, highlight());
        if (table.results().isEmpty()) {
            try {
                Search a = area(seed);
                map.setSearchArea(a.centerX(), a.centerZ(), a.radius());
                SwingUtilities.invokeLater(() -> map.centerOn(a.centerX(), a.centerZ(), a.radius()));
            } catch (BadInput ignored) {
                // The user will see the message when they press Search.
            }
        }
    }

    /** Opens a saved search: its settings and results. */
    void open(History.Entry e) {
        if (e.params != null) apply(e.params);
        current = e;
        showResults(e.results);
        SwingUtilities.invokeLater(map::fit);
        bar.set(1, Theme.SUCCESS);
        setStatus("Saved search from " + new java.text.SimpleDateFormat("d MMM yyyy, HH:mm").format(new java.util.Date(e.time))
                + "  ·  seed " + e.seedName, Theme.TEXT2);
    }

    // ------------------------------------------------------------------ remembering field values

    private Map<String, String> values() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : fields.keySet()) m.put(k, value(k));
        return m;
    }

    private void save() {
        app.settings().pages.put(id, values());
        app.settings().save();
    }

    private void restore() {
        Map<String, String> m = app.settings().pages.get(id);
        if (m != null) apply(m);
    }

    private void apply(Map<String, String> m) {
        m.forEach((k, v) -> {
            JComponent c = fields.get(k);
            if (c instanceof JTextField t) t.setText(v);
            else if (c instanceof JComboBox<?> b) {
                for (int i = 0; i < b.getItemCount(); i++) if (String.valueOf(b.getItemAt(i)).equals(v)) b.setSelectedIndex(i);
            } else if (c instanceof JCheckBox b) b.setSelected(Boolean.parseBoolean(v));
        });
    }

    // ------------------------------------------------------------------ result actions

    private JPanel actions() {
        Ui.FlatButton copy = Ui.button("Copy /tp", "copy", Ui.Kind.SECONDARY);
        copy.addActionListener(e -> copyTp());
        Ui.FlatButton waypoints = Ui.button("Waypoints", "pin", Ui.Kind.SECONDARY);
        waypoints.addActionListener(e -> {
            JPopupMenu menu = new JPopupMenu();
            menu.add(item("Add to Xaero's minimap…", this::toXaero));
            menu.add(item("Copy /cway add commands", this::copyCway));
            menu.show(waypoints, 0, -menu.getPreferredSize().height - 4);
        });
        Ui.FlatButton save = Ui.button("Save", "download", Ui.Kind.SECONDARY);
        save.addActionListener(e -> {
            JPopupMenu menu = new JPopupMenu();
            menu.add(item("Save list as CSV…", this::saveCsv));
            menu.add(item("Save map image…", this::saveMap));
            menu.show(save, 0, -menu.getPreferredSize().height - 4);
        });
        Ui.FlatButton verify = Ui.button("Verify", "shield", Ui.Kind.SECONDARY);
        verify.setToolTipText("Check results on a real Minecraft server with this seed (about 1-2 min)");
        verify.addActionListener(e -> verify());
        resultActions.addAll(List.of(copy, waypoints, save));
        resultActions.addAll(extraActions());
        resultActions.add(verify);
        JPanel p = Ui.clear(new FlowLayout(FlowLayout.LEFT, 8, 0));
        Theme.pad(p, 0, 6, 0, 6);
        for (JComponent c : resultActions) p.add(c);
        return p;
    }

    protected static JMenuItem item(String text, Runnable action) {
        JMenuItem i = new JMenuItem(text);
        i.setFont(Theme.FONT);
        i.setForeground(Theme.TEXT);
        i.setBackground(Theme.PANEL);
        i.addActionListener(e -> action.run());
        return i;
    }

    private void setActionsEnabled(boolean on) {
        for (JComponent c : resultActions) c.setEnabled(on && !busy);
    }

    protected Result chosen() {
        Result r = table.selected();
        return r != null ? r : table.results().isEmpty() ? null : table.results().get(0);
    }

    private void copyTp() {
        Result r = chosen();
        if (r == null) return;
        app.copy(r.tp());
        setStatus("Copied " + r.tp(), Theme.SUCCESS);
    }

    private void copyCway() {
        app.copy(Exporters.cwayCommands(table.results(), app.settings().cwayCategory));
        setStatus("Copied " + table.results().size() + " /cway add commands. Paste them in game one at a time, or into a command macro.", Theme.SUCCESS);
    }

    private void toXaero() {
        XaeroDialog.show(app, table.results(), this::setStatus);
    }

    private void saveCsv() {
        File f = app.chooseSaveFile(id + "-results.csv");
        if (f == null) return;
        try {
            Exporters.toCsv(f.toPath(), table.results());
            setStatus("Saved " + f.getName(), Theme.SUCCESS);
        } catch (Exception ex) {
            setStatus("Couldn't save: " + ex.getMessage(), Theme.DANGER);
        }
    }

    private void saveMap() {
        File f = app.chooseSaveFile(id + "-map.png");
        if (f == null) return;
        try {
            ImageIO.write(map.snapshot(), "png", f);
            setStatus("Saved " + f.getName(), Theme.SUCCESS);
        } catch (Exception ex) {
            setStatus("Couldn't save: " + ex.getMessage(), Theme.DANGER);
        }
    }

    // ------------------------------------------------------------------ slime farms

    /** The seed a result is in: its own (seed hunt), or the seed it was searched in. */
    protected long seedOf(Result r) {
        return r.seed != null ? r.seed : current != null ? current.seed : app.seed().value();
    }

    /** Slime farm results: the in-game test rig, the farm picture, and (seed hunt) adding the farm's seed. */
    protected Ui.FlatButton farmButton(boolean addSeed) {
        Ui.FlatButton farm = Ui.button("Farm", "slime", Ui.Kind.SECONDARY);
        farm.setToolTipText("Test rig commands and a picture of the selected farm");
        farm.addActionListener(e -> {
            Result r = chosen();
            if (r == null) return;
            long seed = seedOf(r);
            dev.steelaspect.seedfinder.engine.Slime.Stand stand = r.ref != null && r.ref.length > 3
                    ? dev.steelaspect.seedfinder.engine.Slime.Stand.values()[r.ref[3]] : dev.steelaspect.seedfinder.engine.Slime.Stand.LEVEL;
            JPopupMenu menu = new JPopupMenu();
            menu.add(item("Copy test rig commands (Carpet + WorldEdit)", () -> {
                app.copy(dev.steelaspect.seedfinder.engine.Slime.rig(seed, r.x, r.z, stand));
                setStatus("Copied the test rig for farm at " + r.x + ", " + r.z + ". Paste it into a text file: each line is one command.", Theme.SUCCESS);
            }));
            menu.add(item("Save farm picture…", () -> {
                File f = app.chooseSaveFile("slime-farm-" + seed + "_" + r.x + "_" + r.z + ".png");
                if (f == null) return;
                try {
                    ImageIO.write(dev.steelaspect.seedfinder.engine.Slime.picture(seed, r.x, r.z, stand), "png", f);
                    setStatus("Saved " + f.getName(), Theme.SUCCESS);
                } catch (Exception ex) {
                    setStatus("Couldn't save: " + ex.getMessage(), Theme.DANGER);
                }
            }));
            if (addSeed && r.seed != null) menu.add(item("Copy 10 more seeds with exactly this farm", () -> {
                // Bits 48-63 of a seed don't change slime chunks: adding any multiple of 2^48 gives the same farm.
                StringBuilder b = new StringBuilder();
                for (int k = 1; k <= 10; k++) b.append(r.seed + ((long) k << 48)).append('\n');
                app.copy(b.toString());
                setStatus("Copied 10 seeds with the same slime chunks everywhere (65,536 in all: add any multiple of 2^48)", Theme.SUCCESS);
            }));
            if (addSeed && r.seed != null) menu.add(item("Add seed " + r.seed + " to my seeds", () -> {
                app.addSeed("Slime " + Math.round(r.value / 2.56) / 100.0 + " cq", r.seed);
                setStatus("Added seed " + r.seed + " and switched to it", Theme.SUCCESS);
            }));
            menu.show(farm, 0, -menu.getPreferredSize().height - 4);
        });
        return farm;
    }

    private void verify() {
        if (busy || table.results().isEmpty()) return;
        Settings s = app.settings();
        if (!s.eulaAccepted && !app.askEula()) return;
        Path jar = s.serverJar.isBlank() ? Verifier.findServerJar() : Path.of(s.serverJar);
        if (jar == null || !jar.toFile().isFile()) {
            setStatus("Verify needs the Minecraft 1.21.11 server jar. Set it in Settings.", Theme.DANGER);
            return;
        }
        List<Result> list = table.results().subList(0, Math.min(15, table.results().size()));
        for (Result r : list) r.verified = "checking";
        table.refresh();
        busy = true;
        cancelled = false;
        search.setVisible(false);
        stop.setVisible(true);
        setActionsEnabled(false);
        long started = System.nanoTime();
        Settings.Seed seed = app.seed();
        Thread t = new Thread(() -> {
            String error = null;
            try {
                new Verifier(jar, current != null ? current.seed : seed.value(), current != null && current.type != null ? current.type : seed.type)
                        .verify(list, new Progress() {
                            @Override
                            public void update(double f, String m) {
                                SwingUtilities.invokeLater(() -> {
                                    bar.set(f, Theme.SUCCESS);
                                    setStatus("Verifying on a test server: " + m + "  ·  " + elapsed(started), Theme.TEXT2);
                                });
                            }

                            @Override
                            public boolean cancelled() {
                                return cancelled;
                            }
                        });
            } catch (Progress.Cancelled e) {
                error = "Verify stopped";
            } catch (Exception e) {
                error = "Verify failed: " + e.getMessage();
            }
            String err = error;
            SwingUtilities.invokeLater(() -> {
                busy = false;
                search.setVisible(true);
                stop.setVisible(false);
                for (Result r : list) if ("checking".equals(r.verified)) r.verified = null;
                table.refresh();
                setActionsEnabled(true);
                long ok = list.stream().filter(r -> "ok".equals(r.verified)).count();
                if (err != null) setStatus(err, Theme.DANGER);
                else setStatus(ok + " of " + list.size() + " verified on a real server" + (ok == list.size() ? "" : ". See the Check column."),
                        ok == list.size() ? Theme.SUCCESS : Theme.WARNING);
                bar.set(1, ok == list.size() ? Theme.SUCCESS : Theme.WARNING);
                if (current != null) History.update(current);
            });
        }, "verify-" + id);
        t.setDaemon(true);
        t.start();
    }

    /** A left-aligned flow that wraps onto more rows when the fields don't fit, and asks for the height that takes. */
    static final class WrapLayout extends FlowLayout {
        WrapLayout(int hgap, int vgap) {
            super(FlowLayout.LEFT, hgap, vgap);
        }

        @Override
        public Dimension preferredLayoutSize(java.awt.Container target) {
            return size(target, true);
        }

        @Override
        public Dimension minimumLayoutSize(java.awt.Container target) {
            Dimension d = size(target, false);
            d.width = 0;
            return d;
        }

        private Dimension size(java.awt.Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                // The width the form really gets (its parent's), or a wide default before the first layout.
                int width = target.getWidth() > 0 ? target.getWidth() : target.getParent() != null && target.getParent().getWidth() > 0
                        ? target.getParent().getWidth() : 1200;
                java.awt.Insets in = target.getInsets();
                int max = width - in.left - in.right, rowW = 0, rowH = 0, w = 0, h = 0;
                for (Component c : target.getComponents()) {
                    if (!c.isVisible()) continue;
                    Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
                    if (rowW > 0 && rowW + getHgap() + d.width > max) {
                        w = Math.max(w, rowW);
                        h += rowH + getVgap();
                        rowW = 0;
                        rowH = 0;
                    }
                    rowW += (rowW > 0 ? getHgap() : 0) + d.width;
                    rowH = Math.max(rowH, d.height);
                }
                w = Math.max(w, rowW);
                // FlowLayout also puts a gap above the first row and below the last.
                h += rowH + 2 * getVgap();
                return new Dimension(w + in.left + in.right, h + in.top + in.bottom);
            }
        }
    }
}
