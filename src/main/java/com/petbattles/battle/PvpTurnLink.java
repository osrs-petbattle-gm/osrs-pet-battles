package com.petbattles.battle;

import com.petbattles.engine.BattleAction;
import com.petbattles.engine.BattleState;

/**
 * The session's end of a player-vs-player battle: how a committed action reaches the opponent, and
 * how the session reports that the fight is over. Implemented by the party-messaging layer
 * ({@code com.petbattles.pvp.PvpService}) and held only for the duration of one battle, which keeps
 * {@link BattleSession} free of any networking.
 *
 * <p>Every method is called on the client thread.
 */
public interface PvpTurnLink
{
	/**
	 * Publish this client's action for {@code turn}, together with its view of the pre-turn state,
	 * and wait for the opponent's. The session resolves the turn once both are in hand.
	 */
	void sendAction(int turn, BattleAction action, long checksum);

	/**
	 * This client's fingerprint of the shared state, for comparison against the opponent's. Lives on
	 * the link because only the party layer knows which of the two players is the challenger, and
	 * the fingerprint has to be folded in that order to match on both sides.
	 */
	long checksum(BattleState state);

	/** The battle reached a natural end (someone won, or someone forfeited). */
	void onBattleFinished(BattleState.Phase phase);

	/**
	 * This client is abandoning the battle — the player closed it, the opponent went quiet, or the
	 * two simulations disagreed. The opponent is told so their side doesn't sit waiting.
	 */
	void onAbandoned(String reason);
}
