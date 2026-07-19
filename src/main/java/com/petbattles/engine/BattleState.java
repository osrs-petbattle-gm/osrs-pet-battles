package com.petbattles.engine;

import java.util.ArrayList;
import java.util.List;

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
		PLAYER_WON,
		ENEMY_WON,
		FLED
	}

	private final List<BattlePet> playerTeam;
	private final List<BattlePet> enemyTeam;
	private final int[] activeIndex = {0, 0};
	private Phase phase = Phase.IN_PROGRESS;
	private int turnNumber;

	public BattleState(List<BattlePet> playerTeam, List<BattlePet> enemyTeam)
	{
		if (playerTeam.isEmpty() || enemyTeam.isEmpty())
		{
			throw new IllegalArgumentException("Both sides need at least one pet");
		}
		this.playerTeam = new ArrayList<>(playerTeam);
		this.enemyTeam = new ArrayList<>(enemyTeam);
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

	public boolean isOver()
	{
		return phase != Phase.IN_PROGRESS;
	}

	public int getTurnNumber()
	{
		return turnNumber;
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
	}

	/**
	 * Switch the active pet to the next non-fainted team member; returns false if none remain.
	 */
	public boolean sendNext(int side)
	{
		List<BattlePet> team = team(side);
		for (int i = 0; i < team.size(); i++)
		{
			if (!team.get(i).isFainted())
			{
				activeIndex[side] = i;
				return true;
			}
		}
		return false;
	}
}
