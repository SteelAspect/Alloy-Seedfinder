package dev.steelaspect.seedfinder.ui;

import dev.steelaspect.seedfinder.engine.Fmt;
import dev.steelaspect.seedfinder.engine.Result;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** The list of results: rank, name, coordinates, size, the result's own details, and the Verify outcome. */
public final class ResultsTable extends JTable {
    private final Model model = new Model();
    private IntConsumer onSelect = i -> {};

    public ResultsTable() {
        setModel(model);
        setRowHeight(34);
        setShowGrid(false);
        setIntercellSpacing(new Dimension(0, 0));
        setFillsViewportHeight(true);
        setBackground(Theme.PANEL);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setAutoCreateRowSorter(false);
        setAutoResizeMode(AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        JTableHeader h = getTableHeader();
        h.setReorderingAllowed(false);
        h.setPreferredSize(new Dimension(10, 32));
        h.setDefaultRenderer((t, v, sel, foc, row, col) -> {
            JLabel l = new JLabel(v == null ? "" : v.toString());
            l.setFont(Theme.FONT_SMALL);
            l.setForeground(Theme.TEXT3);
            l.setOpaque(true);
            l.setBackground(Theme.PANEL);
            l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                    BorderFactory.createEmptyBorder(0, 12, 0, 8)));
            return l;
        });
        h.setBackground(Theme.PANEL);
        setDefaultRenderer(Object.class, new Cell());
        getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onSelect.accept(getSelectedRow());
        });
    }

    public void onSelect(IntConsumer listener) {
        onSelect = listener;
    }

    public void setResults(List<Result> results) {
        model.set(results);
        sizeColumns();
    }

    public List<Result> results() {
        return model.rows;
    }

    public Result selected() {
        int i = getSelectedRow();
        return i >= 0 && i < model.rows.size() ? model.rows.get(i) : null;
    }

    public void refresh() {
        model.fireTableRowsUpdated(0, Math.max(0, model.rows.size() - 1));
    }

    public void selectRow(int i) {
        if (i >= 0 && i < getRowCount()) {
            setRowSelectionInterval(i, i);
            scrollRectToVisible(getCellRect(i, 0, true));
        }
    }

    /** Each column as wide as its widest value (or header); the list scrolls sideways if they don't all fit. */
    private void sizeColumns() {
        for (int c = 0; c < model.getColumnCount(); c++) {
            String name = model.getColumnName(c);
            java.awt.FontMetrics fm = getFontMetrics(name.equals("Coords") ? Theme.FONT_MONO : Theme.FONT);
            int w = getFontMetrics(Theme.FONT_SMALL).stringWidth(name);
            for (int r = 0; r < Math.min(model.getRowCount(), 200); r++)
                w = Math.max(w, fm.stringWidth(String.valueOf(model.getValueAt(r, c))));
            if (name.equals("Check")) w = Math.max(w, fm.stringWidth("✓ verified"));
            w += 26;
            getColumnModel().getColumn(c).setPreferredWidth(w);
            getColumnModel().getColumn(c).setMinWidth(Math.min(w, name.equals("#") || name.equals("Coords") ? w : 60));
        }
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return getParent() == null || getPreferredSize().width <= getParent().getWidth();
    }

    private final class Cell extends DefaultTableCellRenderer {
        private boolean stripe;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(table, value, selected, false, row, col);
            String name = model.getColumnName(col);
            stripe = row % 2 == 1;
            setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 8));
            setHorizontalAlignment(SwingConstants.LEFT);
            setFont(name.equals("Coords") ? Theme.FONT_MONO : Theme.FONT);
            setForeground(name.equals("#") ? Theme.TEXT3 : Theme.TEXT);
            setBackground(selected ? new Color(0x4A3125) : stripe ? new Color(0x23272F) : Theme.PANEL);
            if (name.equals("Check")) {
                String v = value == null ? "" : value.toString();
                setForeground(v.equals("ok") ? Theme.SUCCESS : v.isEmpty() ? Theme.TEXT3 : v.equals("checking") ? Theme.WARNING : Theme.DANGER);
                setText(v.equals("ok") ? "✓ verified" : v.isEmpty() ? "–" : v);
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
        }
    }

    private static final class Model extends AbstractTableModel {
        List<Result> rows = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        boolean nameColumn;

        void set(List<Result> list) {
            rows = new ArrayList<>(list);
            keys = new ArrayList<>();
            for (Result r : rows) for (String k : r.details.keySet()) if (!keys.contains(k) && !k.equals("Biome")) keys.add(k);
            nameColumn = rows.stream().map(r -> r.name).distinct().count() > 1;
            fireTableStructureChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 4 + (nameColumn ? 1 : 0) + keys.size();
        }

        @Override
        public String getColumnName(int c) {
            if (c == 0) return "#";
            if (nameColumn && c == 1) return "Name";
            int k = c - (nameColumn ? 2 : 1);
            if (k == 0) return "Coords";
            if (k == 1) return "Size";
            if (k - 2 < keys.size()) return keys.get(k - 2);
            return "Check";
        }

        @Override
        public Object getValueAt(int row, int c) {
            Result r = rows.get(row);
            String col = getColumnName(c);
            return switch (col) {
                case "#" -> row + 1;
                case "Name" -> r.name.contains("+") ? "Mesa" : r.name.contains("_") || Character.isLowerCase(r.name.charAt(0)) ? Fmt.biome(r.name) : r.name;
                case "Coords" -> r.coords();
                case "Size" -> r.size;
                case "Check" -> r.verified == null ? "" : r.verified;
                default -> r.details.getOrDefault(col, "");
            };
        }
    }
}
