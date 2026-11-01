package dev.steelaspect.seedfinder.ui;

import dev.steelaspect.seedfinder.store.History;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Every finished search, newest first. Open one to see its results and map again. */
final class HistoryPage extends JPanel {
    private static final String[] COLUMNS = {"When", "Tool", "Seed", "Results", "Time", "Top result"};
    private final SeedFinderApp app;
    private List<History.Entry> entries = new ArrayList<>();
    private final JTable table = new JTable();
    private final JLabel empty = Ui.muted("No saved searches yet. Every search you run shows up here.");
    private final AbstractTableModel model = new AbstractTableModel() {
        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLUMNS[c];
        }

        @Override
        public Object getValueAt(int row, int c) {
            History.Entry e = entries.get(row);
            return switch (c) {
                case 0 -> new SimpleDateFormat("d MMM, HH:mm").format(new Date(e.time));
                case 1 -> e.title;
                case 2 -> e.seedName;
                case 3 -> e.results.size();
                case 4 -> String.format(Locale.ROOT, "%.0f s", e.seconds);
                default -> e.results.isEmpty() ? "–" : e.results.get(0).coords() + "  ·  " + e.results.get(0).size;
            };
        }
    };

    HistoryPage(SeedFinderApp app) {
        super(new BorderLayout(0, 14));
        this.app = app;
        setOpaque(false);
        Theme.pad(this, 20, 22, 18, 22);
        JPanel head = Ui.clear(new BorderLayout(0, 3));
        head.add(Ui.title("Saved results"), BorderLayout.NORTH);
        head.add(Ui.muted("Every search is saved here. Open one to get its list and map back."), BorderLayout.SOUTH);
        add(head, BorderLayout.NORTH);

        table.setModel(model);
        table.setRowHeight(34);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setBackground(Theme.PANEL);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setPreferredSize(new Dimension(10, 32));
        table.getTableHeader().setDefaultRenderer((t, v, s, f, r, c) -> {
            JLabel l = new JLabel(String.valueOf(v));
            l.setFont(Theme.FONT_SMALL);
            l.setForeground(Theme.TEXT3);
            l.setOpaque(true);
            l.setBackground(Theme.PANEL);
            l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                    BorderFactory.createEmptyBorder(0, 12, 0, 8)));
            return l;
        });
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, false, row, col);
                setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 8));
                setForeground(col == 0 ? Theme.TEXT2 : Theme.TEXT);
                setFont(col == 5 ? Theme.FONT_MONO : Theme.FONT);
                setBackground(sel ? Theme.SELECTED : row % 2 == 1 ? Theme.PANEL2 : Theme.PANEL);
                return this;
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) open();
            }
        });
        int[] widths = {120, 120, 120, 70, 70, 300};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);

        Ui.Card card = new Ui.Card(new BorderLayout(0, 10));
        Theme.pad(card, 6, 6, 10, 6);
        card.add(Ui.scroll(table), BorderLayout.CENTER);
        Ui.FlatButton open = Ui.button("Open", "open", Ui.Kind.SECONDARY);
        Ui.FlatButton delete = Ui.button("Delete", "trash", Ui.Kind.SECONDARY);
        open.addActionListener(e -> open());
        delete.addActionListener(e -> delete());
        JPanel actions = Ui.clear(new FlowLayout(FlowLayout.LEFT, 8, 0));
        Theme.pad(actions, 0, 6, 0, 6);
        actions.add(open);
        actions.add(delete);
        actions.add(empty);
        card.add(actions, BorderLayout.SOUTH);
        add(card, BorderLayout.CENTER);
    }

    void reload() {
        entries = History.list();
        model.fireTableDataChanged();
        empty.setVisible(entries.isEmpty());
    }

    private void open() {
        int i = table.getSelectedRow();
        if (i >= 0) app.open(entries.get(i));
    }

    private void delete() {
        int i = table.getSelectedRow();
        if (i < 0) return;
        if (!app.confirm("Delete this saved search?", "It's removed from Saved results. This can't be undone.", "Delete")) return;
        History.delete(entries.get(i));
        reload();
    }
}
