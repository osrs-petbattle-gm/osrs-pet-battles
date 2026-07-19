package com.petbattles.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * In-battle mutable state for one pet: current HP, stat stages, status condition.
 */
public class BattlePet
{
	public enum Status
	{
		NONE,
		BURN,
		FREEZE,
		POISON,
		STUN
	}

	public static final int MAX_STAGE = 4;
	public static final int MIN_STAGE = -4;

	private final SpeciesDef species;
	private final String displayName;
	private final int level;
	private final List<MoveDef> moves;
	private final int maxHp;
	private int currentHp;
	private int atkStage;
	private int defStage;
	private int spdStage;
	private Status status = Status.NONE;
	private int statusTurns;

	public BattlePet(SpeciesDef species, String displayName, int level, List<MoveDef> moves)
	{
		this(species, displayName, level, moves, null);
	}

	/**
	 * @param startingHp carried-over HP from a previous battle; null starts at full
	 */
	public BattlePet(SpeciesDef species, String displayName, int level, List<MoveDef> moves, Integer startingHp)
	{
		this.species = species;
		this.displayName = displayName;
		this.level = level;
		this.moves = new ArrayList<>(moves);
		this.maxHp = Leveling.hpAtLevel(species.getBase().getHp(), level);
		this.currentHp = startingHp == null ? maxHp : Math.max(0, Math.min(maxHp, startingHp));
	}

	public SpeciesDef getSpecies()
	{
		return species;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public int getLevel()
	{
		return level;
	}

	public List<MoveDef> getMoves()
	{
		return moves;
	}

	public int getMaxHp()
	{
		return maxHp;
	}

	public int getCurrentHp()
	{
		return currentHp;
	}

	public boolean isFainted()
	{
		return currentHp <= 0;
	}

	public void damage(int amount)
	{
		currentHp = Math.max(0, currentHp - Math.max(0, amount));
	}

	public int heal(int amount)
	{
		int before = currentHp;
		currentHp = Math.min(maxHp, currentHp + Math.max(0, amount));
		return currentHp - before;
	}

	public Status getStatus()
	{
		return status;
	}

	public int getStatusTurns()
	{
		return statusTurns;
	}

	public boolean applyStatus(Status newStatus, int turns)
	{
		if (status != Status.NONE || newStatus == Status.NONE)
		{
			return false;
		}
		status = newStatus;
		statusTurns = turns;
		return true;
	}

	public void tickStatus()
	{
		if (status != Status.NONE && statusTurns > 0)
		{
			statusTurns--;
			if (statusTurns <= 0)
			{
				status = Status.NONE;
			}
		}
	}

	public void cureStatus()
	{
		status = Status.NONE;
		statusTurns = 0;
	}

	public int getAtkStage()
	{
		return atkStage;
	}

	public int getDefStage()
	{
		return defStage;
	}

	public int getSpdStage()
	{
		return spdStage;
	}

	/**
	 * Adjust a stat stage; returns the actual delta applied after clamping.
	 */
	public int changeStage(MoveEffect effect, int delta)
	{
		switch (effect)
		{
			case ATK_UP:
			case ATK_DOWN:
			{
				int before = atkStage;
				atkStage = clampStage(atkStage + delta);
				return atkStage - before;
			}
			case DEF_UP:
			case DEF_DOWN:
			{
				int before = defStage;
				defStage = clampStage(defStage + delta);
				return defStage - before;
			}
			case SPD_UP:
			case SPD_DOWN:
			{
				int before = spdStage;
				spdStage = clampStage(spdStage + delta);
				return spdStage - before;
			}
			default:
				return 0;
		}
	}

	private static int clampStage(int stage)
	{
		return Math.max(MIN_STAGE, Math.min(MAX_STAGE, stage));
	}

	private static double stageMultiplier(int stage)
	{
		return stage >= 0 ? (2.0 + stage) / 2.0 : 2.0 / (2.0 - stage);
	}

	public int effectiveAtk()
	{
		double v = Leveling.statAtLevel(species.getBase().getAtk(), level) * stageMultiplier(atkStage);
		if (status == Status.BURN)
		{
			v *= 0.5;
		}
		return Math.max(1, (int) Math.round(v));
	}

	public int effectiveDef()
	{
		double v = Leveling.statAtLevel(species.getBase().getDef(), level) * stageMultiplier(defStage);
		return Math.max(1, (int) Math.round(v));
	}

	public int effectiveSpd()
	{
		double v = Leveling.statAtLevel(species.getBase().getSpd(), level) * stageMultiplier(spdStage);
		if (status == Status.STUN)
		{
			v *= 0.5;
		}
		return Math.max(1, (int) Math.round(v));
	}

	public boolean hasStab(PetType moveType)
	{
		return species.getTypes().contains(moveType);
	}
}
