package dev.steelaspect.seedfinder.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/** The app icon, drawn in code (kept apart from the app so the launcher can use it before Minecraft is loaded). */
public final class AppIcon {
    private AppIcon() {}

    /** Writes the icon as a PNG (used by the build for the app menu entry). */
    public static void main(String[] args) throws Exception {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.drawImage(image(256), 0, 0, null);
        g.dispose();
        javax.imageio.ImageIO.write(img, "png", new java.io.File(args[0]));
    }

    /** The app icon: an orange tile with a map pin. */
    public static Image image(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = Ui.smooth(img.getGraphics());
        g.setColor(Theme.ACCENT);
        g.fill(new RoundRectangle2D.Float(0, 0, size, size, size * 0.42f, size * 0.42f));
        g.setColor(new Color(0x2A160C));
        float s = size / 16f;
        g.setStroke(new BasicStroke(1.3f * s, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.scale(s, s);
        java.awt.geom.Path2D p = new java.awt.geom.Path2D.Float();
        p.moveTo(2.5, 12.5);
        p.lineTo(6, 6.5);
        p.lineTo(8.3, 9.5);
        p.lineTo(10, 7.5);
        p.lineTo(13.5, 12.5);
        p.closePath();
        g.fill(p);
        g.fill(new java.awt.geom.Ellipse2D.Float(9.6f, 2.6f, 3.2f, 3.2f));
        g.dispose();
        return img;
    }
}
