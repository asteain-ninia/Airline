package com.asteain.shiptest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Small tools for the stage 2 lab: block states by name, reflection on the aircraft mods' block entities, ship building. */
final class LabKit {
    private LabKit() {}

    /** A block state from its id and "property=value" pairs, e.g. state("create:shaft", "axis=x"). */
    static BlockState state(String id, String... properties) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        if (block == Blocks.AIR && !id.equals("minecraft:air")) throw new AssertionError("no block " + id);
        BlockState state = block.defaultBlockState();
        for (String pair : properties) {
            int eq = pair.indexOf('=');
            String name = pair.substring(0, eq), value = pair.substring(eq + 1);
            Property<?> property = block.getStateDefinition().getProperty(name);
            if (property == null) throw new AssertionError(id + " has no property " + name + " (has " + state.getValues().keySet() + ")");
            state = with(state, property, value);
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.getValue(value);
        if (parsed.isEmpty()) throw new AssertionError("property " + property.getName() + " has no value " + value + " (has " + property.getPossibleValues() + ")");
        return state.setValue(property, parsed.get());
    }

    /** Calls a public or declared method by name; the first method with that name and number of arguments wins. */
    static Object call(Object target, String name, Object... args) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                try {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                } catch (IllegalArgumentException wrongTypes) {
                    // another overload may fit
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("calling " + name + " on " + target.getClass().getSimpleName() + ": " + e.getCause(), e);
                }
            }
        }
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            try {
                return m.invoke(target, args);
            } catch (IllegalArgumentException wrongTypes) {
                // next
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("calling " + name + " on " + target.getClass().getSimpleName() + ": " + e.getCause(), e);
            }
        }
        throw new AssertionError("no method " + name + "/" + args.length + " on " + target.getClass().getName());
    }

    static Field field(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException next) {
                // look in the superclass
            }
        }
        throw new AssertionError("no field " + name + " on " + target.getClass().getName());
    }

    static Object get(Object target, String name) {
        try { return field(target, name).get(target); } catch (IllegalAccessException e) { throw new AssertionError(e); }
    }

    static void set(Object target, String name, Object value) {
        try { field(target, name).set(target, value); } catch (IllegalAccessException e) { throw new AssertionError(e); }
    }

    static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    /** A ship under construction: blocks by position relative to an origin, placed into the world and then assembled. */
    static final class Plan {
        final ServerLevel level;
        final BlockPos origin;
        final Map<BlockPos, BlockState> parts = new LinkedHashMap<>();
        Object ship;

        Plan(ServerLevel level, BlockPos origin) {
            this.level = level;
            this.origin = origin;
        }

        Plan put(int x, int y, int z, BlockState state) {
            parts.put(new BlockPos(x, y, z), state);
            return this;
        }

        Plan box(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) put(x, y, z, state);
            return this;
        }

        /** Places everything (flag 2: no neighbour updates while half built), then lets the neighbours see each other, then assembles. */
        Object assemble() throws Exception {
            List<BlockPos> world = new ArrayList<>();
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (Map.Entry<BlockPos, BlockState> e : parts.entrySet()) {
                BlockPos p = origin.offset(e.getKey());
                level.setBlock(p, e.getValue(), 3);
                world.add(p);
                x0 = Math.min(x0, p.getX()); y0 = Math.min(y0, p.getY()); z0 = Math.min(z0, p.getZ());
                x1 = Math.max(x1, p.getX()); y1 = Math.max(y1, p.getY()); z1 = Math.max(z1, p.getZ());
            }
            BlockPos min = new BlockPos(x0, y0, z0);
            ship = Ships.assemble(level, world, min, new BlockPos(x1, y1, z1), min);
            return ship;
        }

        /** The position inside the ship of the block that was planned at this relative position. Only valid right after assemble(). */
        BlockPos inShip(int x, int y, int z) throws Exception {
            return BlockPos.containing(Ships.toPlot(ship, Vec3.atCenterOf(origin.offset(x, y, z))));
        }

        BlockEntity entity(int x, int y, int z) throws Exception {
            return level.getBlockEntity(inShip(x, y, z));
        }
    }
}
