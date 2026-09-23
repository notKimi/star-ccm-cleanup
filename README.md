# STAR-CCM+ Simulation Cleanup

A Windows batch utility for cleaning selected Simcenter STAR-CCM+ simulation files. It provides a file selection window and runs a STAR-CCM+ macro on each chosen `.sim` file. This is an independent community project by **Luchen**; it is not affiliated with or endorsed by Siemens.

## What it does

1. Clear generated mesh from selected `.sim` files.
2. Clear saved solution data from selected `.sim` files.
3. Clear both solution and generated mesh.
4. Delete selected `.sim~` backup files.

For actions 1–3, the macro saves over each selected `.sim` file. It keeps the simulation setup, including geometry, mesh operations, physics, and boundary settings. **Delete matching `.sim~` after successful save** is checked by default: after STAR-CCM+ exits successfully and confirms the save, the tool deletes the matching backup in the same folder (for example, `case.sim~` for `case.sim`). This also removes a matching backup that was already present. Uncheck it if you want to keep that recovery copy. Action 4 permanently deletes only the backup files you tick, including backups left from earlier runs.

The window accepts multiple folders, with optional subfolder scanning. You can tick files individually, use **Check all** or **Invert selection**, or highlight several rows with Shift/Ctrl and choose **Check highlighted** or **Uncheck highlighted**. The file list can be filtered by last-modified age and by minimum/maximum size in decimal GB. The table shows modified time and size in GB.

**Concurrent STAR jobs** can be set from 1 to 4. Each job runs in a separate STAR-CCM+ process with its own log. The default is 1. More jobs may improve total throughput, but they also need enough RAM, disk throughput, and simultaneous license capacity. **Stop after active jobs** lets running jobs finish and skips files that have not started.

## Requirements

- Windows.
- A separately installed and licensed Simcenter STAR-CCM+.
- JDK 21 to build and run the window. The JDK bundled with the tested STAR-CCM+ release may be used.

The macro was tested with STAR-CCM+ 2602.0001 (build 21.02.008-R8). STAR-CCM+ API changes may require adapting it for other releases. No Siemens binaries or simulation files are included in this repository.

## Build and run

From PowerShell in the repository folder:

```powershell
.\build.ps1 -JavaHome 'C:\Path\To\JDK'
```

If `JAVA_HOME` is set or `javac.exe` is on `PATH`, the `-JavaHome` argument can be omitted. The script creates `dist/StarCleanupGui.jar` and copies the macro and launcher into `dist/`.

Copy `dist/local-env.example.bat` to `dist/local-env.bat`, then set these paths in your copy:

```bat
set "STAR_CCM_EXE=C:\Path\To\starccm+.bat"
set "STAR_CCM_JAVA=C:\Path\To\javaw.exe"
```

Double-click `dist/Start-StarCleanup.bat`. If `STAR_CCM_EXE` is unset, you can choose the STAR launcher in the window. If `STAR_CCM_JAVA` is unset, the launcher tries `JAVA_HOME` and then `javaw.exe` on `PATH`.

Choose an action, add folders, set filters if needed, tick the exact files, and click **Run checked files**. The confirmation shows whether matching `.sim~` backups will be deleted after successful saves. A failed STAR job leaves its matching backup alone. If saving succeeds but backup deletion fails, the window's progress log and status show a warning. Detailed STAR logs are written to `dist/logs/`.

## Safety and limitations

- Mesh cleanup applies to meshes generated within STAR-CCM+. If the selected simulation has no clearable generated mesh, the macro reports failure and does not save it.
- Do not run cleanup on a `.sim` file that is open in another STAR-CCM+ session.
- Start with one concurrent job for large simulations. Two or more STAR sessions can run out of memory or license capacity and may compete for disk I/O.
- `*.sim`, `*.sim~`, logs, local configuration, and build outputs are ignored by Git. Do not upload simulation files or unredacted STAR logs in issues; they may contain proprietary models, file paths, or license details.

## Project layout

- `src/StarCleanupGui.java` — the Swing window and batch job scheduler.
- `macro/StarCleanup.java` — the STAR-CCM+ cleanup macro, compiled by STAR-CCM+ when run.
- `build.ps1` — builds the GUI JAR and distribution folder.
- `Start-StarCleanup.bat` — launches the GUI.
- `tests/BackupCleanupSmoke.java` — checks backup deletion with a fake STAR process and temporary test files.

## License

Copyright (c) 2026 Luchen Wang. The code is available under the [MIT License](LICENSE).
