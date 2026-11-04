package com.asteain.shiptest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Edge walks on ships of chosen shapes (-Dshiptest.edgeShapes=s5,s2,s1,l,ring) at chosen headings (-Dshiptest.edgeYaws=0,10,...).
 * -Dshiptest.edgeLanded=true: real gravity, free ships landed on an isolated pad.
 */
final class EdgeSuite {
    private EdgeSuite() {}

    static Script build(MinecraftServer server) {
        Script s = new Script();
        boolean landed = Boolean.getBoolean("shiptest.edgeLanded");
        String[] shapes = System.getProperty("shiptest.edgeShapes", "s5").split(",");
        String[] yawText = System.getProperty("shiptest.edgeYaws", "0,10,20,30,40,44,45,46,60").split(",");
        s.label = (landed ? "landed" : "held") + " edge walks, shapes " + String.join("+", shapes) + ", yaws " + String.join(",", yawText);
        ServerLevel level = server.overworld();
        BlockPos origin = Rig.site(level, 0);
        Rig rig = new Rig(server, origin);
        rig.addColony(s, "CPS edge", 12, 8);
        int slot = 1;
        for (String sh : shapes) {
            EdgeLab.Shape shape = EdgeLab.SHAPES.get(sh.trim());
            if (shape == null) throw new AssertionError("unknown shape " + sh);
            for (String y : yawText) EdgeLab.add(s, rig, origin.offset(0, 0, 70 * slot++), Double.parseDouble(y.trim()), shape, landed, true);
        }
        return s;
    }
}
