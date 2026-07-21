package com.petbattles.engine;

/**
 * One growth stage of an evolving species (e.g. kitten -> cat -> overgrown cat). A stage
 * changes the pet's displayed name and sprite once it reaches the given level; base stats,
 * types and learnset stay species-wide. Stages are listed in species.json in level order.
 */
public class GrowthStage
{
	private int level;
	private String name;
	private int itemId;

	public GrowthStage()
	{
	}

	public GrowthStage(int level, String name, int itemId)
	{
		this.level = level;
		this.name = name;
		this.itemId = itemId;
	}

	public int getLevel()
	{
		return level;
	}

	public String getName()
	{
		return name;
	}

	public int getItemId()
	{
		return itemId;
	}
}
