package com.petbattles.persist;

import com.petbattles.PetBattlesConfig;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.PetInstance;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.item.EquipItemDef;
import com.petbattles.item.Item;
import com.petbattles.quest.QuestDef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Runtime roster state: which species are owned, per-pet progression, and the battle team.
 * All mutations save through the store immediately (the blob is small).
 */
@Slf4j
public class RosterManager
{
	public static final int MAX_TEAM_SIZE = 3;

	/**
	 * Bump this whenever a content change (balance pass, movepool/level retune, XP-curve
	 * change) makes stored pet progression stale enough that every roster should be wiped
	 * back to a clean level-1 state on next load. Not every release needs this -- only bump
	 * when old progression would otherwise be invalid or misleading. Add a changelog line
	 * below each time you bump it, so it's clear which versions triggered a reset.
	 *
	 * Reset changelog:
	 *   (none yet -- bump to 1 and add a line here to trigger the first reset)
	 */
	public static final int PROGRESSION_RESET_VERSION = 0;

	// The capstone story quest whose key-item trophies (Sealed Envelope, Blue party hat) derive their
	// ownership from its progress -- see ownsItem. Kept here (not in QuestManager) because ownership
	// is read straight off questProgress, mirroring the REMOTE_BATTLE_DEVICE case. The steps that
	// grant them are read from the quest content, so re-ordering or dropping chapters can't desync
	// them.
	private static final String CAPSTONE_QUEST_ID = "series_of_fortunate_events";

	// The intro quest that hands over the Remote Battle Device. Completing it lets the player battle
	// already-defeated trainers remotely (see canRemoteFight). The complete step is read from the
	// quest content, so adding chapters can't desync this.
	private static final String REMOTE_QUEST_ID = "wheres_the_remote";

	private final PetDatabase db;
	private final RosterStore store;
	private RosterStore.RosterData data = new RosterStore.RosterData();
	private boolean loaded;
	// Team composition (add/remove) is bank-gated; reordering is not. The gate is
	// re-checked here so a stale panel can't edit the team away from a bank.
	private volatile BooleanSupplier teamEditGate = () -> true;

	public RosterManager(PetDatabase db, RosterStore store)
	{
		this.db = db;
		this.store = store;
	}

	public synchronized void load()
	{
		data = store.load();
		if (data.devUnlocked == null)
		{
			data.devUnlocked = new java.util.LinkedHashSet<>();
		}
		if (data.defeatedTrainers == null)
		{
			data.defeatedTrainers = new java.util.LinkedHashSet<>();
		}
		if (data.trainerWins == null)
		{
			data.trainerWins = new LinkedHashMap<>();
		}
		if (data.questProgress == null)
		{
			data.questProgress = new java.util.LinkedHashMap<>();
		}
		if (data.questFlags == null)
		{
			data.questFlags = new java.util.LinkedHashSet<>();
		}
		if (data.itemInventory == null)
		{
			data.itemInventory = new LinkedHashMap<>();
		}
		// Drop references to species/trainers that no longer exist in the content
		data.team.removeIf(id -> db.species(id) == null);
		data.ownedSpecies.removeIf(id -> db.species(id) == null);
		data.devUnlocked.removeIf(id -> db.species(id) == null);
		data.defeatedTrainers.removeIf(id -> db.trainer(id) == null);
		data.trainerWins.keySet().removeIf(id -> db.trainer(id) == null);
		// One-shot progression wipe when the code constant outruns what this roster has seen.
		if (data.progressionResetVersion < PROGRESSION_RESET_VERSION)
		{
			log.debug("Applying progression reset v{} (was v{}); wiping {} pet record(s)",
				PROGRESSION_RESET_VERSION, data.progressionResetVersion, data.pets.size());
			data.pets.clear();
			data.progressionResetVersion = PROGRESSION_RESET_VERSION;
			store.save(data);
		}
		loaded = true;
	}

	public synchronized void unload()
	{
		if (loaded)
		{
			store.save(data);
		}
		data = new RosterStore.RosterData();
		loaded = false;
	}

	public boolean isLoaded()
	{
		return loaded;
	}

	private synchronized void save()
	{
		if (loaded)
		{
			store.save(data);
		}
	}

	public synchronized boolean isOwned(String speciesId)
	{
		return PetBattlesConfig.devUnlockAll()
			|| data.ownedSpecies.contains(speciesId)
			|| (PetBattlesConfig.devSelectLockedPets() && data.devUnlocked.contains(speciesId));
	}

	/**
	 * Whether the "select locked pets" testing option is currently enabled.
	 */
	public boolean isDevSelectEnabled()
	{
		return PetBattlesConfig.devSelectLockedPets();
	}

	/**
	 * Whether this species has been manually dev-unlocked (independent of the toggle state).
	 */
	public synchronized boolean isDevUnlocked(String speciesId)
	{
		return data.devUnlocked.contains(speciesId);
	}

	/**
	 * Manually unlock a species for testing. No-op for species already owned for real.
	 * Returns true if this added a new dev unlock.
	 */
	public synchronized boolean devUnlock(String speciesId)
	{
		if (db.species(speciesId) == null || data.ownedSpecies.contains(speciesId)
			|| !data.devUnlocked.add(speciesId))
		{
			return false;
		}
		save();
		return true;
	}

	/**
	 * Remove a manual dev unlock, also dropping the pet from the battle team.
	 */
	public synchronized boolean devLock(String speciesId)
	{
		if (!data.devUnlocked.remove(speciesId))
		{
			return false;
		}
		data.team.remove(speciesId);
		save();
		return true;
	}

	/**
	 * Give up ownership of a pet entirely: drop it from the owned set, the battle team and
	 * its progression record. Used when a possession-gated pet is traded away in-game (e.g.
	 * trading a grown cat for death runes). Returns true if the pet was owned.
	 */
	public synchronized boolean removeOwnership(String speciesId)
	{
		boolean owned = data.ownedSpecies.remove(speciesId);
		data.team.remove(speciesId);
		data.pets.remove(speciesId);
		data.devUnlocked.remove(speciesId);
		if (owned)
		{
			save();
		}
		return owned;
	}

	/**
	 * Testing aid: wipe all per-pet progression. Every pet drops back to level 1 with its
	 * starter moveset and full HP (records are recreated on demand by getOrCreatePet).
	 * Ownership, team composition and trainer wins are kept. Returns the number of pet
	 * records cleared.
	 */
	public synchronized int resetProgression()
	{
		int cleared = data.pets.size();
		log.debug("resetProgression invoked; clearing {} pet record(s), team={}", cleared, data.team);
		if (cleared > 0)
		{
			data.pets.clear();
			// Recreate team members' records now (fresh level-1) so the panel shows them
			// immediately rather than waiting for the next lazy getOrCreatePet.
			for (String speciesId : data.team)
			{
				getOrCreatePet(speciesId);
			}
			save();
		}
		return cleared;
	}

	/**
	 * Mark a species as owned (from collection log sync or a live drop). Additive only.
	 * Returns true if this is a new unlock.
	 */
	public synchronized boolean unlock(String speciesId)
	{
		if (db.species(speciesId) == null || data.ownedSpecies.contains(speciesId))
		{
			return false;
		}
		data.ownedSpecies.add(speciesId);
		save();
		return true;
	}

	/**
	 * Get the progression record for an owned species, creating it at level 1 with
	 * its starter moves equipped.
	 */
	public synchronized PetInstance getOrCreatePet(String speciesId)
	{
		PetInstance pet = data.pets.get(speciesId);
		if (pet == null)
		{
			SpeciesDef species = db.species(speciesId);
			if (species == null)
			{
				return null;
			}
			pet = new PetInstance(speciesId);
			for (String move : pet.availableMoves(species))
			{
				if (pet.getEquippedMoves().size() >= PetInstance.MAX_EQUIPPED_MOVES)
				{
					break;
				}
				pet.equipMove(move);
			}
			data.pets.put(speciesId, pet);
			save();
		}
		return pet;
	}

	public synchronized PetInstance getPet(String speciesId)
	{
		return data.pets.get(speciesId);
	}

	/**
	 * Record the active metamorphosis form for an owned pet (from read-only follower/inventory
	 * detection). No-op unless the pet is owned and the form actually changed. Returns true if it
	 * changed. Passing null reverts the pet to its base form (e.g. the player toggled back in-game).
	 */
	public synchronized boolean setActiveVariant(String speciesId, String variantId)
	{
		if (!isOwned(speciesId))
		{
			return false;
		}
		PetInstance pet = getOrCreatePet(speciesId);
		if (pet == null || Objects.equals(pet.getActiveVariantId(), variantId))
		{
			return false;
		}
		pet.setActiveVariantId(variantId);
		save();
		return true;
	}

	public synchronized List<String> getTeam()
	{
		// Copy: callers iterate outside the lock
		return new ArrayList<>(data.team);
	}

	public void setTeamEditGate(BooleanSupplier gate)
	{
		this.teamEditGate = gate;
	}

	/**
	 * Whether team composition may currently be changed (i.e. the player is at a bank).
	 */
	public boolean canEditTeam()
	{
		return teamEditGate.getAsBoolean();
	}

	public synchronized boolean addToTeam(String speciesId)
	{
		if (!canEditTeam() || data.team.contains(speciesId)
			|| data.team.size() >= MAX_TEAM_SIZE || !isOwned(speciesId))
		{
			return false;
		}
		data.team.add(speciesId);
		getOrCreatePet(speciesId);
		save();
		return true;
	}

	public synchronized boolean removeFromTeam(String speciesId)
	{
		if (!canEditTeam())
		{
			return false;
		}
		boolean removed = data.team.remove(speciesId);
		if (removed)
		{
			save();
		}
		return removed;
	}

	/**
	 * Move a team member up (-1) or down (+1) in send-out order. Not bank-gated:
	 * order stays adjustable anywhere.
	 */
	public synchronized boolean moveTeamMember(String speciesId, int delta)
	{
		int from = data.team.indexOf(speciesId);
		if (from < 0)
		{
			return false;
		}
		int to = from + delta;
		if (to < 0 || to >= data.team.size() || to == from)
		{
			return false;
		}
		Collections.swap(data.team, from, to);
		save();
		return true;
	}

	/**
	 * Drag-to-reorder: move {@code speciesId} to battle position {@code insertIndex} (0-based, among
	 * the team). Not bank-gated, like {@link #moveTeamMember}. Returns true if the order changed.
	 */
	public synchronized boolean reorderTeamToIndex(String speciesId, int insertIndex)
	{
		int from = data.team.indexOf(speciesId);
		if (from < 0)
		{
			return false;
		}
		int idx = Math.max(0, Math.min(insertIndex, data.team.size() - 1));
		if (idx == from)
		{
			return false;
		}
		data.team.remove(from);
		data.team.add(idx, speciesId);
		save();
		return true;
	}

	/**
	 * Whether this trainer has been beaten at least once (in-world first win).
	 */
	public synchronized boolean isTrainerDefeated(String trainerId)
	{
		return data.defeatedTrainers.contains(trainerId);
	}

	/**
	 * How many times this trainer has been beaten, which sets the re-fight reward taper (see
	 * {@link com.petbattles.engine.Leveling#repeatFactor(int)}). Saves written before the counter
	 * existed only know that a trainer was beaten, so one of those reads as a single win rather than
	 * handing a long-farmed trainer its first-win rate back.
	 */
	public synchronized int trainerWins(String trainerId)
	{
		Integer wins = data.trainerWins.get(trainerId);
		if (wins != null)
		{
			return wins;
		}
		return data.defeatedTrainers.contains(trainerId) ? 1 : 0;
	}

	/**
	 * Record a trainer win: flags the first defeat (which unlocks remote re-fights) and counts every
	 * win for the reward taper.
	 */
	public synchronized void recordTrainerDefeated(String trainerId)
	{
		if (db.trainer(trainerId) == null)
		{
			return;
		}
		// Count first: flagging the defeat would make trainerWins' legacy fallback read this very win
		// as a prior one, so a first clear would land on 2.
		int wins = trainerWins(trainerId);
		data.defeatedTrainers.add(trainerId);
		data.trainerWins.put(trainerId, wins + 1);
		save();
	}

	/**
	 * The step this quest has reached (0 = not started / in progress). Persistent.
	 */
	public synchronized int getQuestStep(String questId)
	{
		Integer step = data.questProgress.get(questId);
		return step == null ? 0 : step;
	}

	/**
	 * Advance a quest to at least the given step; no-op if already there or beyond.
	 * Returns true if this moved the quest forward (and persisted).
	 */
	public synchronized boolean advanceQuest(String questId, int step)
	{
		if (getQuestStep(questId) >= step)
		{
			return false;
		}
		data.questProgress.put(questId, step);
		save();
		return true;
	}

	/**
	 * Whether a one-off / branch quest flag is set. Persistent.
	 */
	public synchronized boolean hasFlag(String flag)
	{
		return data.questFlags.contains(flag);
	}

	/**
	 * Set a one-off / branch quest flag, persisting. Returns true if it wasn't already set.
	 */
	public synchronized boolean setFlag(String flag)
	{
		if (flag == null || !data.questFlags.add(flag))
		{
			return false;
		}
		save();
		return true;
	}

	/**
	 * A copy of the currently-set quest flags. Callers iterate outside the lock.
	 */
	public synchronized Set<String> getFlags()
	{
		return new LinkedHashSet<>(data.questFlags);
	}

	/**
	 * Testing aid: wipe all quest progress and flags back to not-started. This relocks anything a
	 * quest granted (e.g. remote battles / the Remote Battle Device item), so a developer can re-run
	 * the quest flow without clearing their whole roster. Returns the number of quest records cleared.
	 */
	public synchronized int resetQuests()
	{
		int cleared = data.questProgress.size();
		log.debug("resetQuests invoked; clearing {} quest record(s) and {} flag(s)",
			cleared, data.questFlags.size());
		if (cleared > 0 || !data.questFlags.isEmpty())
		{
			data.questProgress.clear();
			data.questFlags.clear();
			save();
		}
		return cleared;
	}

	/**
	 * Lifetime number of battles the player has started. Shown as a stat on the Items panel.
	 */
	public synchronized int getTotalBattles()
	{
		return data.totalBattles;
	}

	/**
	 * Count one more battle started, and persist. Called when a battle actually begins.
	 */
	public synchronized void recordBattleFought()
	{
		data.totalBattles++;
		save();
	}

	/**
	 * The player's soft-currency wallet balance: coins earned from battles and quests, spent in the
	 * store. Purely local -- never in-game GP, never real money, never reported anywhere.
	 */
	public synchronized long getCoins()
	{
		return data.coins;
	}

	/**
	 * Add coins to the wallet (a battle or quest reward), persisting. Non-positive amounts are
	 * ignored (the wallet is never drained by an "award"). Returns the new balance.
	 */
	public synchronized long addCoins(long amount)
	{
		if (amount > 0)
		{
			data.coins += amount;
			save();
		}
		return data.coins;
	}

	/**
	 * Spend coins if the wallet can cover it: deducts and persists, returning true on success.
	 * Returns false with no change if the amount is non-positive or exceeds the balance.
	 */
	public synchronized boolean spendCoins(long amount)
	{
		if (amount <= 0 || data.coins < amount)
		{
			return false;
		}
		data.coins -= amount;
		save();
		return true;
	}

	// --- Equip-item inventory (held items + cosmetics won from quests / bought in the store) ---

	/**
	 * A copy of the owned-equip-item counts ({@code EquipItemDef} id -> count). Callers iterate
	 * outside the lock.
	 */
	public synchronized Map<String, Integer> getItemInventory()
	{
		return new LinkedHashMap<>(data.itemInventory);
	}

	/**
	 * How many of this equip item the player owns (0 if none).
	 */
	public synchronized int itemCount(String itemId)
	{
		Integer n = data.itemInventory.get(itemId);
		return n == null ? 0 : n;
	}

	/**
	 * Whether the player owns at least one of this equip item.
	 */
	public synchronized boolean hasItem(String itemId)
	{
		return itemCount(itemId) > 0;
	}

	/**
	 * Add {@code count} of an equip item to the inventory (a quest or store grant). Ignores unknown
	 * ids and non-positive counts. Returns true if anything was added.
	 */
	public synchronized boolean grantItem(String itemId, int count)
	{
		if (count <= 0 || db.equipItem(itemId) == null)
		{
			return false;
		}
		data.itemInventory.merge(itemId, count, Integer::sum);
		save();
		return true;
	}

	/**
	 * Remove {@code count} of an equip item if the player has enough (a store trade-in / consume).
	 * Returns false with no change otherwise. The key is dropped when its count reaches zero.
	 */
	public synchronized boolean takeItem(String itemId, int count)
	{
		if (count <= 0 || itemCount(itemId) < count)
		{
			return false;
		}
		int remaining = itemCount(itemId) - count;
		if (remaining > 0)
		{
			data.itemInventory.put(itemId, remaining);
		}
		else
		{
			data.itemInventory.remove(itemId);
		}
		save();
		return true;
	}

	/**
	 * Equip a HELD item on an owned pet: the pet must be owned, the item must exist, be a HELD-slot
	 * item, and be in stock. No-op (returns false) otherwise. Equipping consumes one unit from the
	 * inventory and returns whatever the pet was previously holding, so a given unit can only be worn
	 * by one pet at a time.
	 */
	public synchronized boolean setHeldItem(String speciesId, String itemId)
	{
		EquipItemDef item = db.equipItem(itemId);
		if (!isOwned(speciesId) || item == null
			|| item.getSlot() != EquipItemDef.Slot.HELD || !hasItem(itemId))
		{
			return false;
		}
		PetInstance pet = getOrCreatePet(speciesId);
		if (pet == null || Objects.equals(pet.getHeldItemId(), itemId))
		{
			return false;
		}
		data.itemInventory.merge(itemId, -1, Integer::sum);
		if (data.itemInventory.getOrDefault(itemId, 0) <= 0)
		{
			data.itemInventory.remove(itemId);
		}
		String previous = pet.getHeldItemId();
		if (previous != null)
		{
			data.itemInventory.merge(previous, 1, Integer::sum);
		}
		pet.setHeldItemId(itemId);
		save();
		return true;
	}

	/**
	 * Remove whatever HELD item an owned pet is carrying, returning the unit to the inventory so it can
	 * be re-equipped elsewhere. Returns true if it was holding one.
	 */
	public synchronized boolean clearHeldItem(String speciesId)
	{
		PetInstance pet = getPet(speciesId);
		if (pet == null || pet.getHeldItemId() == null)
		{
			return false;
		}
		String held = pet.getHeldItemId();
		pet.setHeldItemId(null);
		data.itemInventory.merge(held, 1, Integer::sum);
		save();
		return true;
	}

	// --- Cosmetics (HEAD/FACE) ---
	//
	// Cosmetics work as a wardrobe, not as stock: equipping one does NOT consume it, so the same hat
	// can be worn by every pet on the team at once. This differs deliberately from HELD items, which
	// consume-on-equip so a stat bonus can't be duplicated across the team. Two reasons: a cosmetic
	// grants nothing, so there's nothing to duplicate; and a trophy like the blue party hat has
	// *derived* ownership (see ownsItem) with no inventory unit to move around, which the
	// consume-and-return dance can't represent.

	/**
	 * Whether the player owns a cosmetic they could equip: either it's in the equip-item inventory
	 * (bought or granted) or it's a key-item trophy whose ownership is derived from quest state.
	 */
	public synchronized boolean ownsCosmetic(String itemId)
	{
		if (hasItem(itemId))
		{
			return true;
		}
		Item keyItem = Item.byId(itemId);
		return keyItem != null && ownsItem(keyItem);
	}

	/**
	 * Every cosmetic the player owns, in content order — the wardrobe the equip UI offers. Includes
	 * derived-ownership trophies, which never appear in the item inventory.
	 */
	public synchronized List<EquipItemDef> ownedCosmetics()
	{
		List<EquipItemDef> owned = new ArrayList<>();
		for (EquipItemDef item : db.allEquipItems())
		{
			if (item.isCosmetic() && ownsCosmetic(item.getId()))
			{
				owned.add(item);
			}
		}
		return owned;
	}

	/**
	 * Equip a cosmetic on an owned pet, into whichever slot ({@code HEAD}/{@code FACE}) the item
	 * declares — replacing whatever occupied that slot. The pet must be owned, the item must exist,
	 * be a cosmetic, and be owned. No-op (returns false) otherwise, including when it's already worn.
	 */
	public synchronized boolean setCosmetic(String speciesId, String itemId)
	{
		EquipItemDef item = db.equipItem(itemId);
		if (!isOwned(speciesId) || item == null || !item.isCosmetic() || !ownsCosmetic(itemId))
		{
			return false;
		}
		PetInstance pet = getOrCreatePet(speciesId);
		if (pet == null || Objects.equals(cosmeticId(pet, item.getSlot()), itemId))
		{
			return false;
		}
		applyCosmetic(pet, item.getSlot(), itemId);
		save();
		return true;
	}

	/**
	 * Clear a pet's cosmetic slot. Returns true if it was wearing something there. Nothing returns to
	 * the inventory — cosmetics are never consumed on equip.
	 */
	public synchronized boolean clearCosmetic(String speciesId, EquipItemDef.Slot slot)
	{
		PetInstance pet = getPet(speciesId);
		if (pet == null || cosmeticId(pet, slot) == null)
		{
			return false;
		}
		applyCosmetic(pet, slot, null);
		save();
		return true;
	}

	/** What this pet wears in a cosmetic slot, or null (also null for the HELD slot). */
	private static String cosmeticId(PetInstance pet, EquipItemDef.Slot slot)
	{
		switch (slot)
		{
			case HEAD:
				return pet.getHeadItemId();
			case FACE:
				return pet.getFaceItemId();
			default:
				return null;
		}
	}

	/** Write a cosmetic slot on a pet; a no-op for the HELD slot. */
	private static void applyCosmetic(PetInstance pet, EquipItemDef.Slot slot, String itemId)
	{
		switch (slot)
		{
			case HEAD:
				pet.setHeadItemId(itemId);
				break;
			case FACE:
				pet.setFaceItemId(itemId);
				break;
			default:
				break;
		}
	}

	/**
	 * Whether the player currently holds this reward item. Derived from the state that granted it
	 * (so an item and the thing it unlocks can't drift apart), not stored separately.
	 */
	public synchronized boolean ownsItem(Item item)
	{
		switch (item)
		{
			case REMOTE_BATTLE_DEVICE:
				return isRemoteBattlesUnlocked();
			case SEALED_ENVELOPE:
				// Wrung from Ambassador Gimblewap partway through the capstone.
			case BLUE_PARTY_HAT:
				// The Wise Old Man's parting gift for finishing the capstone.
			{
				int granted = capstoneStepAwarding(item);
				return granted >= 0 && getQuestStep(CAPSTONE_QUEST_ID) >= granted;
			}
			default:
				return false;
		}
	}

	/**
	 * The capstone step at which a key-item trophy is in the player's hands: one past the chapter whose
	 * reward names it, since a chapter's reward lands as its step moves on. Read from the quest content
	 * so re-ordering or dropping chapters can't leave a trophy stranded behind a step that no longer
	 * exists. -1 if no chapter awards this item.
	 */
	private int capstoneStepAwarding(Item item)
	{
		QuestDef quest = db.quest(CAPSTONE_QUEST_ID);
		if (quest == null)
		{
			return -1;
		}
		for (QuestDef.Chapter chapter : quest.getChapters())
		{
			QuestDef.Reward reward = chapter.getReward();
			if (reward != null && item.getId().equals(reward.getKeyItem()))
			{
				return chapter.getStep() + 1;
			}
		}
		return -1;
	}

	/**
	 * Whether the player owns the Remote Battle Device: either the dev "remote battles" toggle, or
	 * they have finished "Where's the remote?" (Professor Oddenstein's device). Owning the device does
	 * not by itself let you fight everyone remotely -- it must be calibrated against a trainer by
	 * beating them in person first (see {@link #canRemoteFight(String)}).
	 */
	public synchronized boolean isRemoteBattlesUnlocked()
	{
		if (PetBattlesConfig.devRemoteBattles())
		{
			return true;
		}
		QuestDef q = db.quest(REMOTE_QUEST_ID);
		return q != null && getQuestStep(REMOTE_QUEST_ID) >= q.completeStep();
	}

	/**
	 * Whether this trainer can be re-fought remotely: the device is owned and the trainer has already
	 * been beaten in person (the device only reaches trainers it has "calibrated" against). A first
	 * fight always has to happen in the world, next to the trainer.
	 */
	public synchronized boolean canRemoteFight(String trainerId)
	{
		return isRemoteBattlesUnlocked() && isTrainerDefeated(trainerId);
	}

	/**
	 * Rest every pet back to full HP (bank heal). Bank-gated like team composition.
	 * Returns false if not at a bank or nothing needed healing.
	 */
	public synchronized boolean restAllPets()
	{
		if (!canEditTeam() || !anyPetInjured())
		{
			return false;
		}
		for (PetInstance pet : data.pets.values())
		{
			pet.rest();
		}
		save();
		return true;
	}

	/**
	 * Whether any pet carries battle damage (or is fainted) and would benefit from a rest.
	 */
	public synchronized boolean anyPetInjured()
	{
		for (PetInstance pet : data.pets.values())
		{
			if (pet.getCurrentHp() != null)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether at least one team member is able to fight (not fainted).
	 */
	public synchronized boolean teamCanFight()
	{
		for (String speciesId : data.team)
		{
			PetInstance pet = data.pets.get(speciesId);
			if (pet == null || !pet.isFainted())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Persist after external mutation of a PetInstance (xp gain, move equip, egg unlock).
	 */
	public synchronized void petChanged()
	{
		save();
	}
}
