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
	 * All move ids this pet can equip: learnset up to current level plus unlocked egg moves.
	 */
	public List<String> availableMoves(SpeciesDef species)
	{
		List<String> moves = new ArrayList<>(species.movesKnownAt(getLevel()));
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
