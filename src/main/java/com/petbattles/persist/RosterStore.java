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
	/**
	 * v1: itemInventory held *spares* — equipping a held item decremented it. v2: it holds the total
	 * owned and equipping consumes nothing, the wear limit being a capacity check over wearers
	 * instead. {@link RosterManager#load()} migrates v1 blobs by crediting worn copies back.
	 */
	public static final int SCHEMA_VERSION = 2;

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
		// Trainers beaten at least once (unlocks remote re-fights)
		public Set<String> defeatedTrainers = new LinkedHashSet<>();
		// How many times each trainer has been beaten, for the re-fight reward taper
		// (Leveling.repeatFactor). Absent for a trainer in defeatedTrainers means a pre-taper save,
		// which RosterManager.trainerWins reads as a single win.
		public Map<String, Integer> trainerWins = new LinkedHashMap<>();
		// Step reached per quest id (see com.petbattles.quest.Quest); absent = step 0 (not started).
		public Map<String, Integer> questProgress = new LinkedHashMap<>();
		// One-off / branch quest flags (e.g. a data-quest chapter's marker); see QuestManager.
		public Set<String> questFlags = new LinkedHashSet<>();
		// Highest RosterManager.PROGRESSION_RESET_VERSION already applied to this roster.
		// When the code constant exceeds this, load() wipes progression once and bumps it.
		public int progressionResetVersion = 0;
		// Lifetime count of battles started, shown as a stat on the Items panel.
		public int totalBattles = 0;
		// Soft-currency wallet: coins earned from battles/quests, spent in the store. Purely local
		// -- never in-game GP, never real money, never sent anywhere.
		public long coins = 0;
		// Owned equip items (EquipItemDef id -> count): held items and cosmetics won from quests or
		// bought in the store. A pet's equipped item references an id it owns here.
		public Map<String, Integer> itemInventory = new LinkedHashMap<>();
		// Player-vs-player record. Two counts and nothing else: no opponent names, no match history,
		// nothing that identifies another player — this stays a private tally on your own save, and
		// is never published anywhere (see the PvP section of docs/plans/roadmap.md).
		public int pvpWins = 0;
		public int pvpLosses = 0;
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
