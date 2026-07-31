package com.petbattles.persist;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.petbattles.PetBattlesConfig;
import com.petbattles.engine.PetInstance;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Persists the roster as one versioned JSON blob under RSProfile-scoped config,
 * so state is keyed to the logged-in RS account and rides RuneLite's config sync.
 */
@Slf4j
public class RosterStore
{
	private static final String KEY = "roster";
	private static final int SCHEMA_VERSION = 1;

	/**
	 * The serialized shape. Add fields with defaults; bump v only on breaking changes.
	 */
	public static class RosterData
	{
		public int v = SCHEMA_VERSION;
		public Set<String> ownedSpecies = new LinkedHashSet<>();
		// Pets manually unlocked for testing via the "Dev: select locked pets" option.
		// Kept apart from ownedSpecies so it never masquerades as a real collection-log unlock.
		public Set<String> devUnlocked = new LinkedHashSet<>();
		public Map<String, PetInstance> pets = new LinkedHashMap<>();
		public List<String> team = new ArrayList<>();
		// Trainers beaten at least once (unlocks remote re-fights at reduced XP)
		public Set<String> defeatedTrainers = new LinkedHashSet<>();
		// Step reached per quest id (see com.petbattles.quest.Quest); absent = step 0 (not started).
		public Map<String, Integer> questProgress = new LinkedHashMap<>();
		// Highest RosterManager.PROGRESSION_RESET_VERSION already applied to this roster.
		// When the code constant exceeds this, load() wipes progression once and bumps it.
		public int progressionResetVersion = 0;
		// Lifetime count of battles started, shown as a stat on the Items panel.
		public int totalBattles = 0;
	}

	private final ConfigManager configManager;
	private final Gson gson;

	public RosterStore(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	/**
	 * Load the blob for the current RS profile; empty data if absent or unreadable.
	 */
	public RosterData load()
	{
		String json = configManager.getRSProfileConfiguration(PetBattlesConfig.GROUP, KEY);
		if (json == null || json.isEmpty())
		{
			return new RosterData();
		}
		try
		{
			RosterData data = gson.fromJson(json, RosterData.class);
			return data == null ? new RosterData() : data;
		}
		catch (JsonSyntaxException e)
		{
			log.warn("Corrupt pet-battles roster blob; starting fresh", e);
			return new RosterData();
		}
	}

	public void save(RosterData data)
	{
		configManager.setRSProfileConfiguration(PetBattlesConfig.GROUP, KEY, gson.toJson(data));
	}
}
