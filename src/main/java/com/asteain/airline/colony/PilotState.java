package com.asteain.airline.colony;

import com.minecolonies.api.entity.ai.statemachine.states.IAIState;

/**
 * What the pilot is about, as its work shows it (without these states its name tag would say "work started" all the time): MineColonies
 * shows the state of a citizen's work in the citizen's name tag (with its development features on); ColonyPatch puts it
 * into words ("colonypatch.state." and the name). The pilot's walking is the same in every one of them.
 */
public enum PilotState implements IAIState {
    /** At the airfield (or at the place of another airfield), no flight under way. */
    PILOT_WAITING,
    /** Goods and fuel are being put into the aircraft. */
    PILOT_LOADING,
    /** Walking to the seat, or seated and waiting to leave. */
    PILOT_BOARDING,
    /** In the air. */
    PILOT_FLYING,
    /** Getting off the aircraft. */
    PILOT_DISEMBARKING,
    /** Goods are being taken out of the aircraft. */
    PILOT_UNLOADING;

    @Override
    public boolean isOkayToEat() {
        return this == PILOT_WAITING;
    }
}
