package com.petbattles.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A player's individual pet: persistent progression state (serialized to the roster blob).
 */
public class PetInstance
{
	public static final int MAX_EQUIPPED_MOVES = 4;

	private String speciesId;
	private String nickname;
	private long xp;
	private List<String> equippedMoves = new ArrayList<>();
	private Set<String> unlockedEggMoves = new LinkedHashSet<>();
	// HP carried over from the last battle; null = fully rested. 0 = fainted (can't
	// fight until rested at a bank). Absolute value, clamped to max HP on battle start.
	private Integer currentHp;
	// Active metamorphosis form (a SpeciesDef.Variant id), or null for the base form. Set from
	// read-only follower/inventory detection; a lens over presentation (and later combat), not a
	// separate roster entry — progression stays on this one instance.
	private String activeVariantId;
	// Equipped HELD item (an EquipItemDef id), or null. Applies its passive stat modifier in battle.
	private String heldItemId;
	// Equipped cosmetics (EquipItemDef ids), or null for a bare pet. Purely presentational: drawn on
	// the pet's battle chathead, no combat effect. Kept as two plain fields rather than a slot map so
	// the roster blob stays flat and this class keeps no dependency on the item package.
	private String headItemId;
	private String faceItemId;

	public PetInstance()
	{
	}

	public PetInstance(String speciesId)
	{
		this.speciesId = speciesId;
	}

	public String getSpeciesId()
	{
		return speciesId;
	}

	public String getNickname()
	{
		return nickname;
	}

	public void setNickname(String nickname)
	{
		this.nickname = nickname;
	}

	public long getXp()
	{
		return xp;
	}

	public int getLevel()
	{
		return Leveling.levelForXp(xp);
	}

	/**
	 * Add XP; returns the number of levels gained.
	 */
	public int addXp(long amount)
	{
		int before = getLevel();
		xp += Math.max(0, amount);
		return getLevel() - before;
	}

	/**
	 * Active metamorphosis form (a {@link SpeciesDef.Variant} id), or null for the base form.
	 */
	public String getActiveVariantId()
	{
		return activeVariantId;
	}

	public void setActiveVariantId(String activeVariantId)
	{
		this.activeVariantId = activeVariantId;
	}

	/**
	 * The equipped HELD item's id (an {@code EquipItemDef} id), or null if the pet holds nothing.
	 */
	public String getHeldItemId()
	{
		return heldItemId;
	}

	public void setHeldItemId(String heldItemId)
	{
		this.heldItemId = heldItemId;
	}

	/**
	 * The equipped HEAD cosmetic's id (an {@code EquipItemDef} id), or null if the pet wears none.
	 */
	public String getHeadItemId()
	{
		return headItemId;
	}

	public void setHeadItemId(String headItemId)
	{
		this.headItemId = headItemId;
	}

	/**
	 * The equipped FACE cosmetic's id (an {@code EquipItemDef} id), or null if the pet wears none.
	 */
	public String getFaceItemId()
	{
		return faceItemId;
	}

	public void setFaceItemId(String faceItemId)
	{
		this.faceItemId = faceItemId;
	}

	public Integer getCurrentHp()
	{
		return currentHp;
	}

	public void setCurrentHp(Integer currentHp)
	{
		this.currentHp = currentHp;
	}

	public boolean isFainted()
	{
		return currentHp != null && currentHp <= 0;
	}

	/**
	 * Fully rest this pet (bank heal): back to full HP and battle-ready.
	 */
	public void rest()
	{
		currentHp = null;
	}

	public List<String> getEquippedMoves()
	{
		if (equippedMoves == null)
		{
			equippedMoves = new ArrayList<>();
		}
		return equippedMoves;
	}

	public boolean equipMove(String moveId)
	{
		List<String> moves = getEquippedMoves();
		if (moves.contains(moveId) || moves.size() >= MAX_EQUIPPED_MOVES)
		{
			return false;
		}
		moves.add(moveId);
		return true;
	}

	public boolean unequipMove(String moveId)
	{
		return getEquippedMoves().remove(moveId);
	}

	public Set<String> getUnlockedEggMoves()
	{
		if (unlockedEggMoves == null)
		{
			unlockedEggMoves = new LinkedHashSet<>();
		}
		return unlockedEggMoves;
	}

	/**
	 * All move ids this pet can equip: learnset up to current level (for its active variant) plus
	 * unlocked egg moves.
	 */
	public List<String> availableMoves(SpeciesDef species)
	{
		List<String> moves = new ArrayList<>(species.movesKnownFor(activeVariantId, getLevel()));
		for (String egg : getUnlockedEggMoves())
		{
			if (!moves.contains(egg))
			{
				moves.add(egg);
			}
		}
		return moves;
	}
}
