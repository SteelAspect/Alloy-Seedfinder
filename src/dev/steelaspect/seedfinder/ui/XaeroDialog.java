package dev.steelaspect.seedfinder.ui;

import dev.steelaspect.seedfinder.engine.Result;
import dev.steelaspect.seedfinder.export.Exporters;
import dev.steelaspect.seedfinder.store.Settings;

import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** "Add to Xaero's minimap": pick the minimap folder and server, then write the waypoints. */
final class XaeroDialog {
    private XaeroDialog() {}

    static void show(SeedFinderApp app, List<Result> results, BiConsumer<String, Color> status) {
        Settings s = app.settings();
        List<Path> dirs = new ArrayList<>(Exporters.findXaeroDirs());
        if (!s.xaeroDir.isBlank() && !dirs.contains(Path.of(s.xaeroDir))) dirs.add(0, Path.of(s.xaeroDir));
        if (dirs.isEmpty()) {
            status.accept("No Xaero's minimap folder found. Set it in Settings.", Theme.DANGER);
            return;
        }
        JDialog d = app.dialog("Add to Xaero's minimap");
        JPanel body = Ui.clear(new BorderLayout(0, 14));
        Theme.pad(body, 20, 22, 18, 22);
        JComboBox<Path> dir = Ui.combo(dirs.toArray(Path[]::new));
        if (!s.xaeroDir.isBlank()) dir.setSelectedItem(Path.of(s.xaeroDir));
        JComboBox<String> server = Ui.combo(new String[0]);
        Runnable fill = () -> {
            server.removeAllItems();
            for (String n : Exporters.xaeroServers((Path) dir.getSelectedItem())) server.addItem(n);
            if (!s.xaeroServer.isBlank()) server.setSelectedItem(s.xaeroServer);
        };
        fill.run();
        dir.addActionListener(e -> fill.run());
        dir.setPreferredSize(new Dimension(520, 34));
        JPanel fields = Ui.clear(new GridLayout(0, 1, 0, 12));
        fields.add(Ui.labelled("Minimap folder", dir));
        fields.add(Ui.labelled("Server or world", server));
        body.add(Ui.title("Add " + results.size() + " waypoints"), BorderLayout.NORTH);
        body.add(fields, BorderLayout.CENTER);
        JPanel notes = Ui.clear(new GridLayout(0, 1, 0, 4));
        notes.add(Ui.muted("They go in a \"" + Exporters.XAERO_SET + "\" set, replacing what that set held. The old file is backed up first."));
        if (Exporters.minecraftRunning()) {
            JLabel warn = Ui.muted("Minecraft is running. Close it first, or Xaero may overwrite these when it saves.");
            warn.setForeground(Theme.WARNING);
            notes.add(warn);
        }
        Ui.FlatButton cancel = Ui.button("Cancel", null, Ui.Kind.SECONDARY);
        Ui.FlatButton add = Ui.button("Add waypoints", "pin", Ui.Kind.PRIMARY);
        cancel.addActionListener(e -> d.dispose());
        add.addActionListener(e -> {
            if (server.getSelectedItem() == null) return;
            try {
                Path dirPath = (Path) dir.getSelectedItem();
                Path file = Exporters.toXaero(dirPath, (String) server.getSelectedItem(), results);
                s.xaeroDir = dirPath.toString();
                s.xaeroServer = (String) server.getSelectedItem();
                s.save();
                status.accept("Added " + results.size() + " waypoints to " + file.getParent().getParent().getFileName(), Theme.SUCCESS);
                d.dispose();
            } catch (Exception ex) {
                status.accept("Couldn't add waypoints: " + ex.getMessage(), Theme.DANGER);
            }
        });
        JPanel bottom = Ui.clear(new BorderLayout(0, 16));
        bottom.add(notes, BorderLayout.NORTH);
        JPanel buttons = Ui.clear(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(cancel);
        buttons.add(add);
        bottom.add(buttons, BorderLayout.SOUTH);
        body.add(bottom, BorderLayout.SOUTH);
        d.setContentPane(wrap(body));
        d.pack();
        d.setLocationRelativeTo(app.frame());
        d.setVisible(true);
    }

    static JPanel wrap(JPanel body) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Theme.PANEL);
        p.add(body);
        return p;
    }
}
