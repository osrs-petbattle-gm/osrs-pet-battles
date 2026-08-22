package com.petbattles.pvp;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * One player's committed action for one turn. Both sides send theirs, then both run the same
 * {@code BattleEngine.resolveTurn} over the same seeded RNG and arrive at the same outcome — there
 * is no authoritative host.
 *
 * <p>{@code turn} keys the action to a specific round so an early arrival can be held until this
 * client has finished animating the previous one, and {@code checksum} is the sender's view of the
 * pre-turn state: a mismatch means the two simulations have drifted, and the battle is abandoned
 * rather than allowed to show two different fights.
 */
public class PetBattleTurn extends PartyMemberMessage
{
	/** Action kinds, mirroring {@code BattleAction.Kind} without dragging the engine onto the wire. */
	public static final int KIND_MOVE = 0;
	public static final int KIND_SWITCH = 1;
	public static final int KIND_FLEE = 2;

	private long target;
	private int turn;
	private int kind;
	private int index;
	private long checksum;

	public PetBattleTurn()
	{
	}

	public PetBattleTurn(long target, int turn, int kind, int index, long checksum)
	{
		this.target = target;
		this.turn = turn;
		this.kind = kind;
		this.index = index;
		this.checksum = checksum;
	}

	public long getTarget()
	{
		return target;
	}

	/** Which round this action belongs to (the state's turn number before it resolves). */
	public int getTurn()
	{
		return turn;
	}

	public int getKind()
	{
		return kind;
	}

	/** Move index for {@link #KIND_MOVE}, team index for {@link #KIND_SWITCH}, unused for a forfeit. */
	public int getIndex()
	{
		return index;
	}

	/** The sender's checksum of the shared state before this turn resolves. */
	public long getChecksum()
	{
		return checksum;
	}
}
