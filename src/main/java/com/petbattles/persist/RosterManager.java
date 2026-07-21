package com.petbattles.persist;

import com.petbattles.PetBattlesConfig;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.PetInstance;
import com.petbattles.engine.SpeciesDef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Runtime roster state: which species are owned, per-pet progression, and the battle team.
 * All mutations save through the store immediately (the blob is small).
 */
public class RosterManager
{
	public static final int MAX_TEAM_SIZE = 3;

	private final PetDatabase db;
	private final PetBattlesConfig config;
	private final RosterStore store;
	private RosterStore.RosterData data = new RosterStore.RosterData();
	private boolean loaded;
	// Team composition (add/remove) is bank-gated; reordering is not. The gate is
	// re-checked here so a stale panel can't edit the team away from a bank.
	private volatile BooleanSupplier teamEditGate = () -> true;

	public RosterManager(PetDatabase db, PetBattlesConfig config, RosterStore store)
	{
		this.db = db;
		this.config = config;
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
		// Drop references to species/trainers that no longer exist in the content
		data.team.removeIf(id -> db.species(id) == null);
		data.ownedSpecies.removeIf(id -> db.species(id) == null);
		data.devUnlocked.removeIf(id -> db.species(id) == null);
		data.defeatedTrainers.removeIf(id -> db.trainer(id) == null);
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
		return config.devUnlockAll()
			|| data.ownedSpecies.contains(speciesId)
			|| (config.devSelectLockedPets() && data.devUnlocked.contains(speciesId));
	}

	/**
	 * Whether the "select locked pets" testing option is currently enabled.
	 */
	public boolean isDevSelectEnabled()
	{
		return config.devSelectLockedPets();
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
	 * Whether the dev "remote battles" toggle allows fighting any trainer from the panel.
	 */
	public boolean isDevRemoteBattlesEnabled()
	{
		return config.devRemoteBattles();
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
