package com.petbattles.collection;

import com.petbattles.data.PetDatabase;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.persist.RosterManager;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Keeps the owned-pet set in sync with the real collection log.
 *
 * Sources, all additive (a pet is never revoked):
 *  - Scraping the collection log page when the player opens it (script 2731 redraws
 *    the item grid; obtained items render at full opacity).
 *  - The "New item added to your collection log: X" game message (requires the
 *    player's in-game collection log notification setting).
 *  - The follower tracker also unlocks any pet seen following the player.
 */
@Slf4j
public class CollectionLogSync
{
	private static final Pattern COLLECTION_LOG_ADDITION =
		Pattern.compile("New item added to your collection log: (.+)");

	private final Client client;
	private final PetDatabase db;
	private final RosterManager roster;
	private final Runnable onUnlock;

	public CollectionLogSync(Client client, PetDatabase db, RosterManager roster, Runnable onUnlock)
	{
		this.client = client;
		this.db = db;
		this.roster = roster;
		this.onUnlock = onUnlock;
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != ScriptID.COLLECTION_DRAW_LIST || !roster.isLoaded())
		{
			return;
		}
		Widget contents = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
		if (contents == null)
		{
			return;
		}
		Widget[] items = contents.getDynamicChildren();
		if (items == null)
		{
			return;
		}
		boolean any = false;
		for (Widget item : items)
		{
			int itemId = item.getItemId();
			if (itemId <= 0)
			{
				continue;
			}
			SpeciesDef species = db.speciesByItemId(itemId);
			// Obtained collection log items draw fully opaque; unobtained are faded
			if (species != null && item.getOpacity() == 0 && roster.unlock(species.getId()))
			{
				log.debug("Collection log sync unlocked {}", species.getId());
				announce(species);
				any = true;
			}
		}
		if (any)
		{
			onUnlock.run();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE || !roster.isLoaded())
		{
			return;
		}
		Matcher m = COLLECTION_LOG_ADDITION.matcher(Text.removeTags(event.getMessage()));
		if (!m.matches())
		{
			return;
		}
		String itemName = m.group(1).trim();
		for (SpeciesDef species : db.allSpecies())
		{
			if (species.getName().equalsIgnoreCase(itemName))
			{
				if (roster.unlock(species.getId()))
				{
					announce(species);
					onUnlock.run();
				}
				return;
			}
		}
	}

	private void announce(SpeciesDef species)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			"<col=ff7700>Pet Battles:</col> " + species.getName() + " joined your roster!", null);
	}
}
