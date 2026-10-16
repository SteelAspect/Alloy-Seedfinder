package dev.steelaspect.seedfinder;

import dev.steelaspect.seedfinder.engine.Mc;
import dev.steelaspect.seedfinder.ui.SeedFinderApp;
import dev.steelaspect.seedfinder.ui.Theme;

import javax.swing.SwingUtilities;

/** No arguments: open the app. With arguments: the command line (see Cli). */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        Thread.currentThread().setContextClassLoader(Main.class.getClassLoader());
        // Minecraft's startup redirects System.err into its logger; print crashes where people can see them.
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            Mc.ERR.println("Cytra Seed Finder crashed in " + t.getName() + ":");
            e.printStackTrace(Mc.ERR);
        });
        if (args.length > 0 && !args[0].equals("--gui")) {
            int code;
            try {
                code = Cli.run(args);
            } catch (Throwable e) {
                e.printStackTrace(Mc.ERR);
                code = 1;
            }
            System.exit(code);
            return;
        }
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        // Threads started from the UI must see this class loader (it's the one that can see Minecraft's code).
        ClassLoader loader = Main.class.getClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        SwingUtilities.invokeLater(() -> {
            Thread.currentThread().setContextClassLoader(loader);
            Theme.install();
            new SeedFinderApp().launch();
        });
    }
}
