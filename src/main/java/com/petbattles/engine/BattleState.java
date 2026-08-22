package com.petbattles.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Full state of an in-progress battle: both teams, active pets, phase.
 */
public class BattleState
{
	public static final int PLAYER = 0;
	public static final int ENEMY = 1;

	public enum Phase
	{
		IN_PROGRESS,
		// The player's active pet fainted mid-turn and they must choose a replacement
		// from the bench before play resumes. The forced switch consumes the round.
		PLAYER_MUST_SWITCH,
		PLAYER_WON,
		ENEMY_WON,
		FLED
	}

	private final List<BattlePet> playerTeam;
	private final List<BattlePet> enemyTeam;
	private final int[] activeIndex = {0, 0};
	// Team slots that have ever been the active pet, per side — the pets that "fought".
	// The initial lead counts, plus anything sent in via switch or on a faint.
	private final Set<Integer>[] participated;
	private Phase phase = Phase.IN_PROGRESS;
	private int turnNumber;
	// Who wins a speed tie, or -1 for a coin flip (the default, used by trainer battles).
	// See {@link #setSpeedTieSide} for why PvP pins it.
	private int speedTieSide = -1;
	// Whether a fainted pet is replaced automatically on BOTH sides rather than the player being
	// prompted. See {@link #setAutoReplace}.
	private boolean autoReplace;
	// How the opposing side is named in battle text ("Enemy sends out …"). PvP swaps in the
	// opponent's display name.
	private String enemyLabel = "Enemy";

	@SuppressWarnings("unchecked")
	public BattleState(List<BattlePet> playerTeam, List<BattlePet> enemyTeam)
	{
		if (playerTeam.isEmpty() || enemyTeam.isEmpty())
		{
			throw new IllegalArgumentException("Both sides need at least one pet");
		}
		this.playerTeam = new ArrayList<>(playerTeam);
		this.enemyTeam = new ArrayList<>(enemyTeam);
		this.participated = new Set[]{new LinkedHashSet<>(), new LinkedHashSet<>()};
		// The opening lead on each side has already fought
		participated[PLAYER].add(activeIndex[PLAYER]);
		participated[ENEMY].add(activeIndex[ENEMY]);
	}

	public List<BattlePet> team(int side)
	{
		return side == PLAYER ? playerTeam : enemyTeam;
	}

	public BattlePet active(int side)
	{
		return team(side).get(activeIndex[side]);
	}

	public int activeIndex(int side)
	{
		return activeIndex[side];
	}

	public static int opponent(int side)
	{
		return side == PLAYER ? ENEMY : PLAYER;
	}

	public Phase getPhase()
	{
		return phase;
	}

	public void setPhase(Phase phase)
	{
		this.phase = phase;
	}

	/**
	 * True only for terminal phases. {@link Phase#PLAYER_MUST_SWITCH} is a mid-battle
	 * pause, not an ending, so it is not "over".
	 */
	public boolean isOver()
	{
		return phase == Phase.PLAYER_WON || phase == Phase.ENEMY_WON || phase == Phase.FLED;
	}

	/**
	 * Whether the battle is paused waiting for the player to send in a replacement pet.
	 */
	public boolean awaitingForcedSwitch()
	{
		return phase == Phase.PLAYER_MUST_SWITCH;
	}

	public int getTurnNumber()
	{
		return turnNumber;
	}

	/**
	 * Which side wins a speed tie, or -1 (the default) to flip a coin off the battle RNG.
	 */
	public int getSpeedTieSide()
	{
		return speedTieSide;
	}

	/**
	 * Pin speed ties to one side instead of flipping for them. Lockstep PvP needs this: the two
	 * clients hold mirrored states (each is its own PLAYER), so a shared coin flip would send the
	 * turn order opposite ways. Pinning it to the challenger's physical side resolves the tie to
	 * the same pet on both clients <em>and</em> draws nothing from the shared RNG stream, keeping
	 * every later roll in step.
	 */
	public void setSpeedTieSide(int side)
	{
		this.speedTieSide = side;
	}

	/**
	 * Whether a faint sends in the next living pet automatically on both sides.
	 */
	public boolean isAutoReplace()
	{
		return autoReplace;
	}

	/**
	 * Replace a fainted pet automatically on both sides (PvP) rather than pausing for the player to
	 * choose ({@link Phase#PLAYER_MUST_SWITCH}). A prompt would desync lockstep: the peer's client
	 * has to know which pet came in, and it isn't waiting on an answer. With this set, team order is
	 * the send-out order — voluntary switches on a normal turn still work, because those <em>are</em>
	 * exchanged.
	 */
	public void setAutoReplace(boolean autoReplace)
	{
		this.autoReplace = autoReplace;
	}

	/**
	 * How the opposing side is named in battle text; "Enemy" unless a PvP opponent renames it.
	 */
	public String getEnemyLabel()
	{
		return enemyLabel;
	}

	public void setEnemyLabel(String enemyLabel)
	{
		this.enemyLabel = enemyLabel == null || enemyLabel.isEmpty() ? "Enemy" : enemyLabel;
	}

	public void nextTurn()
	{
		turnNumber++;
	}

	public boolean allFainted(int side)
	{
		for (BattlePet pet : team(side))
		{
			if (!pet.isFainted())
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Set the active pet directly (validated by the engine's switch handling).
	 */
	public void setActive(int side, int index)
	{
		activeIndex[side] = index;
		participated[side].add(index);
	}

	/**
	 * Team slot of the next non-fainted pet on this side, or -1 if the whole team is down.
	 * Does not change the active pet.
	 */
	public int nextAliveIndex(int side)
	{
		List<BattlePet> team = team(side);
		for (int i = 0; i < team.size(); i++)
		{
			if (!team.get(i).isFainted())
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * Switch the active pet to the next non-fainted team member; returns false if none remain.
	 */
	public boolean sendNext(int side)
	{
		int next = nextAliveIndex(side);
		if (next < 0)
		{
			return false;
		}
		setActive(side, next);
		return true;
	}

	/**
	 * Whether the pet at the given team slot has ever been the active pet this battle
	 * (the lead, or sent in via switch / on a faint). Drives "only pets that fought earn XP".
	 */
	public boolean hasFought(int side, int teamIndex)
	{
		return participated[side].contains(teamIndex);
	}
}
