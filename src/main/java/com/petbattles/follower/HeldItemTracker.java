package com.petbattles.follower;

import com.petbattles.data.PetDatabase;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.persist.RosterManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Keeps possession-gated pets ({@link SpeciesDef#isItemUnlock()}, i.e. the OSRS cat) in
 * sync with what the player actually owns in-game:
 *  - unlock when the pet item is seen in the inventory or bank (holding it is proof of
 *    ownership the collection log never records for these pets),
 *  - revoke when a matching game message shows the pet was traded away (e.g. handing a
 *    grown cat over for death runes).
 * The follower unlock path is handled separately by {@link FollowerTracker} via npc ids.
 */
@Slf4j
public class HeldItemTracker
{
	private final Client client;
	private final PetDatabase db;
	private final RosterManager roster;
	private final Runnable onChange;
	// Pre-compiled trade-away message patterns, by species id
	private final Map<String, Pattern> tradeInPatterns = new LinkedHashMap<>();

	public HeldItemTracker(Client client, PetDatabase db, RosterManager roster, Runnable onChange)
	{
		this.client = client;
		this.db = db;
		this.roster = roster;
		this.onChange = onChange;
		for (SpeciesDef species : db.allSpecies())
		{
			if (species.getTradeInMessage() != null && !species.getTradeInMessage().isEmpty())
			{
				tradeInPatterns.put(species.getId(),
					Pattern.compile(species.getTradeInMessage(), Pattern.CASE_INSENSITIVE));
			}
		}
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

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE || !roster.isLoaded()
			|| tradeInPatterns.isEmpty())
		{
			return;
		}
		String message = Text.removeTags(event.getMessage());
		for (Map.Entry<String, Pattern> entry : tradeInPatterns.entrySet())
		{
			if (entry.getValue().matcher(message).find() && roster.removeOwnership(entry.getKey()))
			{
				SpeciesDef species = db.species(entry.getKey());
				log.debug("Trade-in revoked {}", entry.getKey());
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"<col=ff7700>Pet Battles:</col> " + species.getName() + " left your roster.", null);
				onChange.run();
				return;
			}
		}
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
			if (species == null)
			{
				continue;
			}
			// Only possession-gated pets unlock this way; collection-log pets keep to the log
			if (species.isItemUnlock() && roster.unlock(species.getId()))
			{
				roster.getOrCreatePet(species.getId());
				log.debug("Held-item sync unlocked {}", species.getId());
				announce(species);
				any = true;
			}
			// Holding a metamorphosis form's item is proof that form is active — flip an owned
			// pet's variant to match (no-op if not owned or already on that form). Read-only.
			String variantId = db.variantByItemId(itemId);
			if (variantId != null && roster.setActiveVariant(species.getId(), variantId))
			{
				log.debug("Held-item sync set {} variant {}", species.getId(), variantId);
				any = true;
			}
		}
		if (any)
		{
			onChange.run();
		}
	}

	private void announce(SpeciesDef species)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			"<col=ff7700>Pet Battles:</col> " + species.getName() + " joined your roster!", null);
	}
}
