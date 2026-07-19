package com.petbattles.npc;

import com.petbattles.data.PetDatabase;
import com.petbattles.engine.TrainerDef;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.api.events.MenuOpened;

/**
 * Adds a client-side "Challenge" right-click entry to trainer NPCs in the world.
 * The entry is MenuAction.RUNELITE with an onClick handler — it opens our battle
 * overlay and sends nothing to the server. Beating a trainer in-world is what
 * unlocks remote re-fights from the panel.
 */
public class NpcTrainerTracker
{
	private final Client client;
	private final Consumer<String> challengeAction;
	private final Map<Integer, String> trainerIdByNpcId = new HashMap<>();

	public NpcTrainerTracker(Client client, PetDatabase db, Consumer<String> challengeAction)
	{
		this.client = client;
		this.challengeAction = challengeAction;
		for (TrainerDef trainer : db.allTrainers())
		{
			for (int npcId : trainer.getNpcIds())
			{
				trainerIdByNpcId.put(npcId, trainer.getId());
			}
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		for (MenuEntry entry : event.getMenuEntries())
		{
			NPC npc = entry.getNpc();
			if (npc == null)
			{
				continue;
			}
			String trainerId = trainerIdByNpcId.get(npc.getId());
			if (trainerId == null)
			{
				continue;
			}
			client.getMenu().createMenuEntry(-1)
				.setOption("Challenge")
				.setTarget(entry.getTarget())
				.setType(MenuAction.RUNELITE)
				.onClick(e -> challengeAction.accept(trainerId));
			return;
		}
	}
}
