package dev.steelaspect.seedfinder.launcher;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Gets the Minecraft files ready (first run only), then starts the app in its own class loader that also sees
 * the converted Minecraft code.
 */
public final class Boot {
    private Boot() {}

    public static void main(String[] args) throws Exception {
        // Minecraft's own logging config writes a logs/ folder wherever the app starts; use ours instead.
        System.setProperty("log4j2.configurationFile", "seedfinder-log4j2.xml");
        if (args.length == 2 && args[0].equals("setup-screenshot")) {
            SetupWindow.screenshot(args[1]);
            return;
        }
        boolean cli = args.length > 0 && !args[0].equals("--gui");
        if (minecraftOnClasspath()) {
            // Development: Minecraft is already on the classpath.
            Class.forName("dev.steelaspect.seedfinder.Main").getMethod("main", String[].class).invoke(null, (Object) args);
            return;
        }
        Setup setup = new Setup();
        if (!setup.ready()) {
            if (cli || GraphicsEnvironment.isHeadless()) {
                System.out.println("First run: getting Minecraft " + Setup.VERSION + " files from Mojang (about 70 MB, one time).");
                int[] last = {-1};
                setup.run((text, f) -> {
                    int pct = (int) (f * 100);
                    if (pct != last[0]) {
                        last[0] = pct;
                        System.out.print("\r  " + pct + "%  " + text + "          ");
                    }
                });
                System.out.println();
            } else if (!SetupWindow.run(setup)) {
                System.exit(0);
                return;
            }
        }
        List<URL> urls = new ArrayList<>();
        urls.add(Boot.class.getProtectionDomain().getCodeSource().getLocation());
        for (Path p : setup.classpath()) urls.add(p.toUri().toURL());
        System.setProperty("seedfinder.serverJar", setup.serverJar().toString());
        URLClassLoader loader = new URLClassLoader("seedfinder", urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        loader.loadClass("dev.steelaspect.seedfinder.Main").getMethod("main", String[].class).invoke(null, (Object) args);
    }

    private static boolean minecraftOnClasspath() {
        try {
            Class.forName("net.minecraft.SharedConstants", false, Boot.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** The folder the app's jar is in (the zip's folder). */
    static File home() {
        try {
            return new File(Boot.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile();
        } catch (Exception e) {
            return new File(".");
        }
    }
}
