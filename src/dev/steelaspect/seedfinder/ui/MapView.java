package dev.steelaspect.seedfinder.ui;

import dev.steelaspect.seedfinder.engine.Dim;
import dev.steelaspect.seedfinder.engine.Fmt;
import dev.steelaspect.seedfinder.engine.Result;
import dev.steelaspect.seedfinder.engine.SlimeSearch;
import dev.steelaspect.seedfinder.engine.StructureLayers;
import dev.steelaspect.seedfinder.engine.WorldGen;
import dev.steelaspect.seedfinder.engine.WorldType;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/**
 * A zoomable, draggable biome map of the seed with the results as numbered pins.
 * Tiles are 256 px, drawn on background threads; zoom steps double the blocks per pixel.
 */
public final class MapView extends JComponent {
    private static final int TILE = TileRenderer.TILE;
    private static final int MIN_LEVEL = 0, MAX_LEVEL = 14;

    /** Map drawing follows the speed setting too (one thread fewer than searches, at least one). */
    public static void setThreads(int n) {
        TileScheduler.setThreads(Math.max(1, n - 1));
    }

    /** Wipes the cached map tiles (Settings). */
    public static void clearCache() {
        TileCache.clear();
    }

    /** Disk space the map cache uses, in bytes. */
    public static long cacheSize() {
        return TileCache.size();
    }

    private volatile TileRenderer.Spec view;
    private volatile double centerX, centerZ;
    private volatile int level = 6;
    /** Quick previews (one pixel per 8), shown until the sharp tile is ready. */
    private final Map<String, BufferedImage> rough = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) {
            return size() > 1500;
        }
    };
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private List<Result> results = new ArrayList<>();
    private int selected = -1;
    private int[] searchArea; // cx, cz, radius
    private String hoverText = "";
    private IntConsumer onSelect = i -> {};
    private java.util.function.Consumer<String> onStatus = s -> {};
    private volatile int generation;
    private final Timer repaintSoon = new Timer(80, e -> repaint());

    // Chunk overlays: slime chunks (overworld) and a chunk grid, drawn when zoomed in enough to see chunks.
    private static final int SLIME_MAX_BPP = 4, GRID_MAX_BPP = 2;
    private volatile boolean slimeChunks, chunkGrid;

    // Structure icons (the Map page). Icons are worked out per 4096-block cell, only for what's on screen.
    private static final int ICON_CELL = 4096;
    private volatile Set<StructureLayers.Layer> layers = Set.of();
    private final Map<String, List<StructureLayers.Icon>> icons = java.util.Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<StructureLayers.Icon>> e) {
            return size() > 6000;
        }
    });
    private final Set<String> iconPending = ConcurrentHashMap.newKeySet();
    private java.util.function.BiConsumer<StructureLayers.Icon, java.awt.Point> onIcon = (i, p) -> {};
    private final List<java.awt.geom.Point2D.Double> drawnIconSpots = new ArrayList<>();
    private final List<StructureLayers.Icon> drawnIcons = new ArrayList<>();

    // Live biome under the cursor: one background thread, always answering the latest position only.
    private static final java.util.concurrent.ExecutorService HOVER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "map-hover");
        t.setDaemon(true);
        return t;
    });
    private final java.util.concurrent.atomic.AtomicInteger hoverSeq = new java.util.concurrent.atomic.AtomicInteger();

    public MapView() {
        setPreferredSize(new Dimension(480, 480));
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        repaintSoon.setRepeats(false);
        MouseAdapter m = new MouseAdapter() {
            int lastX, lastY;
            boolean dragged;

            @Override
            public void mousePressed(MouseEvent e) {
                lastX = e.getX();
                lastY = e.getY();
                dragged = false;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                double bpp = bpp();
                centerX -= (e.getX() - lastX) * bpp;
                centerZ -= (e.getY() - lastY) * bpp;
                lastX = e.getX();
                lastY = e.getY();
                dragged = true;
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                if (dragged) return;
                int pin = pinAt(e.getX(), e.getY());
                if (pin >= 0) {
                    select(pin);
                    onSelect.accept(pin);
                    return;
                }
                StructureLayers.Icon icon = iconAt(e.getX(), e.getY());
                if (icon != null) {
                    onIcon.accept(icon, e.getPoint());
                    return;
                }
                if (e.getY() < 44 && e.getX() > getWidth() - 116) {
                    if (e.getX() < getWidth() - 80) zoom(-1, getWidth() / 2, getHeight() / 2);
                    else if (e.getX() < getWidth() - 44) zoom(1, getWidth() / 2, getHeight() / 2);
                    else fit();
                    return;
                }
                describe(e.getX(), e.getY());
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int bx = (int) Math.floor(blockX(e.getX())), bz = (int) Math.floor(blockZ(e.getY()));
                String where = bx + ", " + bz + "  ·  chunk " + Math.floorDiv(bx, 16) + ", " + Math.floorDiv(bz, 16);
                hoverText = where;
                setCursor(Cursor.getPredefinedCursor(iconAt(e.getX(), e.getY()) != null || pinAt(e.getX(), e.getY()) >= 0
                        ? Cursor.HAND_CURSOR : Cursor.CROSSHAIR_CURSOR));
                repaint();
                TileRenderer.Spec v = view;
                if (v == null) return;
                int seq = hoverSeq.incrementAndGet();
                HOVER.submit(() -> {
                    if (seq != hoverSeq.get()) return;
                    String b = WorldGen.of(v.seed(), v.dim(), v.type()).mapBiomeAt(bx, bz);
                    SwingUtilities.invokeLater(() -> {
                        if (seq != hoverSeq.get()) return;
                        hoverText = where + "  ·  " + Fmt.biome(b);
                        repaint();
                    });
                });
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                zoom(e.getWheelRotation() < 0 ? -1 : 1, e.getX(), e.getY());
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
    }

    /** Shades slime chunks (overworld only). */
    public void setSlimeChunks(boolean on) {
        slimeChunks = on;
        repaint();
    }

    public void setChunkGrid(boolean on) {
        chunkGrid = on;
        repaint();
    }

    /** Structure layers to draw (the Map page). */
    public void setLayers(Set<StructureLayers.Layer> set) {
        layers = Set.copyOf(set);
        repaint();
    }

    /** Called when a structure icon is clicked, with the click position. */
    public void onIcon(java.util.function.BiConsumer<StructureLayers.Icon, java.awt.Point> listener) {
        onIcon = listener;
    }

    /** Moves the view to a block, keeping the zoom. */
    public void goTo(int x, int z) {
        centerX = x;
        centerZ = z;
        repaint();
    }

    public int zoomLevel() {
        return level;
    }

    public void setZoomLevel(int l) {
        level = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, l));
        repaint();
    }

    public int centerBlockX() {
        return (int) Math.round(centerX);
    }

    public int centerBlockZ() {
        return (int) Math.round(centerZ);
    }

    public void onSelect(IntConsumer listener) {
        onSelect = listener;
    }

    /** Status messages such as "-4190880, -3901524: Crimson forest". */
    public void onStatus(java.util.function.Consumer<String> listener) {
        onStatus = listener;
    }

    public void show(long seed, Dim dim, WorldType type, Set<String> highlight) {
        TileRenderer.Spec v = new TileRenderer.Spec(seed, dim, dim == Dim.OVERWORLD ? type : WorldType.NORMAL, Set.copyOf(highlight));
        if (v.equals(view)) return;
        view = v;
        synchronized (rough) {
            rough.clear();
        }
        generation++;
        repaint();
    }

    public void setSearchArea(int cx, int cz, int radius) {
        searchArea = new int[]{cx, cz, radius};
        repaint();
    }

    public void setResults(List<Result> list) {
        results = new ArrayList<>(list);
        selected = -1;
        repaint();
    }

    public void select(int i) {
        selected = i;
        if (i >= 0 && i < results.size()) {
            Result r = results.get(i);
            double bpp = bpp();
            double px = (r.x - centerX) / bpp + getWidth() / 2.0, pz = (r.z - centerZ) / bpp + getHeight() / 2.0;
            if (px < 40 || pz < 40 || px > getWidth() - 40 || pz > getHeight() - 40) {
                centerX = r.x;
                centerZ = r.z;
            }
        }
        repaint();
    }

    public void centerOn(int x, int z, int radius) {
        centerX = x;
        centerZ = z;
        int size = Math.max(1, Math.min(getWidth(), getHeight()) - 40);
        level = MIN_LEVEL;
        while (level < MAX_LEVEL && radius * 2.0 / (1 << level) > size) level++;
        repaint();
    }

    /** Zoom and move so all results (or the search area) fit. */
    public void fit() {
        if (!results.isEmpty()) {
            long minX = Long.MAX_VALUE, maxX = Long.MIN_VALUE, minZ = Long.MAX_VALUE, maxZ = Long.MIN_VALUE;
            for (Result r : results) {
                minX = Math.min(minX, r.minX);
                maxX = Math.max(maxX, r.maxX);
                minZ = Math.min(minZ, r.minZ);
                maxZ = Math.max(maxZ, r.maxZ);
            }
            centerOn((int) ((minX + maxX) / 2), (int) ((minZ + maxZ) / 2), (int) Math.max(256, Math.max(maxX - minX, maxZ - minZ) / 2 + 64));
        } else if (searchArea != null) {
            centerOn(searchArea[0], searchArea[1], searchArea[2]);
        }
    }

    private double bpp() {
        return 1 << level;
    }

    private double blockX(int px) {
        return centerX + (px - getWidth() / 2.0) * bpp();
    }

    private double blockZ(int py) {
        return centerZ + (py - getHeight() / 2.0) * bpp();
    }

    private void zoom(int dir, int px, int py) {
        int next = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level + dir));
        if (next == level) return;
        double bx = blockX(px), bz = blockZ(py);
        level = next;
        centerX = bx - (px - getWidth() / 2.0) * bpp();
        centerZ = bz - (py - getHeight() / 2.0) * bpp();
        repaint();
    }

    private void describe(int px, int py) {
        if (view == null) return;
        int bx = (int) Math.floor(blockX(px)), bz = (int) Math.floor(blockZ(py));
        TileRenderer.Spec v = view;
        Thread t = new Thread(() -> {
            String b = WorldGen.of(v.seed(), v.dim(), v.type()).biomeAt(bx, bz);
            SwingUtilities.invokeLater(() -> onStatus.accept(describe(v.dim(), bx, bz, b)));
        }, "map-biome-at");
        t.setDaemon(true);
        t.start();
    }

    /** "x, z: Biome · chunk · region file · the other dimension's coordinates" for a clicked block. */
    static String describe(Dim dim, int x, int z, String biome) {
        int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
        String other = switch (dim) {
            case OVERWORLD -> "  ·  Nether " + Math.floorDiv(x, 8) + ", " + Math.floorDiv(z, 8);
            case NETHER -> "  ·  Overworld " + x * 8 + ", " + z * 8;
            case END -> "";
        };
        return x + ", " + z + ": " + Fmt.biome(biome) + "  ·  chunk " + cx + ", " + cz
                + "  ·  r." + Math.floorDiv(cx, 32) + "." + Math.floorDiv(cz, 32) + ".mca" + other;
    }

    private int pinAt(int px, int py) {
        double bpp = bpp();
        for (int i = results.size() - 1; i >= 0; i--) {
            Result r = results.get(i);
            double x = (r.x - centerX) / bpp + getWidth() / 2.0, z = (r.z - centerZ) / bpp + getHeight() / 2.0;
            if (Math.hypot(px - x, py - z) <= 12) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ painting

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Ui.smooth(g);
        int w = getWidth(), h = getHeight();
        g2.setClip(new RoundRectangle2D.Float(0, 0, w, h, 12, 12));
        g2.setColor(new Color(0x101216));
        g2.fillRect(0, 0, w, h);
        if (view != null) paintTiles(g2, w, h);
        double bpp = bpp();
        List<String> hidden = new ArrayList<>();
        paintChunks(g2, w, h, bpp, hidden);
        if (searchArea != null) {
            double x0 = (searchArea[0] - searchArea[2] - centerX) / bpp + w / 2.0, z0 = (searchArea[1] - searchArea[2] - centerZ) / bpp + h / 2.0;
            double size = searchArea[2] * 2.0 / bpp;
            g2.setColor(new Color(255, 255, 255, 110));
            g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{6, 5}, 0));
            g2.draw(new Rectangle((int) x0, (int) z0, (int) size, (int) size));
        }
        hidden.addAll(paintIcons(g2, w, h, bpp));
        paintResults(g2, w, h, bpp);
        paintOverlay(g2, w, h, bpp);
        if (!hidden.isEmpty()) paintHint(g2, w, "Zoom in to see " + String.join(", ", hidden).toLowerCase());
        g2.setClip(null);
        g2.setColor(Theme.BORDER);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 12, 12));
        g2.dispose();
    }

    private void paintTiles(Graphics2D g2, int w, int h) {
        double bpp = bpp();
        long tileBlocks = (long) (TILE * bpp);
        long minTx = (long) Math.floor(blockX(0) / tileBlocks), maxTx = (long) Math.floor(blockX(w) / tileBlocks);
        long minTz = (long) Math.floor(blockZ(0) / tileBlocks), maxTz = (long) Math.floor(blockZ(h) / tileBlocks);
        g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        for (long tx = minTx; tx <= maxTx; tx++)
            for (long tz = minTz; tz <= maxTz; tz++) {
                int px = (int) Math.round((tx * tileBlocks - centerX) / bpp + w / 2.0);
                int pz = (int) Math.round((tz * tileBlocks - centerZ) / bpp + h / 2.0);
                String key = TileCache.key(view, level, tx, tz);
                BufferedImage img = TileCache.memory(key);
                if (img != null) {
                    g2.drawImage(img, px, pz, null);
                    continue;
                }
                BufferedImage quick;
                synchronized (rough) {
                    quick = rough.get(key);
                }
                if (quick != null) g2.drawImage(quick, px, pz, TILE, TILE, null);
                else drawFromParent(g2, tx, tz, px, pz);
                request(key, tx, tz);
            }
    }

    /** While a tile loads, stretch the matching quarter of the zoomed-out tile if we have it. */
    private void drawFromParent(Graphics2D g2, long tx, long tz, int px, int pz) {
        if (level >= MAX_LEVEL) return;
        BufferedImage parent = TileCache.memory(TileCache.key(view, level + 1, Math.floorDiv(tx, 2), Math.floorDiv(tz, 2)));
        if (parent == null) return;
        int sx = (int) Math.floorMod(tx, 2) * TILE / 2, sz = (int) Math.floorMod(tz, 2) * TILE / 2;
        g2.drawImage(parent, px, pz, px + TILE, pz + TILE, sx, sz, sx + TILE / 2, sz + TILE / 2, null);
    }

    /** Queues a tile: disk cache first, else a quick preview and then the sharp tile. */
    private void request(String key, long tx, long tz) {
        if (!pending.add(key)) return;
        TileRenderer.Spec spec = view;
        int lvl = level, gen = generation;
        double cx = (tx + 0.5) * TILE * (double) (1 << lvl), cz = (tz + 0.5) * TILE * (double) (1 << lvl);
        TileScheduler.submit(new TileScheduler.Job() {
            @Override
            public double priority() {
                double bpp = bpp();
                return Math.hypot(cx - centerX, cz - centerZ) / bpp;
            }

            @Override
            public boolean wanted() {
                boolean ok = gen == generation && lvl == level && onScreen(tx, tz, lvl);
                if (!ok) pending.remove(key);
                return ok;
            }

            @Override
            public void run() {
                try {
                    BufferedImage img = TileCache.load(key);
                    if (img == null) {
                        boolean haveQuick;
                        synchronized (rough) {
                            haveQuick = rough.containsKey(key);
                        }
                        if (!haveQuick) {
                            BufferedImage q = TileRenderer.render(spec, lvl, tx, tz, true, false);
                            synchronized (rough) {
                                rough.put(key, q);
                            }
                            SwingUtilities.invokeLater(repaintSoon::restart);
                        }
                        if (!wanted()) return;
                        img = TileRenderer.render(spec, lvl, tx, tz, false, false);
                        TileCache.save(key, img);
                    }
                    if (gen != generation) return;
                    TileCache.remember(key, img);
                    SwingUtilities.invokeLater(repaintSoon::restart);
                } finally {
                    pending.remove(key);
                }
            }
        });
    }

    /** Is the tile on screen (with a one-tile margin)? Called from worker threads. */
    private boolean onScreen(long tx, long tz, int lvl) {
        double bpp = 1 << lvl, tileBlocks = TILE * bpp;
        double x0 = tx * tileBlocks, z0 = tz * tileBlocks;
        double halfW = (getWidth() / 2.0 + TILE) * bpp, halfH = (getHeight() / 2.0 + TILE) * bpp;
        return x0 + tileBlocks >= centerX - halfW && x0 <= centerX + halfW && z0 + tileBlocks >= centerZ - halfH && z0 <= centerZ + halfH;
    }

    /** Draws the structure icons; returns the labels of layers hidden because the map is zoomed out too far. */
    private List<String> paintIcons(Graphics2D g2, int w, int h, double bpp) {
        List<String> hidden = new ArrayList<>();
        drawnIcons.clear();
        drawnIconSpots.clear();
        TileRenderer.Spec v = view;
        if (v == null || layers.isEmpty()) return hidden;
        String world = v.seed() + "/" + v.type() + "/" + v.dim();
        long minCx = Math.floorDiv((long) Math.floor(blockX(0)), ICON_CELL), maxCx = Math.floorDiv((long) Math.floor(blockX(w)), ICON_CELL);
        long minCz = Math.floorDiv((long) Math.floor(blockZ(0)), ICON_CELL), maxCz = Math.floorDiv((long) Math.floor(blockZ(h)), ICON_CELL);
        for (StructureLayers.Layer layer : StructureLayers.Layer.values()) {
            if (!layers.contains(layer) || layer.dim != v.dim()) continue;
            if (bpp > layer.maxBlocksPerPixel) {
                hidden.add(layer.label);
                continue;
            }
            // Strongholds come from one world-wide ring calculation: one job for all of them, not one per cell.
            boolean global = layer == StructureLayers.Layer.STRONGHOLD;
            for (long cx = global ? 0 : minCx; cx <= (global ? 0 : maxCx); cx++)
                for (long cz = global ? 0 : minCz; cz <= (global ? 0 : maxCz); cz++) {
                    String key = world + "/" + layer + "/" + (global ? "all" : cx + "/" + cz);
                    List<StructureLayers.Icon> list = icons.get(key);
                    if (list == null) {
                        requestIcons(key, v, layer, cx, cz, global);
                        continue;
                    }
                    for (StructureLayers.Icon icon : list) {
                        double x = (icon.x() - centerX) / bpp + w / 2.0, z = (icon.z() - centerZ) / bpp + h / 2.0;
                        if (x < -12 || z < -12 || x > w + 12 || z > h + 12) continue;
                        g2.setColor(new Color(0, 0, 0, 110));
                        g2.fill(new Ellipse2D.Double(x - 10, z - 9, 20, 20));
                        g2.setColor(new Color(layer.color));
                        g2.fill(new Ellipse2D.Double(x - 10, z - 10, 20, 20));
                        g2.setColor(new Color(0x16181D));
                        g2.setStroke(new BasicStroke(1.4f));
                        g2.draw(new Ellipse2D.Double(x - 10, z - 10, 20, 20));
                        Icons.get(layer.icon, 12, new Color(0x16181D)).paintIcon(this, g2, (int) Math.round(x - 6), (int) Math.round(z - 6));
                        drawnIcons.add(icon);
                        drawnIconSpots.add(new java.awt.geom.Point2D.Double(x, z));
                    }
                }
        }
        return hidden;
    }

    /** Slime chunks and the chunk grid, for the chunks on screen (slime chunks are cheap: just the seed). */
    private void paintChunks(Graphics2D g2, int w, int h, double bpp, List<String> hidden) {
        TileRenderer.Spec v = view;
        if (v == null) return;
        boolean slime = slimeChunks && v.dim() == Dim.OVERWORLD, grid = chunkGrid;
        if (slime && bpp > SLIME_MAX_BPP) {
            hidden.add("Slime chunks");
            slime = false;
        }
        if (grid && bpp > GRID_MAX_BPP) {
            hidden.add("Chunk grid");
            grid = false;
        }
        if (!slime && !grid) return;
        int cx0 = Math.floorDiv((int) Math.floor(blockX(0)), 16), cx1 = Math.floorDiv((int) Math.floor(blockX(w)), 16);
        int cz0 = Math.floorDiv((int) Math.floor(blockZ(0)), 16), cz1 = Math.floorDiv((int) Math.floor(blockZ(h)), 16);
        double size = 16 / bpp;
        Color fill = new Color(110, 220, 90, 95), edge = new Color(140, 240, 110, 190), line = new Color(255, 255, 255, 38);
        g2.setStroke(new BasicStroke(1f));
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cz = cz0; cz <= cz1; cz++) {
                double x = (cx * 16.0 - centerX) / bpp + w / 2.0, z = (cz * 16.0 - centerZ) / bpp + h / 2.0;
                java.awt.geom.Rectangle2D.Double r = new java.awt.geom.Rectangle2D.Double(x, z, size, size);
                if (slime && SlimeSearch.isSlimeChunk(v.seed(), cx, cz)) {
                    g2.setColor(fill);
                    g2.fill(r);
                    if (size >= 6) {
                        g2.setColor(edge);
                        g2.draw(r);
                    }
                }
                if (grid) {
                    g2.setColor(line);
                    g2.draw(r);
                }
            }
    }

    private void requestIcons(String key, TileRenderer.Spec v, StructureLayers.Layer layer, long cx, long cz, boolean global) {
        if (!iconPending.add(key)) return;
        int gen = generation;
        double mx = (cx + 0.5) * ICON_CELL, mz = (cz + 0.5) * ICON_CELL;
        TileScheduler.submit(new TileScheduler.Job() {
            @Override
            public double priority() {
                // Icons are quick and the main thing on the Map page: they go before map tiles.
                return -1e9 + Math.hypot(mx - centerX, mz - centerZ) / bpp();
            }

            @Override
            public boolean wanted() {
                double halfW = (getWidth() / 2.0 + 64) * bpp(), halfH = (getHeight() / 2.0 + 64) * bpp();
                boolean ok = gen == generation && layers.contains(layer) && bpp() <= layer.maxBlocksPerPixel
                        && (global || mx + ICON_CELL / 2.0 >= centerX - halfW && mx - ICON_CELL / 2.0 <= centerX + halfW
                        && mz + ICON_CELL / 2.0 >= centerZ - halfH && mz - ICON_CELL / 2.0 <= centerZ + halfH);
                if (!ok) iconPending.remove(key);
                return ok;
            }

            @Override
            public void run() {
                try {
                    int x0 = global ? -30_000_000 : (int) Math.max(-30_000_000L, cx * ICON_CELL);
                    int z0 = global ? -30_000_000 : (int) Math.max(-30_000_000L, cz * ICON_CELL);
                    int x1 = global ? 30_000_000 : (int) Math.min(30_000_000L, cx * ICON_CELL + ICON_CELL - 1);
                    int z1 = global ? 30_000_000 : (int) Math.min(30_000_000L, cz * ICON_CELL + ICON_CELL - 1);
                    icons.put(key, StructureLayers.find(WorldGen.of(v.seed(), v.dim(), v.type()), layer, x0, z0, x1, z1));
                    SwingUtilities.invokeLater(repaintSoon::restart);
                } finally {
                    iconPending.remove(key);
                }
            }
        });
    }

    private StructureLayers.Icon iconAt(int px, int py) {
        for (int i = drawnIcons.size() - 1; i >= 0; i--)
            if (drawnIconSpots.get(i).distance(px, py) <= 11) return drawnIcons.get(i);
        return null;
    }

    private void paintHint(Graphics2D g2, int w, String text) {
        g2.setFont(Theme.FONT_SMALL);
        FontMetrics fm = g2.getFontMetrics();
        int tw = fm.stringWidth(text) + 24;
        g2.setColor(new Color(22, 24, 29, 220));
        g2.fill(new RoundRectangle2D.Float((w - tw) / 2f, 10, tw, 26, 8, 8));
        g2.setColor(Theme.TEXT2);
        g2.drawString(text, (w - tw) / 2f + 12, 28);
    }

    private void paintResults(Graphics2D g2, int w, int h, double bpp) {
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            double x0 = (r.minX - centerX) / bpp + w / 2.0, z0 = (r.minZ - centerZ) / bpp + h / 2.0;
            double bw = (r.maxX - r.minX + 1) / bpp, bh = (r.maxZ - r.minZ + 1) / bpp;
            if (bw >= 4 && bh >= 4) {
                g2.setColor(i == selected ? Theme.ACCENT : new Color(255, 255, 255, 150));
                g2.setStroke(new BasicStroke(i == selected ? 2f : 1.2f));
                g2.draw(new RoundRectangle2D.Double(x0, z0, bw, bh, 4, 4));
            }
        }
        g2.setFont(Theme.FONT_SMALL.deriveFont(java.awt.Font.BOLD));
        FontMetrics fm = g2.getFontMetrics();
        for (int i = results.size() - 1; i >= -1; i--) {
            int k = i == -1 ? selected : i;
            if (k < 0 || (i >= 0 && i == selected)) continue;
            Result r = results.get(k);
            double x = (r.x - centerX) / bpp + w / 2.0, z = (r.z - centerZ) / bpp + h / 2.0;
            if (x < -20 || z < -20 || x > w + 20 || z > h + 20) continue;
            float rad = (k == selected ? 12 : 10) - (bpp >= 128 ? 3 : 0);
            g2.setColor(new Color(0, 0, 0, 120));
            g2.fill(new Ellipse2D.Double(x - rad, z - rad + 1.5, rad * 2, rad * 2));
            g2.setColor(k == selected ? Theme.ACCENT : Theme.TEXT);
            g2.fill(new Ellipse2D.Double(x - rad, z - rad, rad * 2, rad * 2));
            String s = Integer.toString(k + 1);
            g2.setColor(k == selected ? Theme.ON_ACCENT : new Color(0x16181D));
            g2.drawString(s, (float) (x - fm.stringWidth(s) / 2.0), (float) (z + fm.getAscent() / 2.0 - 1.5));
        }
    }

    private void paintOverlay(Graphics2D g2, int w, int h, double bpp) {
        // Zoom buttons and "fit".
        g2.setStroke(new BasicStroke(1f));
        String[] labels = {"-", "+", "fit"};
        int x = w - 116;
        g2.setFont(Theme.FONT_BOLD);
        FontMetrics fm = g2.getFontMetrics();
        for (int i = 0; i < 3; i++) {
            int bw = i == 2 ? 40 : 32;
            g2.setColor(new Color(22, 24, 29, 220));
            g2.fill(new RoundRectangle2D.Float(x, 10, bw, 28, 8, 8));
            g2.setColor(Theme.BORDER_STRONG);
            g2.draw(new RoundRectangle2D.Float(x + 0.5f, 10.5f, bw - 1, 27, 8, 8));
            g2.setColor(Theme.TEXT);
            g2.drawString(labels[i], x + (bw - fm.stringWidth(labels[i])) / 2f, 29);
            x += bw + 4;
        }
        // Cursor position and scale.
        g2.setFont(Theme.FONT_SMALL);
        fm = g2.getFontMetrics();
        String info = (view == null ? "" : view.dim().label) + (hoverText.isEmpty() ? "" : "  ·  " + hoverText);
        String scale = Fmt.big(bpp * 100) + " blocks";
        int iw = fm.stringWidth(info) + 20;
        g2.setColor(new Color(22, 24, 29, 220));
        g2.fill(new RoundRectangle2D.Float(10, h - 36, iw, 26, 8, 8));
        g2.setColor(Theme.TEXT2);
        g2.drawString(info, 20, h - 18);
        int sw = fm.stringWidth(scale) + 136;
        g2.setColor(new Color(22, 24, 29, 220));
        g2.fill(new RoundRectangle2D.Float(w - sw - 10, h - 36, sw, 26, 8, 8));
        g2.setColor(Theme.TEXT2);
        g2.setStroke(new BasicStroke(2f));
        g2.drawLine(w - sw, h - 23, w - sw + 100, h - 23);
        g2.drawLine(w - sw, h - 27, w - sw, h - 19);
        g2.drawLine(w - sw + 100, h - 27, w - sw + 100, h - 19);
        g2.drawString(scale, w - sw + 112, h - 18);
    }

    /** The current view as an image, for "Save map". */
    public BufferedImage snapshot() {
        BufferedImage img = new BufferedImage(Math.max(1, getWidth()), Math.max(1, getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        paint(g);
        g.dispose();
        return img;
    }
}
