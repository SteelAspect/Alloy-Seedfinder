package dev.steelaspect.seedfinder.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * The Alloy logo, drawn in code from its four shapes (kept apart from the app so the launcher can use it before
 * Minecraft is loaded).
 */
public final class AppIcon {
    /** The logo's shapes in its 24 x 24 drawing, with their colours. */
    private static final float[][] SHAPES = {
            {18, 2, 8, 13, 13.5f, 13, 16.6f, 9},
            {17, 11, 22.5f, 11, 12, 22, 14.6f, 15},
            {4, 14.5f, 10, 14.5f, 8.6f, 16.4f, 2.6f, 16.4f},
            {2, 18.5f, 9, 18.5f, 7.6f, 20.4f, 0.6f, 20.4f},
    };
    private static final Color[] COLORS = {new Color(0xE4E7F5), new Color(0x4682B4), new Color(0x9397AB), new Color(0x75798C)};
    /** The tile behind the logo in app icons, so its pale shape shows on light desktops too. */
    private static final Color TILE = new Color(0x1B1E24);

    private AppIcon() {}

    /** Writes the icon as a PNG (used by the build for the app menu entry). */
    public static void main(String[] args) throws Exception {
        javax.imageio.ImageIO.write((BufferedImage) image(256), "png", new java.io.File(args[0]));
    }

    /** The app icon: the logo on a dark rounded tile. */
    public static Image image(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = Ui.smooth(img.getGraphics());
        g.setColor(TILE);
        g.fill(new RoundRectangle2D.Float(0, 0, size, size, size * 0.42f, size * 0.42f));
        draw(g, size * 0.12f, size * 0.76f);
        g.dispose();
        return img;
    }

    /** The logo alone, for dark backgrounds (the app's top bar). */
    public static Image mark(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = Ui.smooth(img.getGraphics());
        draw(g, 0, size);
        g.dispose();
        return img;
    }

    /** The logo in a square of this size at (at, at), centred on its own bounds (x 0.6 to 22.5, y 2 to 22). */
    private static void draw(Graphics2D g, float at, float size) {
        float s = size / 22f, dx = at + (size - 21.9f * s) / 2 - 0.6f * s, dy = at + (size - 20f * s) / 2 - 2f * s;
        for (int i = 0; i < SHAPES.length; i++) {
            float[] p = SHAPES[i];
            Path2D.Float path = new Path2D.Float();
            path.moveTo(dx + p[0] * s, dy + p[1] * s);
            for (int k = 2; k < p.length; k += 2) path.lineTo(dx + p[k] * s, dy + p[k + 1] * s);
            path.closePath();
            g.setColor(COLORS[i]);
            g.fill(path);
        }
    }
}
