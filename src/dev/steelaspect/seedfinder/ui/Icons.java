package dev.steelaspect.seedfinder.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** Simple outline icons drawn on a 16x16 grid, so the app needs no image files. */
public final class Icons {
    private Icons() {}

    public static Icon get(String name, int size, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = Ui.smooth(g);
                g2.translate(x, y);
                g2.scale(size / 16.0, size / 16.0);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                draw(g2, name);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    private static void draw(Graphics2D g, String name) {
        switch (name) {
            case "mountain" -> g.draw(path(1, 13, 6, 5, 9, 9, 11, 6, 15, 13, 1, 13));
            case "square" -> g.draw(new RoundRectangle2D.Float(2.5f, 2.5f, 11, 11, 2, 2));
            case "hut" -> {
                g.draw(path(2, 8, 8, 3, 14, 8));
                g.draw(path(4, 7, 4, 13, 12, 13, 12, 7));
                g.draw(path(7, 13, 7, 10, 9, 10, 9, 13));
            }
            case "waves" -> {
                for (int i = 0; i < 3; i++) {
                    Path2D p = new Path2D.Float();
                    float y = 4.5f + i * 3.5f;
                    p.moveTo(1.5, y);
                    p.curveTo(3.5, y - 2, 5.5, y + 2, 8, y);
                    p.curveTo(10.5, y - 2, 12.5, y + 2, 14.5, y);
                    g.draw(p);
                }
            }
            case "flame" -> {
                Path2D p = new Path2D.Float();
                p.moveTo(8, 14.5);
                p.curveTo(4, 14.5, 2.8, 11.5, 3.5, 9);
                p.curveTo(4.2, 6.8, 6, 6.5, 6.5, 4);
                p.curveTo(8.5, 5.5, 9, 7, 8.8, 8.5);
                p.curveTo(10, 7.8, 10.5, 6.8, 10.5, 5.5);
                p.curveTo(12.8, 7.5, 13.5, 10, 12.5, 12);
                p.curveTo(11.7, 13.7, 10, 14.5, 8, 14.5);
                g.draw(p);
            }
            case "castle" -> {
                g.draw(path(2, 14, 2, 4, 4, 4, 4, 6, 6, 6, 6, 4, 10, 4, 10, 6, 12, 6, 12, 4, 14, 4, 14, 14, 2, 14));
                g.draw(path(7, 14, 7, 10, 9, 10, 9, 14));
            }
            case "clock" -> {
                g.draw(new Ellipse2D.Float(2, 2, 12, 12));
                g.draw(path(8, 5, 8, 8, 10.5f, 9.5f));
            }
            case "gear" -> {
                g.draw(new Ellipse2D.Float(5.5f, 5.5f, 5, 5));
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    g.draw(new Line2D.Double(8 + Math.cos(a) * 4.6, 8 + Math.sin(a) * 4.6, 8 + Math.cos(a) * 6.6, 8 + Math.sin(a) * 6.6));
                }
            }
            case "copy" -> {
                g.draw(new RoundRectangle2D.Float(5.5f, 5.5f, 8, 8, 2, 2));
                g.draw(path(10.5f, 3.5f, 10.5f, 2.5f, 2.5f, 2.5f, 2.5f, 10.5f, 3.5f, 10.5f));
            }
            case "pin" -> {
                Path2D p = new Path2D.Float();
                p.moveTo(8, 14.5);
                p.curveTo(4, 10, 3, 8, 3, 6.5);
                p.curveTo(3, 3.7, 5.3, 1.5, 8, 1.5);
                p.curveTo(10.7, 1.5, 13, 3.7, 13, 6.5);
                p.curveTo(13, 8, 12, 10, 8, 14.5);
                g.draw(p);
                g.draw(new Ellipse2D.Float(6.2f, 4.7f, 3.6f, 3.6f));
            }
            case "download" -> {
                g.draw(path(8, 2, 8, 10));
                g.draw(path(4.5f, 6.5f, 8, 10, 11.5f, 6.5f));
                g.draw(path(2.5f, 11, 2.5f, 13.5f, 13.5f, 13.5f, 13.5f, 11));
            }
            case "check" -> g.draw(path(3, 8.5f, 6.5f, 12, 13, 4.5f));
            case "shield" -> {
                g.draw(path(8, 1.8f, 13.5f, 3.8f, 13.5f, 8, 8, 14.3f));
                g.draw(path(8, 1.8f, 2.5f, 3.8f, 2.5f, 8, 8, 14.3f));
                g.draw(path(5.5f, 8, 7.3f, 9.8f, 10.5f, 6.3f));
            }
            case "play" -> {
                Path2D p = path(4.5f, 2.5f, 13, 8, 4.5f, 13.5f, 4.5f, 2.5f);
                g.fill(p);
            }
            case "stop" -> g.fill(new RoundRectangle2D.Float(3.5f, 3.5f, 9, 9, 2, 2));
            case "plus" -> {
                g.draw(path(8, 3, 8, 13));
                g.draw(path(3, 8, 13, 8));
            }
            case "chevron" -> g.draw(path(4, 6, 8, 10, 12, 6));
            case "trash" -> {
                g.draw(path(2.5f, 4.5f, 13.5f, 4.5f));
                g.draw(path(6, 4.5f, 6, 2.5f, 10, 2.5f, 10, 4.5f));
                g.draw(path(4, 4.5f, 4.8f, 14, 11.2f, 14, 12, 4.5f));
            }
            case "open" -> {
                g.draw(path(9, 2.5f, 13.5f, 2.5f, 13.5f, 7));
                g.draw(path(13.5f, 2.5f, 7, 9));
                g.draw(path(11.5f, 9.5f, 11.5f, 13.5f, 2.5f, 13.5f, 2.5f, 4.5f, 6.5f, 4.5f));
            }
            case "search" -> {
                g.draw(new Ellipse2D.Float(2.5f, 2.5f, 8, 8));
                g.draw(path(9.3f, 9.3f, 13.5f, 13.5f));
            }
            case "map" -> {
                g.draw(path(1.5f, 4, 5.5f, 2, 10.5f, 4, 14.5f, 2, 14.5f, 12, 10.5f, 14, 5.5f, 12, 1.5f, 14, 1.5f, 4));
                g.draw(path(5.5f, 2, 5.5f, 12));
                g.draw(path(10.5f, 4, 10.5f, 14));
            }
            case "x" -> {
                g.draw(path(4, 4, 12, 12));
                g.draw(path(12, 4, 4, 12));
            }
            case "target" -> {
                g.draw(new Ellipse2D.Float(2.5f, 2.5f, 11, 11));
                g.fill(new Ellipse2D.Float(6.5f, 6.5f, 3, 3));
            }
            case "house" -> {
                g.draw(path(2, 8, 8, 2.5f, 14, 8));
                g.draw(path(3.5f, 7, 3.5f, 13.5f, 12.5f, 13.5f, 12.5f, 7));
            }
            case "flag" -> {
                g.draw(path(4, 14, 4, 2));
                g.draw(path(4, 2.5f, 13, 4.5f, 4, 8));
            }
            case "mansion" -> {
                g.draw(path(1.5f, 14, 1.5f, 7, 5, 4, 8, 7, 11, 4, 14.5f, 7, 14.5f, 14, 1.5f, 14));
                g.draw(path(7, 14, 7, 10.5f, 9, 10.5f, 9, 14));
            }
            case "pyramid" -> {
                g.draw(path(1.5f, 13.5f, 8, 2.5f, 14.5f, 13.5f, 1.5f, 13.5f));
                g.draw(path(4.8f, 8, 11.2f, 8));
            }
            case "dome" -> {
                Path2D p = new Path2D.Float();
                p.moveTo(1.5, 13);
                p.curveTo(1.5, 4.5, 14.5, 4.5, 14.5, 13);
                p.closePath();
                g.draw(p);
                g.draw(path(6.5f, 13, 6.5f, 10, 9.5f, 10, 9.5f, 13));
            }
            case "city" -> {
                g.draw(path(1.5f, 14, 14.5f, 14));
                for (int i = 0; i < 3; i++) g.draw(path(3.5f + i * 4.5f, 14, 3.5f + i * 4.5f, 5));
                g.draw(path(1.5f, 5, 14.5f, 5));
                g.draw(path(3, 3, 13, 3));
            }
            case "trial" -> {
                g.draw(new RoundRectangle2D.Float(2.5f, 2.5f, 11, 11, 2, 2));
                g.draw(path(8, 2.5f, 8, 13.5f));
                g.draw(path(2.5f, 8, 13.5f, 8));
            }
            case "eye" -> {
                Path2D p = new Path2D.Float();
                p.moveTo(1.5, 8);
                p.curveTo(4, 3.5, 12, 3.5, 14.5, 8);
                p.curveTo(12, 12.5, 4, 12.5, 1.5, 8);
                g.draw(p);
                g.fill(new Ellipse2D.Float(6, 6, 4, 4));
            }
            case "portal" -> {
                g.draw(new RoundRectangle2D.Float(3.5f, 1.5f, 9, 13, 1, 1));
                g.draw(new RoundRectangle2D.Float(6, 4, 4, 8, 1, 1));
            }
            case "ship" -> {
                g.draw(path(1.5f, 9, 14.5f, 9, 12, 13.5f, 4, 13.5f, 1.5f, 9));
                g.draw(path(8, 9, 8, 2));
                g.draw(path(8, 2.5f, 12.5f, 7, 8, 7));
            }
            case "tower" -> {
                g.draw(path(4, 14.5f, 4, 4, 12, 4, 12, 14.5f));
                g.draw(path(3, 4, 3, 1.5f, 5.5f, 1.5f, 5.5f, 3, 10.5f, 3, 10.5f, 1.5f, 13, 1.5f, 13, 4));
                g.draw(path(6.5f, 14.5f, 6.5f, 10.5f, 9.5f, 10.5f, 9.5f, 14.5f));
            }
            default -> g.draw(new Ellipse2D.Float(3, 3, 10, 10));
        }
    }

    private static Path2D path(float... xy) {
        Path2D p = new Path2D.Float();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        return p;
    }
}
