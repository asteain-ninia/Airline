package com.asteain.shiptest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

/** Sable access by reflection only, so the test mod compiles and loads without Sable. */
final class Ships {
    private Ships() {}

    /** Fills the box with planks and assembles it into a sub-level; returns the ServerSubLevel. */
    static Object assemblePlanks(ServerLevel level, BlockPos min, BlockPos max, BlockPos anchor) throws Exception {
        List<BlockPos> blocks = new ArrayList<>();
        for (BlockPos raw : BlockPos.betweenClosed(min, max)) {
            BlockPos p = raw.immutable();
            blocks.add(p);
            level.setBlockAndUpdate(p, Blocks.OAK_PLANKS.defaultBlockState());
        }
        Class<?> box = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3i");
        Class<?> boxIf = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3ic");
        Object bounds = box.getConstructor(BlockPos.class, BlockPos.class).newInstance(min, max);
        return Class.forName("dev.ryanhcode.sable.api.SubLevelAssemblyHelper")
                .getMethod("assembleBlocks", ServerLevel.class, BlockPos.class, Iterable.class, boxIf)
                .invoke(null, level, anchor, blocks, bounds);
    }

    /** Assembles already-placed blocks (anchor becomes the plot centre). */
    static Object assemble(ServerLevel level, List<BlockPos> blocks, BlockPos min, BlockPos max, BlockPos anchor) throws Exception {
        Class<?> box = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3i");
        Class<?> boxIf = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3ic");
        Object bounds = box.getConstructor(BlockPos.class, BlockPos.class).newInstance(min, max);
        return Class.forName("dev.ryanhcode.sable.api.SubLevelAssemblyHelper")
                .getMethod("assembleBlocks", ServerLevel.class, BlockPos.class, Iterable.class, boxIf)
                .invoke(null, level, anchor, blocks, bounds);
    }

    /** logicalPose().transformPosition(plot) : plot-space point to world. */
    static Vec3 toWorld(Object sub, Vec3 plot) throws Exception {
        return (Vec3) Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc").getMethod("transformPosition", Vec3.class).invoke(pose(sub), plot);
    }

    static Vec3 toPlot(Object sub, Vec3 world) throws Exception {
        return (Vec3) Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc").getMethod("transformPositionInverse", Vec3.class).invoke(pose(sub), world);
    }

    static org.joml.Quaterniond orientation(Object sub) throws Exception {
        Object q = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc").getMethod("orientation").invoke(pose(sub));
        return new org.joml.Quaterniond((org.joml.Quaterniondc) q);
    }

    /** RigidBodyHandle.teleport(position, orientation). */
    static void teleport(Object sub, Vector3d pos, org.joml.Quaterniond q) throws Exception {
        Class<?> handleType = Class.forName("dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle");
        Class<?> serverSub = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel");
        Object handle = handleType.getMethod("of", serverSub).invoke(null, sub);
        handleType.getMethod("teleport", Class.forName("org.joml.Vector3dc"), Class.forName("org.joml.Quaterniondc")).invoke(handle, pos, q);
    }

    static Vec3 angularVelocity(Object sub) throws Exception {
        Class<?> handleType = Class.forName("dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle");
        Class<?> serverSub = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel");
        Object handle = handleType.getMethod("of", serverSub).invoke(null, sub);
        Object v = handleType.getMethod("getAngularVelocity").invoke(handle);
        Class<?> vc = Class.forName("org.joml.Vector3dc");
        return new Vec3((double) vc.getMethod("x").invoke(v), (double) vc.getMethod("y").invoke(v), (double) vc.getMethod("z").invoke(v));
    }

    static Object pose(Object sub) throws Exception {
        return Class.forName("dev.ryanhcode.sable.companion.SubLevelAccess").getMethod("logicalPose").invoke(sub);
    }

    /** World-space position of the sub-level's pose. */
    static Vec3 position(Object sub) throws Exception {
        Object pose = pose(sub);
        Vector3d p = (Vector3d) Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc").getMethod("position").invoke(pose);
        return new Vec3(p.x, p.y, p.z);
    }

    /** Linear velocity from the Rapier body (RigidBodyHandle.of(ServerSubLevel).getLinearVelocity()). */
    static Vec3 velocity(Object sub) throws Exception {
        Class<?> handleType = Class.forName("dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle");
        Class<?> serverSub = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel");
        Object handle = handleType.getMethod("of", serverSub).invoke(null, sub);
        Object v = handleType.getMethod("getLinearVelocity").invoke(handle);
        Class<?> vc = Class.forName("org.joml.Vector3dc");
        return new Vec3((double) vc.getMethod("x").invoke(v), (double) vc.getMethod("y").invoke(v), (double) vc.getMethod("z").invoke(v));
    }

    /** RigidBodyHandle.of(sub).applyLinearImpulse(impulse): moves a stopped, weightless ship. */
    static void impulse(Object sub, double x, double y, double z) throws Exception {
        Class<?> handleType = Class.forName("dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle");
        Class<?> serverSub = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel");
        Object handle = handleType.getMethod("of", serverSub).invoke(null, sub);
        handleType.getMethod("applyLinearImpulse", Class.forName("org.joml.Vector3dc")).invoke(handle, new Vector3d(x, y, z));
    }

    /** Cancels the body's linear and angular velocity (RigidBodyHandle.addLinearAndAngularVelocity with the negated current values). */
    static void zeroVelocity(Object sub) throws Exception {
        Class<?> handleType = Class.forName("dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle");
        Class<?> serverSub = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel");
        Object handle = handleType.getMethod("of", serverSub).invoke(null, sub);
        Class<?> vc = Class.forName("org.joml.Vector3dc");
        Object lin = handleType.getMethod("getLinearVelocity").invoke(handle);
        Object ang = handleType.getMethod("getAngularVelocity").invoke(handle);
        Vector3d nl = new Vector3d((double) vc.getMethod("x").invoke(lin), (double) vc.getMethod("y").invoke(lin), (double) vc.getMethod("z").invoke(lin)).negate();
        Vector3d na = new Vector3d((double) vc.getMethod("x").invoke(ang), (double) vc.getMethod("y").invoke(ang), (double) vc.getMethod("z").invoke(ang)).negate();
        handleType.getMethod("addLinearAndAngularVelocity", vc, vc).invoke(handle, nl, na);
    }

    /** The sub-level's id (SubLevelAccess.getUniqueId()). */
    static java.util.UUID id(Object sub) throws Exception {
        return (java.util.UUID) Class.forName("dev.ryanhcode.sable.companion.SubLevelAccess").getMethod("getUniqueId").invoke(sub);
    }

    static boolean isRemoved(Object sub) throws Exception {
        return (boolean) Class.forName("dev.ryanhcode.sable.sublevel.SubLevel").getMethod("isRemoved").invoke(sub);
    }

    /** Removes every sub-level within radius of centre (radius < 0: all of them); returns how many were removed. Test worlds only. */
    static int removeNear(ServerLevel level, Vec3 centre, double radius) throws Throwable {
        Class<?> containerType = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
        Class<?> holderType = Class.forName("dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder");
        Object container = holderType.getMethod("sable$getPlotContainer").invoke(level);
        // getMethod would enumerate client-only overloads and fail on a dedicated server; resolve exactly this descriptor.
        java.util.List<?> all = new ArrayList<>((java.util.List<?>) MethodHandles.publicLookup()
                .findVirtual(containerType, "getAllSubLevels", MethodType.methodType(java.util.List.class)).invoke(container));
        int n = 0;
        for (Object sub : all) {
            if (isRemoved(sub)) continue;
            if (radius >= 0 && position(sub).distanceTo(centre) > radius) continue;
            remove(level, sub);
            n++;
        }
        return n;
    }

    static void remove(ServerLevel level, Object sub) throws Throwable {
        Class<?> containerType = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
        Class<?> holderType = Class.forName("dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder");
        Object container = holderType.getMethod("sable$getPlotContainer").invoke(level);
        Class<?> reasonType = Class.forName("dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason");
        Object reason = reasonType.getField("REMOVED").get(null);
        MethodHandles.publicLookup().findVirtual(containerType, "removeSubLevel",
                MethodType.methodType(void.class, Class.forName("dev.ryanhcode.sable.sublevel.SubLevel"), reasonType))
                .invoke(container, sub, reason);
    }
}
