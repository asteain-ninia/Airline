package com.asteain.airline.boarding;

import com.asteain.airline.ShipConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Boarding: a citizen walks to a seat on a stopped Sable ship, sits down, is carried along while the ship moves, and
 * later stands up and walks off. This class is the entry point for other code (the test command, an air route mod).
 *
 * It is safe to call without Sable installed: every method then answers {@link Result#NO_SABLE} / {@link State#NONE},
 * and nothing that names a Sable class is loaded.
 *
 * A seat is named by the ship's id and a block position in the ship's own coordinates. Those coordinates are never
 * handed to MineColonies' walking code; the place to walk to is worked out from the ship's pose at that moment.
 *
 * While a citizen is boarding, seated or leaving, its own decisions (work, food, sleep) are held back, so whoever
 * called {@link #board} stays in charge until {@link #leave} has finished. Server thread only.
 */
public final class Boarding {
    /** Where a citizen is in the procedure. */
    public enum State {
        /** Not boarding at all. */
        NONE,
        /** Walking to the seat. */
        WALKING,
        /** Sitting on the ship. */
        SEATED,
        /** Standing again and walking to the place it was sent to. */
        LEAVING
    }

    public enum Result {
        OK("受け付けました。"),
        NO_SABLE("Sable が入っていないので、乗り込みは使えません。"),
        DISABLED("乗り込みは設定で無効になっています([boarding] enabled)。"),
        NOT_A_CITIZEN("MineColonies の村人ではありません。"),
        NO_SHIP("その場所に船のブロックがありません。"),
        SHIP_MOVING("船が動いています。止まってからやり直してください。"),
        ALREADY_SEATED("その村人は、すでに別の席に座っています。先に降ろしてください。"),
        NOT_ABOARD("その村人は乗り込みの途中でも、座ってもいません。"),
        WRONG_THREAD("サーバーのスレッドから呼んでください。"),
        FAILED("内部の失敗で受け付けられませんでした。サーバーのログを見てください。");

        /** A sentence for whoever gave the order (shown by the command). */
        public final String text;

        Result(String text) {
            this.text = text;
        }
    }

    private static Boolean sable;

    private Boarding() {}

    /** Whether boarding can work at all here (Sable is installed). */
    public static boolean available() {
        Boolean known = sable;
        if (known == null) sable = known = ModList.get().isLoaded("sable");
        return known;
    }

    @Nullable
    private static Result refusal(Entity citizen) {
        if (!available()) return Result.NO_SABLE;
        if (!(citizen.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return Result.WRONG_THREAD;
        if (!ShipConfig.BOARDING_ENABLED.get()) return Result.DISABLED;
        return null;
    }

    /**
     * Sends the citizen to a seat on a ship and has it sit down there.
     *
     * @param ship the ship's id (Sable's sub-level id)
     * @param seat the block to sit on, in the ship's own coordinates
     */
    public static Result board(Mob citizen, UUID ship, BlockPos seat) {
        final Result refusal = refusal(citizen);
        return refusal != null ? refusal : BoardingManager.board((ServerLevel) citizen.level(), citizen, ship, seat);
    }

    /**
     * Like {@link #board(Mob, UUID, BlockPos)}, for a seat pointed at in the world: the ship block that is at this world
     * position right now.
     */
    public static Result boardAt(Mob citizen, Vec3 seatInWorld) {
        final Result refusal = refusal(citizen);
        return refusal != null ? refusal : BoardingManager.boardAt((ServerLevel) citizen.level(), citizen, seatInWorld);
    }

    /**
     * Has the citizen stand up and walk to a place on the ground. Refused while the ship is moving.
     *
     * @param ground where to walk to (world coordinates); null to only stand up
     */
    public static Result leave(Mob citizen, @Nullable BlockPos ground) {
        final Result refusal = refusal(citizen);
        return refusal != null ? refusal : BoardingManager.leave((ServerLevel) citizen.level(), citizen, ground);
    }

    /**
     * Like {@link #leave(Mob, BlockPos)} without a place to walk to, for a citizen that nobody will ask again (the aircraft
     * whose pilot it is was taken away). While the ship moves the answer is {@link Result#SHIP_MOVING} as with leave, but
     * the order is kept: the citizen stands up on the deck as soon as the ship has stopped.
     */
    public static Result standUpWhenStopped(Mob citizen) {
        final Result refusal = refusal(citizen);
        return refusal != null ? refusal : BoardingManager.standUpWhenStopped((ServerLevel) citizen.level(), citizen);
    }

    public static State state(Entity citizen) {
        if (!available() || !(citizen.level() instanceof ServerLevel level)) return State.NONE;
        return BoardingManager.state(level, citizen);
    }

    /** How many internal failures boarding has had since the server started (for tests and diagnosis). */
    public static int failures() {
        return available() ? BoardingManager.failures() : 0;
    }
}
