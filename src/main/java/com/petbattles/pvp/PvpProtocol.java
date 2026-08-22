package com.petbattles.pvp;

import com.petbattles.engine.BattlePet;
import com.petbattles.engine.BattleState;
import java.util.List;

/**
 * Constants shared by both ends of a pet-battle exchange over the RuneLite party.
 *
 * <p>Every message is broadcast to the whole party (that is all the party transport offers), so each
 * one names its intended recipient in {@code target} and both ends drop anything not addressed to
 * them. Nothing here is authoritative: the peer is another copy of this plugin and is not trusted —
 * see {@link PvpRosterCodec} for what is validated before a peer's team is allowed on the field.
 */
public final class PvpProtocol
{
	/**
	 * Wire-format version. Bump on any change to a message's fields or to how the two clients agree
	 * a battle (seeding, turn keying, the rules the engine runs under). A challenge whose version
	 * doesn't match is refused with a "different version" note rather than risking a silent desync
	 * between two plugin builds.
	 */
	public static final int VERSION = 1;

	/**
	 * Game ticks to wait for a peer's reply / roster before giving up on a pending challenge (~30s).
	 * Once the battle is running the wait is the session's to manage, not this layer's.
	 */
	public static final int NEGOTIATE_TIMEOUT_TICKS = 50;

	private PvpProtocol()
	{
	}

	/**
	 * The battle seed, from the challenger's half and the accepter's. Both halves go over the wire
	 * before either side knows the other's, so neither player can pick a seed that suits them; the
	 * mix is order-sensitive (challenger first) so both clients derive the identical value.
	 */
	public static long mixSeed(long challengerHalf, long accepterHalf)
	{
		// splitmix64-style avalanche, so two similar halves don't produce two similar streams.
		long z = challengerHalf * 0x9E3779B97F4A7C15L + accepterHalf;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/**
	 * A fingerprint of the shared battle state, identical on both clients whenever their simulations
	 * agree. The two clients hold mirrored states — each is its own {@code PLAYER} — so the teams are
	 * folded in challenger-first rather than player-first, which is the same physical order on both
	 * sides. Sent with each turn action; a mismatch aborts the battle instead of letting the two
	 * screens tell different stories.
	 */
	public static long checksum(BattleState state, boolean localIsChallenger)
	{
		int challengerSide = localIsChallenger ? BattleState.PLAYER : BattleState.ENEMY;
		int accepterSide = BattleState.opponent(challengerSide);
		long h = 1125899906842597L;
		h = h * 31 + state.getTurnNumber();
		h = hashSide(h, state, challengerSide);
		h = hashSide(h, state, accepterSide);
		return h;
	}

	private static long hashSide(long h, BattleState state, int side)
	{
		h = h * 31 + state.activeIndex(side);
		List<BattlePet> team = state.team(side);
		for (BattlePet pet : team)
		{
			h = h * 31 + pet.getLevel();
			h = h * 31 + pet.getCurrentHp();
			h = h * 31 + pet.getMaxHp();
			h = h * 31 + pet.getStatus().ordinal();
			h = h * 31 + pet.getStatusTurns();
			h = h * 31 + pet.getAtkStage();
			h = h * 31 + pet.getDefStage();
			h = h * 31 + pet.getSpdStage();
		}
		return h;
	}
}
