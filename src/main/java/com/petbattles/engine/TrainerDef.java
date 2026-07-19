package com.petbattles.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An AI trainer opponent loaded from trainers.json.
 */
public class TrainerDef
{
	public enum Difficulty
	{
		EASY,
		MEDIUM,
		HARD
	}

	public static class PartyEntry
	{
		private String species;
		private int level;

		public PartyEntry()
		{
		}

		public PartyEntry(String species, int level)
		{
			this.species = species;
			this.level = level;
		}

		public String getSpecies()
		{
			return species;
		}

		public int getLevel()
		{
			return level;
		}
	}

	private String id;
	private String name;
	private String theme;
	private Difficulty difficulty = Difficulty.EASY;
	private List<PartyEntry> party = new ArrayList<>();
	// In-world NPC ids for this trainer (net.runelite.api.gameval.NpcID values);
	// empty means the trainer is panel-only.
	private List<Integer> npcIds = new ArrayList<>();

	public String getId()
	{
		return id;
	}

	public String getName()
	{
		return name;
	}

	public String getTheme()
	{
		return theme;
	}

	public Difficulty getDifficulty()
	{
		return difficulty == null ? Difficulty.EASY : difficulty;
	}

	public List<PartyEntry> getParty()
	{
		return party == null ? Collections.emptyList() : party;
	}

	public List<Integer> getNpcIds()
	{
		return npcIds == null ? Collections.emptyList() : npcIds;
	}
}
