package com.petbattles.follower;

import com.petbattles.data.PetDatabase;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.persist.RosterManager;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Tracks which pet is currently following the player.
 *
 * Primary signal: varp 447 (FOLLOWER_NPC) — high 16 bits are the follower's NPC id.
 * Fallback: an NpcSpawned whose id is a known pet and which is interacting with us.
 * A pet seen following the player is also unlocked (you can't have a follower you
 * don't own), which makes this a live unlock source for fresh drops.
 */
@Slf4j
public class FollowerTracker
{
	private final Client client;
	private final PetDatabase db;
	private final RosterManager roster;
	private final Runnable onChange;

	private String activeSpeciesId;

	public FollowerTracker(Client client, PetDatabase db, RosterManager roster, Runnable onChange)
	{
		this.client = client;
		this.db = db;
		this.roster = roster;
		this.onChange = onChange;
	}

	/**
	 * Species id of the pet currently following the player, or null.
	 */
	public String getActiveSpeciesId()
	{
		return activeSpeciesId;
	}

	/**
	 * Re-read the follower varp (called after login/roster load).
	 */
	public void refresh()
	{
		apply(client.getVarpValue(VarPlayerID.FOLLOWER_NPC));
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarpId() == VarPlayerID.FOLLOWER_NPC)
		{
			apply(event.getValue());
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		// Fallback path: catches the fresh-drop case before any varp read
		NPC npc = event.getNpc();
		SpeciesDef species = db.speciesByNpcId(npc.getId());
		if (species != null && npc.getInteracting() == client.getLocalPlayer())
		{
			setActive(species, db.variantByNpcId(npc.getId()));
		}
	}

	private void apply(int varpValue)
	{
		if (varpValue <= 0)
		{
			if (activeSpeciesId != null)
			{
				activeSpeciesId = null;
				onChange.run();
			}
			return;
		}
		int npcId = varpValue >>> 16;
		SpeciesDef species = db.speciesByNpcId(npcId);
		if (species != null)
		{
			setActive(species, db.variantByNpcId(npcId));
		}
	}

	private void setActive(SpeciesDef species, String variantId)
	{
		boolean changed = false;
		if (roster.isLoaded())
		{
			// Owning proof: it's following you
			roster.unlock(species.getId());
			roster.getOrCreatePet(species.getId());
			// The follower npc id carries the active metamorphosis form; record it. Done even when
			// the species is unchanged, so metamorphosing your current follower flips the form.
			changed = roster.setActiveVariant(species.getId(), variantId);
		}
		if (!species.getId().equals(activeSpeciesId))
		{
			activeSpeciesId = species.getId();
			log.debug("Active follower pet: {} (variant {})", species.getId(), variantId);
			changed = true;
		}
		if (changed)
		{
			onChange.run();
		}
	}
}
