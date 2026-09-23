// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Luchen Wang
// Author: Luchen Wang
// Tested with Simcenter STAR-CCM+ 2602.0001 (build 21.02.008-R8).
package macro;

import java.io.File;
import star.common.Simulation;
import star.common.StarMacro;
import star.meshing.MeshPipelineController;

public class StarCleanup extends StarMacro {
    @Override
    public void execute() {
        String mode = System.getenv("STAR_CLEAN_MODE");
        String target = System.getenv("STAR_CLEAN_TARGET");

        if (mode == null || target == null || !target.toLowerCase().endsWith(".sim")) {
            throw new IllegalArgumentException("STAR_CLEAN_MODE and STAR_CLEAN_TARGET must identify a .sim file.");
        }

        boolean clearMesh = "mesh".equals(mode) || "both".equals(mode);
        boolean clearSolution = "solution".equals(mode) || "both".equals(mode);
        if (!clearMesh && !clearSolution) {
            throw new IllegalArgumentException("Unknown STAR_CLEAN_MODE: " + mode);
        }

        Simulation sim = getActiveSimulation();
        MeshPipelineController mesh = null;
        int regionRepresentationsBefore = 0;
        boolean finalSurfaceBefore = false;
        if (clearMesh) {
            mesh = sim.get(MeshPipelineController.class);
            regionRepresentationsBefore = sim.getRepresentationManager().getRegionRepresentations().size();
            finalSurfaceBefore = sim.getRepresentationManager().hasFinalSurfaceRep() != null;
            if (regionRepresentationsBefore == 0 && !finalSurfaceBefore) {
                throw new IllegalStateException("No generated surface or volume mesh is present to clear.");
            }
            if (!mesh.allowMeshClearing()) {
                throw new IllegalStateException(
                    "STAR-CCM+ does not allow generated-mesh clearing in this simulation."
                );
            }
        }

        // Clear solution first when doing both, then remove generated meshes.
        if (clearSolution) {
            sim.getSolution().clearSolution();
        }
        if (clearMesh) {
            mesh.clearGeneratedMeshes();
            int regionRepresentationsAfter = sim.getRepresentationManager().getRegionRepresentations().size();
            boolean finalSurfaceAfter = sim.getRepresentationManager().hasFinalSurfaceRep() != null;
            if (regionRepresentationsAfter >= regionRepresentationsBefore
                && finalSurfaceAfter == finalSurfaceBefore) {
                throw new IllegalStateException(
                    "Mesh representations did not change after Clear Generated Meshes; simulation was not saved."
                );
            }
        }

        sim.saveState(new File(target).getAbsolutePath());
        sim.println("STAR_CLEANUP_SUCCESS");
    }
}
