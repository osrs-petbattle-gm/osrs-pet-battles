package com.petbattles.engine;

/**
 * One level-up move unlock in a species learnset.
 */
public class LearnsetEntry
{
	private int level;
	private String move;

	public LearnsetEntry()
	{
	}

	public LearnsetEntry(int level, String move)
	{
		this.level = level;
		this.move = move;
	}

	public int getLevel()
	{
		return level;
	}

	public String getMove()
	{
		return move;
	}
}
