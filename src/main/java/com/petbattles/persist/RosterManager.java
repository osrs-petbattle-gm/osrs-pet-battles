package com.petbattles.persist;

import com.petbattles.PetBattlesConfig;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.PetInstance;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.quest.Quest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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
		if (data.questProgress == null)
		{
			data.questProgress = new java.util.LinkedHashMap<>();
		}
		// Drop references to species/trainers that no longer exist in the content
		data.team.removeIf(id -> db.species(id) == null);
		data.ownedSpecies.removeIf(id -> db.species(id) == null);
		data.devUnlocked.removeIf(id -> db.species(id) == null);
		data.defeatedTrainers.removeIf(id -> db.trainer(id) == null);
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
	 * Whether this trainer has been beaten at least once (in-world first win).
	 */
	public synchronized boolean isTrainerDefeated(String trainerId)
	{
		return data.defeatedTrainers.contains(trainerId);
	}

	/**
	 * Record a trainer win; no-op if already recorded.
	 */
	public synchronized void recordTrainerDefeated(String trainerId)
	{
		if (db.trainer(trainerId) != null && data.defeatedTrainers.add(trainerId))
		{
			save();
		}
	}

	/**
	 * The step this quest has reached (0 = not started / in progress). Persistent.
	 */
	public synchronized int getQuestStep(String questId)
	{
		Integer step = data.questProgress.get(questId);
		return step == null ? Quest.STEP_START : step;
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
	 * Whether remote battles are unlocked: either the dev "remote battles" toggle, or the player
	 * has completed "Where's the remote?" (the Remote Battle Device from Ernest). When unlocked,
	 * any trainer can be fought from the panel without standing next to them in the world.
	 */
	public synchronized boolean isRemoteBattlesUnlocked()
	{
		return PetBattlesConfig.devRemoteBattles()
			|| getQuestStep(Quest.WHERES_THE_REMOTE.getId()) >= Quest.STEP_COMPLETE;
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
