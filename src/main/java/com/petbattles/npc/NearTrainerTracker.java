package com.petbattles.npc;

import com.petbattles.data.PetDatabase;
import com.petbattles.engine.TrainerDef;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.eventbus.Subscribe;

/**
 * "Is the player standing next to a known trainer?" Trainer NPCs are tracked from
 * spawn/despawn events into a small set (no scene scans); proximity is re-evaluated once
 * per game tick against that set. Being near an undefeated trainer is what unlocks the
 * first panel Fight against them, replacing the old in-world right-click entry.
 */
public class NearTrainerTracker
{
	private static final int TRAINER_RADIUS_TILES = 10;

	private final Client client;
	private final Runnable onChanged;
	// Reverse map: in-world NPC id -> trainer id
	private final Map<Integer, String> trainerIdByNpcId = new HashMap<>();
	private final Set<NPC> trainers = new HashSet<>();
	// Read from the Swing EDT by the panel; written on the client thread
	private volatile Set<String> nearTrainerIds = new HashSet<>();

	public NearTrainerTracker(Client client, PetDatabase db, Runnable onChanged)
	{
		this.client = client;
		this.onChanged = onChanged;
		for (TrainerDef trainer : db.allTrainers())
		{
			for (int npcId : trainer.getNpcIds())
			{
				trainerIdByNpcId.put(npcId, trainer.getId());
			}
		}
	}

	/**
	 * Whether the trainer with this id is currently within a few tiles of the player.
	 */
	public boolean isNear(String trainerId)
	{
		return nearTrainerIds.contains(trainerId);
	}

	/**
	 * Snapshot of every trainer id currently within a few tiles of the player. The
	 * challenge overlay needs the whole set (not just a single-id test) to list who
	 * you can battle right now.
	 */
	public Set<String> getNearTrainerIds()
	{
		return new LinkedHashSet<>(nearTrainerIds);
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned e)
	{
		if (trainerIdByNpcId.containsKey(e.getNpc().getId()))
		{
			trainers.add(e.getNpc());
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned e)
	{
		trainers.remove(e.getNpc());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOADING || e.getGameState() == GameState.LOGIN_SCREEN
			|| e.getGameState() == GameState.HOPPING)
		{
			trainers.clear();
			update();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		update();
	}

	private void update()
	{
		Set<String> now = computeNearby();
		if (!now.equals(nearTrainerIds))
		{
			nearTrainerIds = now;
			onChanged.run();
		}
	}

	private Set<String> computeNearby()
	{
		if (trainers.isEmpty() || client.getLocalPlayer() == null)
		{
			return new HashSet<>();
		}
		WorldPoint me = client.getLocalPlayer().getWorldLocation();
		if (me == null)
		{
			return new HashSet<>();
		}
		Set<String> near = new LinkedHashSet<>();
		for (NPC npc : trainers)
		{
			WorldPoint p = npc.getWorldLocation();
			if (p != null && me.distanceTo(p) <= TRAINER_RADIUS_TILES)
			{
				String trainerId = trainerIdByNpcId.get(npc.getId());
				if (trainerId != null)
				{
					near.add(trainerId);
				}
			}
		}
		return near;
	}
}
