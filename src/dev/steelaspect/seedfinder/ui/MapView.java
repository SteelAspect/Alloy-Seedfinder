package dev.steelaspect.seedfinder.ui;

import dev.steelaspect.seedfinder.engine.Dim;
import dev.steelaspect.seedfinder.engine.Fmt;
import dev.steelaspect.seedfinder.engine.Result;
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
                hoverText = bx + ", " + bz;
                repaint();
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
            SwingUtilities.invokeLater(() -> onStatus.accept(bx + ", " + bz + ": " + Fmt.biome(b)));
        }, "map-biome-at");
        t.setDaemon(true);
        t.start();
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
        if (searchArea != null) {
            double x0 = (searchArea[0] - searchArea[2] - centerX) / bpp + w / 2.0, z0 = (searchArea[1] - searchArea[2] - centerZ) / bpp + h / 2.0;
            double size = searchArea[2] * 2.0 / bpp;
            g2.setColor(new Color(255, 255, 255, 110));
            g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{6, 5}, 0));
            g2.draw(new Rectangle((int) x0, (int) z0, (int) size, (int) size));
        }
        paintResults(g2, w, h, bpp);
        paintOverlay(g2, w, h, bpp);
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
