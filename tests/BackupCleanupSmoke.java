// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Luchen Wang
// Author: Luchen Wang

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

public final class BackupCleanupSmoke {
    public static final class FakeStar {
        public static void main(String[] args) throws Exception {
            Path sim = Path.of(System.getenv("STAR_CLEAN_TARGET"));
            Path backup = Path.of(sim.toString() + "~");
            if (sim.getFileName().toString().startsWith("incomplete")) {
                System.out.println("The object data file \"" + sim + "\" is incomplete.  "
                    + "Apparently, the original save action did not terminate successfully.");
                System.exit(4);
            }
            if (!sim.getFileName().toString().startsWith("directory")) {
                Files.writeString(backup, "old simulation");
            }
            if (sim.getFileName().toString().startsWith("failed")) {
                System.out.println("java.lang.IllegalStateException: Fake cleanup failure");
                System.exit(3);
            }
            if (sim.getFileName().toString().startsWith("missing")) {
                Files.delete(sim);
            } else if (sim.getFileName().toString().startsWith("empty")) {
                Files.writeString(sim, "");
            } else {
                Files.writeString(sim, "cleaned simulation");
            }
            System.out.println("STAR_CLEANUP_SUCCESS");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object run(Method method, Object gui, Object action, Path sim,
                              Path launcher, boolean deleteBackup) throws Exception {
        return method.invoke(gui, sim, action, launcher, deleteBackup);
    }

    public static void main(String[] args) throws Exception {
        Path fixtureParent = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(fixtureParent);
        Path fixture = Files.createTempDirectory(fixtureParent, "backup-cleanup-");
        Files.createDirectories(fixture.resolve("logs"));
        Files.writeString(fixture.resolve("StarCleanup.java"), "// test macro");
        Path launcher = fixture.resolve("fake-star.bat");
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String classpath = System.getProperty("java.class.path");
        Files.writeString(launcher, "@echo off\r\n\"" + java + "\" -cp \"" + classpath
            + "\" BackupCleanupSmoke$FakeStar\r\nexit /b %errorlevel%\r\n");

        Class<?> actionType = Class.forName("StarCleanupGui$CleanupAction");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object action = Enum.valueOf((Class) actionType, "SOLUTION");
        Constructor<StarCleanupGui> constructor = StarCleanupGui.class
            .getDeclaredConstructor(Path.class, String.class);
        constructor.setAccessible(true);
        StarCleanupGui[] holder = new StarCleanupGui[1];
        SwingUtilities.invokeAndWait(() -> {
            try { holder[0] = constructor.newInstance(fixture, launcher.toString()); }
            catch (Exception ex) { throw new RuntimeException(ex); }
        });
        StarCleanupGui gui = holder[0];
        check(((JCheckBox) readField(gui, "deleteSavedBackup")).isSelected(),
            "automatic backup deletion should be selected by default");
        Method job = StarCleanupGui.class.getDeclaredMethod("runStarJob",
            Path.class, actionType, Path.class, boolean.class);
        job.setAccessible(true);
        try {
            Path success = fixture.resolve("success.sim");
            Path successBackup = Path.of(success + "~");
            Path unrelatedBackup = fixture.resolve("unrelated.sim~");
            Files.writeString(success, "original simulation");
            Files.writeString(successBackup, "previous backup");
            Files.writeString(unrelatedBackup, "unrelated backup");
            Object result = run(job, gui, action, success, launcher, true);
            check((boolean) readField(result, "success"), "successful job reported failure");
            check(!Files.exists(successBackup), "matching backup was not deleted");
            check(Files.exists(unrelatedBackup), "unrelated backup was deleted");
            check(Files.readString(success).equals("cleaned simulation"), "simulation was not saved");

            Path keep = fixture.resolve("keep.sim");
            Files.writeString(keep, "original simulation");
            result = run(job, gui, action, keep, launcher, false);
            check((boolean) readField(result, "success"), "opt-out job reported failure");
            check(Files.exists(Path.of(keep + "~")), "opt-out did not keep the backup");

            Path failed = fixture.resolve("failed.sim");
            Files.writeString(failed, "original simulation");
            result = run(job, gui, action, failed, launcher, true);
            check(!(boolean) readField(result, "success"), "failed STAR job reported success");
            check(Files.exists(Path.of(failed + "~")), "failed STAR job deleted its backup");
            check(((String) readField(result, "message")).contains("Fake cleanup failure"),
                "STAR error reason was not shown in the job result");

            Path incomplete = fixture.resolve("incomplete.sim");
            Files.writeString(incomplete, "unfinished simulation");
            result = run(job, gui, action, incomplete, launcher, true);
            check(!(boolean) readField(result, "success"), "incomplete simulation reported success");
            check(((String) readField(result, "message")).contains("previous save did not finish"),
                "incomplete simulation reason was not shown in the job result");
            check(Files.readString(incomplete).equals("unfinished simulation"),
                "incomplete simulation was changed");
            check(!Files.exists(Path.of(incomplete + "~")),
                "cleanup created a backup for an incomplete simulation");

            Path directory = fixture.resolve("directory.sim");
            Path directoryBackup = Path.of(directory + "~");
            Files.writeString(directory, "original simulation");
            Files.createDirectory(directoryBackup);
            result = run(job, gui, action, directory, launcher, true);
            check((boolean) readField(result, "success"), "saved job reported failure on backup warning");
            check((boolean) readField(result, "backupWarning"), "backup warning was not reported");
            check(Files.isDirectory(directoryBackup), "non-file backup was deleted");

            Path missing = fixture.resolve("missing.sim");
            Files.writeString(missing, "original simulation");
            result = run(job, gui, action, missing, launcher, true);
            check(!(boolean) readField(result, "success"), "missing saved simulation reported success");
            check(Files.exists(Path.of(missing + "~")), "backup was deleted with no saved simulation");

            Path empty = fixture.resolve("empty.sim");
            Files.writeString(empty, "original simulation");
            result = run(job, gui, action, empty, launcher, true);
            check(!(boolean) readField(result, "success"), "empty saved simulation reported success");
            check(Files.exists(Path.of(empty + "~")), "backup was deleted with an empty simulation");

            Method batch = StarCleanupGui.class.getDeclaredMethod("runStarBatch",
                List.class, actionType, Path.class, int.class, boolean.class);
            batch.setAccessible(true);
            SwingUtilities.invokeAndWait(() -> {
                try { batch.invoke(gui, List.of(directory), action, launcher, 1, true); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            });
            boolean warningStatus = false;
            for (int attempt = 0; attempt < 200; attempt++) {
                final boolean[] current = new boolean[1];
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        current[0] = !(boolean) readField(gui, "running")
                            && !(boolean) readField(gui, "scanning")
                            && ((JLabel) readField(gui, "status")).getText()
                                .equals("Finished with backup warnings");
                    } catch (Exception ex) { throw new RuntimeException(ex); }
                });
                if (current[0]) { warningStatus = true; break; }
                Thread.sleep(100);
            }
            check(warningStatus, "backup warning status did not persist after the file scan");
        } finally {
            SwingUtilities.invokeAndWait(gui::dispose);
        }
        System.out.println("BACKUP_CLEANUP_SMOKE_OK");
    }
}
