package dev.steelaspect.seedfinder.launcher;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;

/**
 * The jar's entry point. Compiled for Java 8 so that on an old Java it can still explain what's needed,
 * instead of crashing with a version error.
 */
public final class Launcher {
    public static final int JAVA_NEEDED = 21;

    private Launcher() {}

    public static void main(String[] args) throws Exception {
        if (javaVersion() < JAVA_NEEDED) {
            String msg = "Alloy Seed Finder needs Java " + JAVA_NEEDED + " or newer (this is Java " + System.getProperty("java.version") + ").\n\n"
                    + "Easiest: open the Minecraft launcher and play 1.21 once (it installs Java 21), then start the Seed Finder again.\n"
                    + "Or install Java 21 from https://adoptium.net";
            if (GraphicsEnvironment.isHeadless() || args.length > 0) System.err.println(msg);
            else JOptionPane.showMessageDialog(null, msg, "Alloy Seed Finder", JOptionPane.WARNING_MESSAGE);
            System.exit(1);
        }
        Class.forName("dev.steelaspect.seedfinder.launcher.Boot").getMethod("main", String[].class).invoke(null, (Object) args);
    }

    static int javaVersion() {
        String v = System.getProperty("java.specification.version", "1.8");
        if (v.startsWith("1.")) v = v.substring(2);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 8;
        }
    }
}
