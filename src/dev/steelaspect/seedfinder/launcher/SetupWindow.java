package dev.steelaspect.seedfinder.launcher;

import dev.steelaspect.seedfinder.ui.AppIcon;
import dev.steelaspect.seedfinder.ui.Theme;
import dev.steelaspect.seedfinder.ui.Ui;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** The first-run window: explains the one-time download and shows its progress. */
final class SetupWindow {
    private SetupWindow() {}

    /** Returns true when the files are ready, false if the user closed the window. */
    static boolean run(Setup setup) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            JFrame f = build(setup, done, ok);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
        done.await();
        return ok.get();
    }

    /** Draws the window into a PNG without showing it (for the README and testing). */
    static void screenshot(String file) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            JFrame f = build(new Setup(), new CountDownLatch(1), new AtomicBoolean());
            java.awt.Container c = f.getContentPane();
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(c.getWidth(), c.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            c.paint(g);
            g.dispose();
            try {
                javax.imageio.ImageIO.write(img, "png", new java.io.File(file));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            f.dispose();
        });
    }

    private static JFrame build(Setup setup, CountDownLatch done, AtomicBoolean ok) {
        {
            JFrame f = new JFrame("Alloy Seed Finder");
            f.setIconImage(AppIcon.image(64));
            f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            f.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    done.countDown();
                }
            });
            JPanel body = new JPanel(new BorderLayout(0, 16));
            body.setBackground(Theme.PANEL);
            Theme.pad(body, 24, 26, 22, 26);
            JLabel title = Ui.title("Getting Minecraft's world generator");
            title.setIcon(new javax.swing.ImageIcon(AppIcon.mark(28)));
            title.setIconTextGap(12);
            body.add(title, BorderLayout.NORTH);
            JPanel text = Ui.clear(new GridLayout(0, 1, 0, 4));
            text.add(Ui.muted("The Seed Finder uses Minecraft " + Setup.VERSION + "'s own code, so its results match the real game."));
            text.add(Ui.muted("This one-time setup downloads the official server files straight from Mojang (about 70 MB)"));
            text.add(Ui.muted("and converts them. It takes a minute or two and is only needed once."));
            Ui.Progress bar = new Ui.Progress();
            bar.setPreferredSize(new Dimension(520, 6));
            JLabel status = Ui.muted("Ready to start");
            JPanel prog = Ui.clear(new BorderLayout(0, 8));
            prog.add(bar, BorderLayout.NORTH);
            prog.add(status, BorderLayout.CENTER);
            JPanel mid = Ui.clear(new BorderLayout(0, 18));
            mid.add(text, BorderLayout.NORTH);
            mid.add(prog, BorderLayout.CENTER);
            body.add(mid, BorderLayout.CENTER);
            Ui.FlatButton quit = Ui.button("Quit", null, Ui.Kind.SECONDARY);
            Ui.FlatButton start = Ui.button("Download and set up", "download", Ui.Kind.PRIMARY);
            quit.addActionListener(e -> f.dispose());
            start.addActionListener(e -> {
                start.setEnabled(false);
                Thread t = new Thread(() -> {
                    try {
                        setup.run((s, v) -> SwingUtilities.invokeLater(() -> {
                            bar.set(v, Theme.ACCENT);
                            status.setText(s);
                        }));
                        ok.set(true);
                        SwingUtilities.invokeLater(f::dispose);
                    } catch (Exception ex) {
                        SwingUtilities.invokeLater(() -> {
                            status.setText("Setup failed: " + ex.getMessage() + ". Check your internet connection and try again.");
                            status.setForeground(Theme.DANGER);
                            start.setText("Try again");
                            start.setEnabled(true);
                        });
                    }
                }, "setup");
                t.setDaemon(true);
                t.start();
            });
            JPanel buttons = Ui.clear(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.add(quit);
            buttons.add(start);
            body.add(buttons, BorderLayout.SOUTH);
            f.setContentPane(body);
            f.pack();
            f.setResizable(false);
            return f;
        }
    }
}
