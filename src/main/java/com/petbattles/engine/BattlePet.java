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
	// Passive held-item modifier for this battler (null = holds nothing). Applied to the derived
	// stats below and to max HP; enemy battlers hold nothing in v1.
	private final ItemEffect heldEffect;
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
	// Cosmetics worn into the fight (EquipItemDef ids, null for none). Presentation only — they never
	// touch a stat, so they're set after construction rather than widening the constructor chain
	// again. Enemy battlers wear nothing.
	private String headCosmeticId;
	private String faceCosmeticId;

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

	public BattlePet(SpeciesDef species, String displayName, int level, List<MoveDef> moves,
		Integer startingHp, String variantId)
	{
		this(species, displayName, level, moves, startingHp, variantId, null);
	}

	/**
	 * @param startingHp carried-over HP from a previous battle; null starts at full
	 * @param variantId  active metamorphosis form id, or null for the base form
	 * @param heldEffect passive held-item stat modifier, or null for none
	 */
	public BattlePet(SpeciesDef species, String displayName, int level, List<MoveDef> moves,
		Integer startingHp, String variantId, ItemEffect heldEffect)
	{
		this.species = species;
		this.variantId = variantId;
		this.heldEffect = heldEffect;
		this.displayName = displayName;
		this.level = level;
		this.moves = new ArrayList<>(moves);
		this.maxHp = computeMaxHp(level);
		this.currentHp = startingHp == null ? maxHp : Math.max(0, Math.min(maxHp, startingHp));
	}

	/**
	 * Max HP at {@code level}, folding in a held item's HP modifier (if any). Centralised so the
	 * constructor and {@link #growTo} stay in step.
	 */
	private int computeMaxHp(int level)
	{
		int base = Leveling.hpAtLevel(species.baseFor(variantId).getHp(), level);
		return Math.max(1, (int) Math.round(base * heldMultiplier(ItemEffect.Stat.HP)));
	}

	/** The held item's multiplier for {@code stat}, or 1.0 when this battler holds nothing. */
	private double heldMultiplier(ItemEffect.Stat stat)
	{
		return heldEffect == null ? 1.0 : heldEffect.multiplierFor(stat);
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

	/** The HEAD cosmetic worn into this fight (an {@code EquipItemDef} id), or null. */
	public String getHeadCosmeticId()
	{
		return headCosmeticId;
	}

	/** The FACE cosmetic worn into this fight (an {@code EquipItemDef} id), or null. */
	public String getFaceCosmeticId()
	{
		return faceCosmeticId;
	}

	/** Dress this battler for the scene. Purely cosmetic; safe to call at any point. */
	public void setCosmetics(String headCosmeticId, String faceCosmeticId)
	{
		this.headCosmeticId = headCosmeticId;
		this.faceCosmeticId = faceCosmeticId;
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
		this.maxHp = computeMaxHp(newLevel);
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
		v *= heldMultiplier(ItemEffect.Stat.ATK);
		return Math.max(1, (int) Math.round(v));
	}

	public int effectiveDef()
	{
		double v = Leveling.statAtLevel(species.baseFor(variantId).getDef(), level) * stageMultiplier(defStage);
		v *= heldMultiplier(ItemEffect.Stat.DEF);
		return Math.max(1, (int) Math.round(v));
	}

	public int effectiveSpd()
	{
		double v = Leveling.statAtLevel(species.baseFor(variantId).getSpd(), level) * stageMultiplier(spdStage);
		if (status == Status.STUN)
		{
			v *= 0.5;
		}
		v *= heldMultiplier(ItemEffect.Stat.SPD);
		return Math.max(1, (int) Math.round(v));
	}

	public boolean hasStab(PetType moveType)
	{
		return getTypes().contains(moveType);
	}
}
