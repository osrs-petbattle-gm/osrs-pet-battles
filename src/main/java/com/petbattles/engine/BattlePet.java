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
	// Active metamorphosis form id (null = base). Folds into every species lookup below so the
	// battler's sprite/orientation/types/base follow the form; falls through to base when unset.
	private final String variantId;
	private final String displayName;
	private int level;
	private final List<MoveDef> moves;
	private int maxHp;
	private int currentHp;
	private int atkStage;
	private int defStage;
	private int spdStage;
	private Status status = Status.NONE;
	private int statusTurns;

	public BattlePet(SpeciesDef species, String displayName, int level, List<MoveDef> moves)
	{
		this(species, displayName, level, moves, null, null);
	}

	/**
	 * @param startingHp carried-over HP from a previous battle; null starts at full
	 */
	public BattlePet(SpeciesDef species, String displayName, int level, List<MoveDef> moves, Integer startingHp)
	{
		this(species, displayName, level, moves, startingHp, null);
	}

	/**
	 * @param startingHp carried-over HP from a previous battle; null starts at full
	 * @param variantId  active metamorphosis form id, or null for the base form
	 */
	public BattlePet(SpeciesDef species, String displayName, int level, List<MoveDef> moves,
		Integer startingHp, String variantId)
	{
		this.species = species;
		this.variantId = variantId;
		this.displayName = displayName;
		this.level = level;
		this.moves = new ArrayList<>(moves);
		this.maxHp = Leveling.hpAtLevel(species.baseFor(variantId).getHp(), level);
		this.currentHp = startingHp == null ? maxHp : Math.max(0, Math.min(maxHp, startingHp));
	}

	public SpeciesDef getSpecies()
	{
		return species;
	}

	/**
	 * Active metamorphosis form id, or null for the base form.
	 */
	public String getVariantId()
	{
		return variantId;
	}

	/**
	 * Types for this battler, honouring the active variant (tier 2); base types otherwise.
	 */
	public List<PetType> getTypes()
	{
		return species.typesFor(variantId);
	}

	/**
	 * Whether the sprite art faces left, honouring the active variant's orientation override.
	 */
	public boolean isSpriteFacesLeft()
	{
		return species.spriteFacesLeftFor(variantId);
	}

	public String getDisplayName()
	{
		return displayName;
	}

	/**
	 * Sprite item id for this pet at its current level, honouring growth-stage evolution and the
	 * active metamorphosis form.
	 */
	public int getDisplayItemId()
	{
		return species.itemIdFor(variantId, level);
	}

	public int getLevel()
	{
		return level;
	}

	/**
	 * Grow this pet to a higher level mid-battle (from shared XP): raise max HP and add the
	 * gained HP to current, so leveling heals by the growth rather than leaving the bar looking
	 * emptier. Derived stats and the growth-stage sprite then reflect the new level for the rest
	 * of the fight. Returns the max-HP gained (0 if the level didn't actually increase).
	 */
	public int growTo(int newLevel)
	{
		if (newLevel <= level)
		{
			return 0;
		}
		int oldMax = maxHp;
		this.level = newLevel;
		this.maxHp = Leveling.hpAtLevel(species.baseFor(variantId).getHp(), newLevel);
		int gain = maxHp - oldMax;
		if (gain > 0)
		{
			currentHp = Math.min(maxHp, currentHp + gain);
		}
		return Math.max(0, gain);
	}

	public List<MoveDef> getMoves()
	{
		return moves;
	}

	/**
	 * Replace this pet's in-battle moveset. Used to reflect a move learned/forgotten
	 * mid-battle so the new move is usable for the rest of the fight, without rebuilding
	 * the pet. The list is copied; empty replacements are ignored so a pet is never unarmed.
	 */
	public void setMoves(List<MoveDef> newMoves)
	{
		if (newMoves == null || newMoves.isEmpty())
		{
			return;
		}
		moves.clear();
		moves.addAll(newMoves);
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
		double v = Leveling.statAtLevel(species.baseFor(variantId).getAtk(), level) * stageMultiplier(atkStage);
		if (status == Status.BURN)
		{
			v *= 0.5;
		}
		return Math.max(1, (int) Math.round(v));
	}

	public int effectiveDef()
	{
		double v = Leveling.statAtLevel(species.baseFor(variantId).getDef(), level) * stageMultiplier(defStage);
		return Math.max(1, (int) Math.round(v));
	}

	public int effectiveSpd()
	{
		double v = Leveling.statAtLevel(species.baseFor(variantId).getSpd(), level) * stageMultiplier(spdStage);
		if (status == Status.STUN)
		{
			v *= 0.5;
		}
		return Math.max(1, (int) Math.round(v));
	}

	public boolean hasStab(PetType moveType)
	{
		return getTypes().contains(moveType);
	}
}
