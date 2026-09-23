// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Luchen Wang
// Author: Luchen Wang

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

public final class StarCleanupGui extends JFrame {
    private static final double BYTES_PER_GB = 1_000_000_000.0;
    private static final DateTimeFormatter MODIFIED_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private enum CleanupAction {
        MESH("1 - Clear generated mesh from .sim", "mesh", ".sim"),
        SOLUTION("2 - Clear solution from .sim", "solution", ".sim"),
        BOTH("3 - Clear mesh and solution from .sim", "both", ".sim"),
        BACKUPS("4 - Delete selected .sim~ backup files", "backup", ".sim~");

        final String label;
        final String mode;
        final String suffix;

        CleanupAction(String label, String mode, String suffix) {
            this.label = label;
            this.mode = mode;
            this.suffix = suffix;
        }

        @Override public String toString() { return label; }
    }

    private enum AgeFilter {
        ANY("Any time", 0, false),
        OLDER_ONE("Older than 1 year", 1, true),
        OLDER_TWO("Older than 2 years", 2, true),
        OLDER_THREE("Older than 3 years", 3, true),
        OLDER_FIVE("Older than 5 years", 5, true),
        WITHIN_ONE("Within the past 1 year", 1, false),
        WITHIN_TWO("Within the past 2 years", 2, false);

        final String label;
        final int years;
        final boolean older;

        AgeFilter(String label, int years, boolean older) {
            this.label = label;
            this.years = years;
            this.older = older;
        }

        boolean matches(Instant modified, Instant cutoff) {
            return years == 0 || (older ? modified.isBefore(cutoff) : !modified.isBefore(cutoff));
        }

        @Override public String toString() { return label; }
    }

    private static final class FileRow {
        final Path path;
        final String name;
        final String folder;
        final String modified;
        final Double sizeGb;
        boolean checked;

        FileRow(Path path, long sizeBytes, FileTime modifiedTime, boolean checked) {
            this.path = path;
            this.name = path.getFileName().toString();
            this.folder = path.getParent().toString();
            this.modified = MODIFIED_FORMAT.format(modifiedTime.toInstant().atZone(ZoneId.systemDefault()));
            this.sizeGb = sizeBytes / BYTES_PER_GB;
            this.checked = checked;
        }
    }

    private static final class FileTableModel extends AbstractTableModel {
        private final String[] columns = {"Use", "File", "Folder", "Modified", "Size (GB)"};
        private final List<FileRow> rows = new ArrayList<>();

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : column == 4 ? Double.class : String.class;
        }
        @Override public boolean isCellEditable(int row, int column) { return column == 0; }

        @Override public Object getValueAt(int row, int column) {
            FileRow file = rows.get(row);
            return switch (column) {
                case 0 -> file.checked;
                case 1 -> file.name;
                case 2 -> file.folder;
                case 3 -> file.modified;
                default -> file.sizeGb;
            };
        }

        @Override public void setValueAt(Object value, int row, int column) {
            if (column == 0) {
                rows.get(row).checked = Boolean.TRUE.equals(value);
                fireTableCellUpdated(row, column);
            }
        }

        void replace(List<FileRow> newRows) {
            rows.clear();
            rows.addAll(newRows);
            fireTableDataChanged();
        }

        void checkAll(boolean checked) {
            for (FileRow row : rows) row.checked = checked;
            fireTableDataChanged();
        }

        void invertSelection() {
            for (FileRow row : rows) row.checked = !row.checked;
            fireTableDataChanged();
        }

        void setCheckedAtRows(int[] rowIndices, boolean checked) {
            for (int rowIndex : rowIndices) rows.get(rowIndex).checked = checked;
            fireTableDataChanged();
        }

        List<Path> checkedPaths() {
            List<Path> selected = new ArrayList<>();
            for (FileRow row : rows) if (row.checked) selected.add(row.path);
            return selected;
        }
    }

    private static final class RunSummary {
        int success;
        int failure;
        int backupWarnings;
    }

    private static final class JobResult {
        final boolean success;
        final boolean backupWarning;
        final String message;

        JobResult(boolean success, boolean backupWarning, String message) {
            this.success = success;
            this.backupWarning = backupWarning;
            this.message = message;
        }
    }

    private final Path toolDirectory;
    private final Path macroPath;
    private final Path logDirectory;
    private final javax.swing.DefaultListModel<Path> folderModel = new javax.swing.DefaultListModel<>();
    private final JList<Path> folderList = new JList<>(folderModel);
    private final JCheckBox includeSubfolders = new JCheckBox("Include subfolders");
    private final JCheckBox deleteSavedBackup = new JCheckBox("Delete matching .sim~ after successful save", true);
    private final JComboBox<CleanupAction> actionBox = new JComboBox<>(CleanupAction.values());
    private final JComboBox<Integer> concurrentJobs = new JComboBox<>(new Integer[] {1, 2, 3, 4});
    private final JComboBox<AgeFilter> ageFilter = new JComboBox<>(AgeFilter.values());
    private final JTextField minSizeGb = new JTextField(7);
    private final JTextField maxSizeGb = new JTextField(7);
    private final JButton applyFilters = new JButton("Apply filters");
    private final JTextField starPath = new JTextField();
    private final FileTableModel fileModel = new FileTableModel();
    private final JTable fileTable = new JTable(fileModel);
    private final JTextArea logArea = new JTextArea();
    private final JLabel fileCount = new JLabel("Files: 0");
    private final JLabel status = new JLabel("Ready");
    private final JButton addFolder = new JButton("Add folder...");
    private final JButton removeFolder = new JButton("Remove folder");
    private final JButton browseStar = new JButton("Browse...");
    private final JButton checkAll = new JButton("Check all");
    private final JButton invertSelection = new JButton("Invert selection");
    private final JButton checkNone = new JButton("Check none");
    private final JButton checkHighlighted = new JButton("Check highlighted");
    private final JButton uncheckHighlighted = new JButton("Uncheck highlighted");
    private final JButton refresh = new JButton("Refresh files");
    private final JButton run = new JButton("Run checked files");
    private final JButton stop = new JButton("Stop after active jobs");
    private boolean running;
    private boolean scanning;
    private volatile boolean stopAfterCurrent;
    private long scanVersion;

    private StarCleanupGui(Path toolDirectory, String initialStarPath) {
        super("STAR-CCM+ simulation cleanup");
        this.toolDirectory = toolDirectory;
        this.macroPath = toolDirectory.resolve("StarCleanup.java");
        this.logDirectory = toolDirectory.resolve("logs");
        starPath.setText(initialStarPath);
        buildWindow();
        wireActions();
        refreshFiles();
    }

    private void buildWindow() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(840, 650));
        setSize(1060, 760);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        JPanel top = new JPanel(new BorderLayout(8, 8));
        JPanel folderPanel = new JPanel(new BorderLayout(6, 6));
        folderPanel.add(new JLabel("Folders (add multiple folders, then tick individual files below):"), BorderLayout.NORTH);
        folderList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JScrollPane folderScroll = new JScrollPane(folderList);
        folderScroll.setPreferredSize(new Dimension(700, 90));
        folderPanel.add(folderScroll, BorderLayout.CENTER);
        JPanel folderButtons = new JPanel(new GridLayout(2, 1, 0, 6));
        folderButtons.add(addFolder);
        folderButtons.add(removeFolder);
        folderPanel.add(folderButtons, BorderLayout.EAST);
        top.add(folderPanel, BorderLayout.NORTH);

        JPanel options = new JPanel(new GridLayout(4, 1, 0, 5));
        JPanel scopeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        scopeRow.add(includeSubfolders);
        scopeRow.add(new JLabel("Concurrent STAR jobs:"));
        concurrentJobs.setToolTipText("Separate STAR sessions. Start with 1 for large files; higher values need more RAM and licenses.");
        scopeRow.add(concurrentJobs);
        options.add(scopeRow);
        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        actionRow.add(new JLabel("Action:"));
        actionBox.setPreferredSize(new Dimension(365, 26));
        actionRow.add(actionBox);
        actionRow.add(deleteSavedBackup);
        options.add(actionRow);
        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        filterRow.add(new JLabel("Last modified:"));
        filterRow.add(ageFilter);
        filterRow.add(new JLabel("Min size (GB):"));
        filterRow.add(minSizeGb);
        filterRow.add(new JLabel("Max size (GB):"));
        filterRow.add(maxSizeGb);
        filterRow.add(applyFilters);
        options.add(filterRow);
        JPanel starRow = new JPanel(new BorderLayout(5, 0));
        starRow.add(new JLabel("STAR-CCM+ launcher:"), BorderLayout.WEST);
        starRow.add(starPath, BorderLayout.CENTER);
        starRow.add(browseStar, BorderLayout.EAST);
        options.add(starRow);
        top.add(options, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);

        JPanel filePanel = new JPanel(new BorderLayout(5, 5));
        filePanel.add(fileCount, BorderLayout.NORTH);
        fileTable.setFillsViewportHeight(true);
        fileTable.setAutoCreateRowSorter(true);
        fileTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        fileTable.setRowSelectionAllowed(true);
        fileTable.setColumnSelectionAllowed(false);
        fileTable.getColumnModel().getColumn(0).setPreferredWidth(45);
        fileTable.getColumnModel().getColumn(1).setPreferredWidth(220);
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(430);
        fileTable.getColumnModel().getColumn(3).setPreferredWidth(135);
        fileTable.getColumnModel().getColumn(4).setPreferredWidth(100);
        fileTable.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                if (value instanceof Number) {
                    double gb = ((Number) value).doubleValue();
                    setText(String.format(Locale.ROOT, gb > 0 && gb < 0.001 ? "%.6f" : "%.3f", gb));
                } else {
                    setText("");
                }
            }
        });
        filePanel.add(new JScrollPane(fileTable), BorderLayout.CENTER);
        JPanel fileButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        fileButtons.add(checkAll);
        fileButtons.add(invertSelection);
        fileButtons.add(checkNone);
        fileButtons.add(checkHighlighted);
        fileButtons.add(uncheckHighlighted);
        fileButtons.add(refresh);
        filePanel.add(fileButtons, BorderLayout.SOUTH);
        root.add(filePanel, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(5, 5));
        bottom.add(new JLabel("Progress and results (detailed STAR logs are saved beside this tool):"), BorderLayout.NORTH);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setPreferredSize(new Dimension(700, 130));
        bottom.add(logScroll, BorderLayout.CENTER);
        JPanel bottomButtons = new JPanel(new BorderLayout());
        bottomButtons.add(status, BorderLayout.WEST);
        JPanel runButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        runButtons.add(stop);
        runButtons.add(run);
        bottomButtons.add(runButtons, BorderLayout.EAST);
        bottom.add(bottomButtons, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);
    }

    private void wireActions() {
        addFolder.addActionListener(event -> addFolders());
        removeFolder.addActionListener(event -> {
            for (Path path : folderList.getSelectedValuesList()) folderModel.removeElement(path);
            refreshFiles();
        });
        includeSubfolders.addActionListener(event -> refreshFiles());
        actionBox.addActionListener(event -> refreshFiles());
        ageFilter.addActionListener(event -> refreshFiles());
        minSizeGb.addActionListener(event -> refreshFiles());
        maxSizeGb.addActionListener(event -> refreshFiles());
        applyFilters.addActionListener(event -> refreshFiles());
        browseStar.addActionListener(event -> chooseStarExecutable());
        checkAll.addActionListener(event -> fileModel.checkAll(true));
        invertSelection.addActionListener(event -> fileModel.invertSelection());
        checkNone.addActionListener(event -> fileModel.checkAll(false));
        checkHighlighted.addActionListener(event -> setHighlightedChecked(true));
        uncheckHighlighted.addActionListener(event -> setHighlightedChecked(false));
        refresh.addActionListener(event -> refreshFiles());
        run.addActionListener(event -> runChecked());
        stop.addActionListener(event -> {
            stopAfterCurrent = true;
            stop.setEnabled(false);
            appendLog("Stop requested. Active STAR jobs will finish; remaining files will be skipped.");
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (running) {
                    JOptionPane.showMessageDialog(StarCleanupGui.this,
                        "Please wait for the current STAR-CCM+ batch to finish before closing this window.");
                } else {
                    dispose();
                }
            }
        });
    }

    private void addFolders() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose one or more folders containing .sim files");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        if (!folderModel.isEmpty()) chooser.setCurrentDirectory(folderModel.lastElement().toFile());
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        java.io.File[] selected = chooser.getSelectedFiles();
        if (selected.length == 0 && chooser.getSelectedFile() != null) {
            selected = new java.io.File[] {chooser.getSelectedFile()};
        }
        for (java.io.File file : selected) {
            Path path = file.toPath().toAbsolutePath().normalize();
            if (!folderModel.contains(path)) folderModel.addElement(path);
        }
        refreshFiles();
    }

    private void chooseStarExecutable() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose starccm+.bat or starccm+.exe");
        String current = starPath.getText().trim();
        if (!current.isEmpty() && Files.exists(Paths.get(current))) chooser.setSelectedFile(Paths.get(current).toFile());
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            starPath.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private CleanupAction selectedAction() { return (CleanupAction) actionBox.getSelectedItem(); }

    private static Double parseSizeGb(String text) {
        if (text.trim().isEmpty()) return null;
        double value = Double.parseDouble(text.trim());
        if (!Double.isFinite(value) || value < 0) throw new NumberFormatException("Size must be nonnegative");
        return value;
    }

    private void refreshFiles() {
        refreshFiles("Ready");
    }

    private void refreshFiles(String completedStatus) {
        if (running) return;
        final long version = ++scanVersion;
        final Double minimum;
        final Double maximum;
        try {
            minimum = parseSizeGb(minSizeGb.getText());
            maximum = parseSizeGb(maxSizeGb.getText());
            if (minimum != null && maximum != null && minimum > maximum) {
                throw new NumberFormatException("Minimum exceeds maximum");
            }
        } catch (NumberFormatException ex) {
            scanning = false;
            fileModel.replace(new ArrayList<>());
            fileCount.setText("Files: 0 (invalid size filter)");
            status.setText("Use nonnegative GB values; minimum must not exceed maximum.");
            updateButtons();
            return;
        }
        final List<Path> folders = new ArrayList<>();
        for (int index = 0; index < folderModel.size(); index++) folders.add(folderModel.get(index));
        final boolean recursive = includeSubfolders.isSelected();
        final String suffix = selectedAction().suffix;
        final AgeFilter selectedAge = (AgeFilter) ageFilter.getSelectedItem();
        final Instant cutoff = selectedAge.years == 0 ? null
            : ZonedDateTime.now().minusYears(selectedAge.years).toInstant();
        final Set<Path> checkedBefore = new HashSet<>(fileModel.checkedPaths());
        scanning = true;
        updateButtons();
        status.setText("Scanning folders...");

        new SwingWorker<List<FileRow>, Void>() {
            @Override protected List<FileRow> doInBackground() throws Exception {
                Set<String> found = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (Path folder : folders) {
                    if (!Files.isDirectory(folder)) continue;
                    int depth = recursive ? Integer.MAX_VALUE : 1;
                    try (Stream<Path> stream = Files.walk(folder, depth)) {
                        stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                            .forEach(path -> found.add(path.toAbsolutePath().normalize().toString()));
                    }
                }
                List<FileRow> rows = new ArrayList<>();
                for (String pathText : found) {
                    Path path = Paths.get(pathText);
                    try {
                        long bytes = Files.size(path);
                        FileTime modified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
                        double sizeGb = bytes / BYTES_PER_GB;
                        if (minimum != null && sizeGb < minimum) continue;
                        if (maximum != null && sizeGb > maximum) continue;
                        if (!selectedAge.matches(modified.toInstant(), cutoff)) continue;
                        rows.add(new FileRow(path, bytes, modified, checkedBefore.contains(path)));
                    } catch (IOException ex) {
                        // Files can disappear or become inaccessible during a folder scan.
                    }
                }
                return rows;
            }

            @Override protected void done() {
                if (version != scanVersion) return;
                scanning = false;
                try {
                    List<FileRow> rows = get();
                    fileModel.replace(rows);
                    fileCount.setText("Files: " + rows.size() + " matching; Shift/Ctrl-click rows for group checking");
                    status.setText(completedStatus);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    status.setText("Scan interrupted");
                } catch (ExecutionException ex) {
                    fileModel.replace(new ArrayList<>());
                    fileCount.setText("Files: 0 (scan failed)");
                    status.setText("Scan failed");
                    appendLog("[FAILED] Folder scan: " + ex.getCause().getMessage());
                }
                updateButtons();
            }
        }.execute();
    }

    private void updateButtons() {
        boolean enabled = !running && !scanning;
        addFolder.setEnabled(enabled);
        removeFolder.setEnabled(enabled);
        folderList.setEnabled(enabled);
        includeSubfolders.setEnabled(enabled);
        actionBox.setEnabled(enabled);
        deleteSavedBackup.setEnabled(enabled && selectedAction() != CleanupAction.BACKUPS);
        concurrentJobs.setEnabled(enabled);
        ageFilter.setEnabled(enabled);
        minSizeGb.setEnabled(enabled);
        maxSizeGb.setEnabled(enabled);
        applyFilters.setEnabled(enabled);
        starPath.setEnabled(enabled);
        browseStar.setEnabled(enabled);
        fileTable.setEnabled(enabled);
        checkAll.setEnabled(enabled);
        invertSelection.setEnabled(enabled);
        checkNone.setEnabled(enabled);
        checkHighlighted.setEnabled(enabled);
        uncheckHighlighted.setEnabled(enabled);
        refresh.setEnabled(enabled);
        run.setEnabled(enabled);
        stop.setEnabled(running && !stopAfterCurrent);
    }

    private void appendLog(String message) {
        logArea.append(message + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void setHighlightedChecked(boolean checked) {
        int[] viewRows = fileTable.getSelectedRows();
        if (viewRows.length == 0) {
            JOptionPane.showMessageDialog(this,
                "Highlight one or more file rows with Shift or Ctrl, then use this button.");
            return;
        }
        int[] modelRows = new int[viewRows.length];
        for (int index = 0; index < viewRows.length; index++) {
            modelRows[index] = fileTable.convertRowIndexToModel(viewRows[index]);
        }
        fileModel.setCheckedAtRows(modelRows, checked);
    }

    private void runChecked() {
        List<Path> checked = fileModel.checkedPaths();
        if (checked.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Tick at least one file.");
            return;
        }
        CleanupAction action = selectedAction();
        if (action == CleanupAction.BACKUPS) {
            deleteBackups(checked);
            return;
        }
        Path executable;
        try { executable = Paths.get(starPath.getText().trim()); }
        catch (Exception ex) { executable = null; }
        if (executable == null || !Files.isRegularFile(executable) || !Files.isRegularFile(macroPath)) {
            JOptionPane.showMessageDialog(this,
                "Choose an existing starccm+.bat or starccm+.exe launcher and keep StarCleanup.java beside this tool.");
            return;
        }
        int decision = JOptionPane.showConfirmDialog(this,
            "Run action " + (action.ordinal() + 1) + " on " + checked.size()
                + " checked .sim file(s) with up to " + concurrentJobs.getSelectedItem()
                + " concurrent STAR job(s) and save over them?\n"
                + ((Integer) concurrentJobs.getSelectedItem() > 1
                    ? "Parallel jobs use separate STAR sessions, memory, and license capacity.\n" : "")
                + (deleteSavedBackup.isSelected()
                    ? "After each successful save, permanently delete its matching .sim~ backup if present."
                    : "Keep any .sim~ backups created by STAR-CCM+."),
            "Confirm simulation cleanup", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (decision != JOptionPane.YES_OPTION) return;
        runStarBatch(checked, action, executable, (Integer) concurrentJobs.getSelectedItem(),
            deleteSavedBackup.isSelected());
    }

    private void deleteBackups(List<Path> checked) {
        int decision = JOptionPane.showConfirmDialog(this,
            "Permanently delete the " + checked.size() + " checked .sim~ backup file(s)?",
            "Confirm backup deletion", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (decision != JOptionPane.YES_OPTION) return;
        for (Path path : checked) {
            try {
                if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sim~")
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("File is missing or is not a regular .sim~ backup");
                }
                Files.delete(path);
                appendLog("[DELETED] " + path);
            } catch (IOException ex) {
                appendLog("[FAILED] " + path + " - " + ex.getMessage());
            }
        }
        refreshFiles();
    }

    private JobResult runStarJob(Path simFile, CleanupAction action, Path executable,
                                 boolean deleteBackupAfterSave) {
        String safeName = simFile.getFileName().toString().replaceAll("[^a-zA-Z0-9_.-]", "_");
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        Path logFile = logDirectory.resolve(stamp + "_" + safeName + "_"
            + UUID.randomUUID().toString().substring(0, 8) + ".log");
        long started = System.nanoTime();
        try {
            if (!simFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sim")
                || !Files.isRegularFile(simFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("The selected .sim file is missing or has changed");
            }
            ProcessBuilder builder = new ProcessBuilder(
                executable.toString(), "-batch", macroPath.toString(), simFile.toString());
            builder.directory(simFile.getParent().toFile());
            builder.environment().put("STAR_CLEAN_MODE", action.mode);
            builder.environment().put("STAR_CLEAN_TARGET", simFile.toString());
            builder.redirectErrorStream(true);
            builder.redirectOutput(logFile.toFile());
            int exit = builder.start().waitFor();
            if (exit != 0 || !containsSuccessMarker(logFile)) {
                throw new IOException("STAR-CCM+ exit code " + exit + "; " + failureReason(logFile));
            }
            if (!Files.isRegularFile(simFile, LinkOption.NOFOLLOW_LINKS) || Files.size(simFile) == 0) {
                throw new IOException("Saved .sim file is missing or empty; matching backup was kept");
            }
            String backupMessage = "";
            boolean backupWarning = false;
            if (deleteBackupAfterSave) {
                Path backup = simFile.resolveSibling(simFile.getFileName().toString() + "~");
                try {
                    if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                        if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
                            throw new IOException("Matching backup is not a regular file");
                        }
                        Files.delete(backup);
                        backupMessage = System.lineSeparator() + "[BACKUP DELETED] " + backup;
                    }
                } catch (IOException ex) {
                    backupWarning = true;
                    backupMessage = System.lineSeparator() + "[BACKUP DELETE FAILED] " + backup
                        + " - " + ex.getMessage();
                }
            }
            return new JobResult(true, backupWarning, "[OK] " + simFile + " ("
                + String.format(Locale.ROOT, "%.1f", (System.nanoTime() - started) / 1e9)
                + "s; log: " + logFile + ")" + backupMessage);
        } catch (Exception ex) {
            return new JobResult(false, false, "[FAILED] " + simFile + " - " + ex.getMessage()
                + " (log: " + logFile + ")");
        }
    }

    private void runStarBatch(List<Path> checked, CleanupAction action, Path executable,
                              int concurrency, boolean deleteBackupAfterSave) {
        stopAfterCurrent = false;
        running = true;
        updateButtons();
        status.setText("Running STAR-CCM+ batch jobs...");
        appendLog("Queued " + checked.size() + " simulation(s); up to " + concurrency
            + " concurrent STAR job(s). Logs: " + logDirectory);
        final long batchStarted = System.nanoTime();

        new SwingWorker<RunSummary, String>() {
            @Override protected RunSummary doInBackground() throws Exception {
                Files.createDirectories(logDirectory);
                RunSummary summary = new RunSummary();
                ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, checked.size()));
                CompletionService<JobResult> completed = new ExecutorCompletionService<>(pool);
                Map<Future<JobResult>, Path> active = new HashMap<>();
                int next = 0;
                try {
                    while (active.size() < concurrency && next < checked.size() && !stopAfterCurrent) {
                        Path file = checked.get(next++);
                        publish("[START] " + file);
                        active.put(completed.submit(
                            () -> runStarJob(file, action, executable, deleteBackupAfterSave)), file);
                    }
                    while (!active.isEmpty()) {
                        Future<JobResult> finished = completed.take();
                        Path file = active.remove(finished);
                        try {
                            JobResult result = finished.get();
                            if (result.success) summary.success++;
                            else summary.failure++;
                            if (result.backupWarning) summary.backupWarnings++;
                            publish(result.message);
                        } catch (ExecutionException ex) {
                            summary.failure++;
                            publish("[FAILED] " + file + " - " + ex.getCause());
                        }
                        if (next < checked.size() && !stopAfterCurrent) {
                            Path upcoming = checked.get(next++);
                            publish("[START] " + upcoming);
                            active.put(completed.submit(
                                () -> runStarJob(upcoming, action, executable, deleteBackupAfterSave)), upcoming);
                        }
                    }
                    if (stopAfterCurrent && next < checked.size()) {
                        publish("Stopped before starting " + (checked.size() - next) + " remaining file(s).");
                    }
                } finally {
                    pool.shutdown();
                }
                return summary;
            }

            @Override protected void process(List<String> messages) {
                for (String message : messages) appendLog(message);
            }

            @Override protected void done() {
                running = false;
                String completedStatus;
                try {
                    RunSummary summary = get();
                    appendLog("Finished in "
                        + String.format(Locale.ROOT, "%.1f", (System.nanoTime() - batchStarted) / 1e9)
                        + "s: " + summary.success + " succeeded, " + summary.failure + " failed"
                        + (summary.backupWarnings > 0
                            ? ", " + summary.backupWarnings + " backup deletion warning(s)." : "."));
                    completedStatus = summary.failure > 0 ? "Finished with failures"
                        : summary.backupWarnings > 0 ? "Finished with backup warnings" : "Complete";
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    completedStatus = "Interrupted";
                    appendLog("[FAILED] Batch interrupted");
                } catch (ExecutionException ex) {
                    completedStatus = "Batch failed";
                    appendLog("[FAILED] " + ex.getCause().getMessage());
                }
                updateButtons();
                refreshFiles(completedStatus);
            }
        }.execute();
    }

    private static boolean containsSuccessMarker(Path logFile) throws IOException {
        if (!Files.isRegularFile(logFile)) return false;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(Files.newInputStream(logFile), StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("STAR_CLEANUP_SUCCESS")) return true;
            }
        }
        return false;
    }

    private static String failureReason(Path logFile) throws IOException {
        if (Files.size(logFile) == 0) return "STAR produced no output; see log";
        String reason = null;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(Files.newInputStream(logFile), StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.contains("Exception:") || trimmed.startsWith("Error:")
                    || trimmed.startsWith("ERROR:") || trimmed.startsWith("Error ")) {
                    reason = trimmed;
                }
            }
        }
        return reason == null ? "success marker missing or failed; see log" : reason;
    }

    public static void main(String[] args) {
        Path toolDirectory;
        try {
            toolDirectory = Paths.get(StarCleanupGui.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).getParent();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "Could not locate the cleanup tool: " + ex.getMessage());
            return;
        }
        String initialStarPath = args.length > 0 ? args[0] : "";
        SwingUtilities.invokeLater(() -> new StarCleanupGui(toolDirectory, initialStarPath).setVisible(true));
    }
}
