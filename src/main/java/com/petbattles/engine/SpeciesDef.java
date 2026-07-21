package com.petbattles.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A pet species loaded from species.json.
 */
public class SpeciesDef
{
	private String id;
	private String name;
	private int itemId;
	private List<Integer> altItemIds = new ArrayList<>();
	private List<Integer> npcIds = new ArrayList<>();
	private List<String> npcXpTags = new ArrayList<>();
	private List<PetType> types = new ArrayList<>();
	private Stats base;
	private List<LearnsetEntry> learnset = new ArrayList<>();
	private List<EasterEggDef> easterEggs = new ArrayList<>();
	// Unlocked by possessing the pet in-game (item in inventory/bank, or as a follower)
	// rather than via the collection log — for the OSRS cat, which isn't a log pet.
	private boolean itemUnlock;
	// Optional evolution stages (kitten -> cat -> ...); empty for non-growing species.
	private List<GrowthStage> growthStages = new ArrayList<>();

	public String getId()
	{
		return id;
	}

	public String getName()
	{
		return name;
	}

	public int getItemId()
	{
		return itemId;
	}

	public List<Integer> getAltItemIds()
	{
		return altItemIds == null ? Collections.emptyList() : altItemIds;
	}

	public List<Integer> getNpcIds()
	{
		return npcIds == null ? Collections.emptyList() : npcIds;
	}

	public List<String> getNpcXpTags()
	{
		return npcXpTags == null ? Collections.emptyList() : npcXpTags;
	}

	public List<PetType> getTypes()
	{
		return types == null ? Collections.emptyList() : types;
	}

	public Stats getBase()
	{
		return base;
	}

	public List<LearnsetEntry> getLearnset()
	{
		return learnset == null ? Collections.emptyList() : learnset;
	}

	public List<EasterEggDef> getEasterEggs()
	{
		return easterEggs == null ? Collections.emptyList() : easterEggs;
	}

	/**
	 * Whether this pet is unlocked by possessing it in-game — the item in the player's
	 * inventory or bank, or the pet following them — instead of via the collection log.
	 */
	public boolean isItemUnlock()
	{
		return itemUnlock;
	}

	public List<GrowthStage> getGrowthStages()
	{
		return growthStages == null ? Collections.emptyList() : growthStages;
	}

	/**
	 * The growth stage active at the given level (the highest stage whose level threshold
	 * has been reached), or null if this species doesn't evolve.
	 */
	public GrowthStage stageAt(int level)
	{
		GrowthStage current = null;
		for (GrowthStage stage : getGrowthStages())
		{
			if (stage.getLevel() <= level)
			{
				current = stage;
			}
		}
		return current;
	}

	/**
	 * Display name at the given level: the active growth stage's name, else the base name.
	 */
	public String nameAt(int level)
	{
		GrowthStage stage = stageAt(level);
		return stage != null && stage.getName() != null ? stage.getName() : name;
	}

	/**
	 * Sprite item id at the given level: the active growth stage's item, else the base item.
	 */
	public int itemIdAt(int level)
	{
		GrowthStage stage = stageAt(level);
		return stage != null && stage.getItemId() > 0 ? stage.getItemId() : itemId;
	}

	/**
	 * All item ids (primary + alternates) whose presence in the collection log means this pet is owned.
	 */
	public List<Integer> getAllItemIds()
	{
		List<Integer> ids = new ArrayList<>();
		ids.add(itemId);
		ids.addAll(getAltItemIds());
		return ids;
	}

	/**
	 * Moves known at the given level (learnset entries at or below it), newest first.
	 */
	public List<String> movesKnownAt(int level)
	{
		List<String> moves = new ArrayList<>();
		for (LearnsetEntry e : getLearnset())
		{
			if (e.getLevel() <= level)
			{
				moves.add(e.getMove());
			}
		}
		Collections.reverse(moves);
		return moves;
	}
}
