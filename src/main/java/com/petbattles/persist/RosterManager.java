package com.petbattles.persist;

import com.petbattles.PetBattlesConfig;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.PetInstance;
import com.petbattles.engine.SpeciesDef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

	public RosterManager(PetDatabase db, PetBattlesConfig config, RosterStore store)
	{
		this.db = db;
		this.config = config;
		this.store = store;
	}

	public void load()
	{
		data = store.load();
		// Drop references to species that no longer exist in the content
		data.team.removeIf(id -> db.species(id) == null);
		data.ownedSpecies.removeIf(id -> db.species(id) == null);
		loaded = true;
	}

	public void unload()
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

	private void save()
	{
		if (loaded)
		{
			store.save(data);
		}
	}

	public boolean isOwned(String speciesId)
	{
		return config.devUnlockAll() || data.ownedSpecies.contains(speciesId);
	}

	/**
	 * Mark a species as owned (from collection log sync or a live drop). Additive only.
	 * Returns true if this is a new unlock.
	 */
	public boolean unlock(String speciesId)
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
	public PetInstance getOrCreatePet(String speciesId)
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

	public PetInstance getPet(String speciesId)
	{
		return data.pets.get(speciesId);
	}

	public List<String> getTeam()
	{
		return Collections.unmodifiableList(data.team);
	}

	public boolean addToTeam(String speciesId)
	{
		if (data.team.contains(speciesId) || data.team.size() >= MAX_TEAM_SIZE || !isOwned(speciesId))
		{
			return false;
		}
		data.team.add(speciesId);
		getOrCreatePet(speciesId);
		save();
		return true;
	}

	public boolean removeFromTeam(String speciesId)
	{
		boolean removed = data.team.remove(speciesId);
		if (removed)
		{
			save();
		}
		return removed;
	}

	/**
	 * Persist after external mutation of a PetInstance (xp gain, move equip, egg unlock).
	 */
	public void petChanged()
	{
		save();
	}
}
