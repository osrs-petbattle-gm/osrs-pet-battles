package com.petbattles.follower;

import com.petbattles.data.PetDatabase;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.persist.RosterManager;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Unlocks possession-gated pets ({@link SpeciesDef#isItemUnlock()}, i.e. the OSRS cat)
 * when the player is seen holding the pet item in their inventory or bank. Holding the
 * item is proof of ownership the collection log never records for these pets. The bank
 * side fires whenever the bank container updates (i.e. on opening it). The follower path
 * is handled separately by {@link FollowerTracker} via the species' npc ids.
 */
@Slf4j
public class HeldItemTracker
{
	private final Client client;
	private final PetDatabase db;
	private final RosterManager roster;
	private final Runnable onUnlock;

	public HeldItemTracker(Client client, PetDatabase db, RosterManager roster, Runnable onUnlock)
	{
		this.client = client;
		this.db = db;
		this.roster = roster;
		this.onUnlock = onUnlock;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int id = event.getContainerId();
		if ((id != InventoryID.INV && id != InventoryID.BANK) || !roster.isLoaded())
		{
			return;
		}
		scan(event.getItemContainer());
	}

	private void scan(ItemContainer container)
	{
		if (container == null)
		{
			return;
		}
		boolean any = false;
		for (Item item : container.getItems())
		{
			int itemId = item.getId();
			if (itemId <= 0)
			{
				continue;
			}
			SpeciesDef species = db.speciesByItemId(itemId);
			// Only possession-gated pets unlock this way; collection-log pets keep to the log
			if (species != null && species.isItemUnlock() && roster.unlock(species.getId()))
			{
				roster.getOrCreatePet(species.getId());
				log.debug("Held-item sync unlocked {}", species.getId());
				announce(species);
				any = true;
			}
		}
		if (any)
		{
			onUnlock.run();
		}
	}

	private void announce(SpeciesDef species)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			"<col=ff7700>Pet Battles:</col> " + species.getName() + " joined your roster!", null);
	}
}
