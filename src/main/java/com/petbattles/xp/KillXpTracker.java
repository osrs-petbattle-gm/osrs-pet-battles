package com.petbattles.xp;

import com.petbattles.PetBattlesConfig;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.LearnsetEntry;
import com.petbattles.engine.Leveling;
import com.petbattles.engine.MoveDef;
import com.petbattles.engine.PetInstance;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.follower.FollowerTracker;
import com.petbattles.persist.RosterManager;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.client.eventbus.Subscribe;

/**
 * Awards XP to the active follower pet when the player kills an NPC.
 * Kill attribution: NPCs we've hitsplat recently (within an expiry window) count as ours.
 */
public class KillXpTracker
{
	private static final int ATTRIBUTION_WINDOW_TICKS = 50;

	private final Client client;
	private final PetDatabase db;
	private final RosterManager roster;
	private final FollowerTracker follower;
	private final PetBattlesConfig config;
	private final Runnable onXpGained;

	private final Map<Integer, Integer> recentTargets = new HashMap<>(); // npc index -> last hit tick
	private int tickCount;

	public KillXpTracker(Client client, PetDatabase db, RosterManager roster,
		FollowerTracker follower, PetBattlesConfig config, Runnable onXpGained)
	{
		this.client = client;
		this.db = db;
		this.roster = roster;
		this.follower = follower;
		this.config = config;
		this.onXpGained = onXpGained;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		tickCount++;
		if (tickCount % 25 == 0)
		{
			Iterator<Map.Entry<Integer, Integer>> it = recentTargets.entrySet().iterator();
			while (it.hasNext())
			{
				if (tickCount - it.next().getValue() > ATTRIBUTION_WINDOW_TICKS)
				{
					it.remove();
				}
			}
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Actor actor = event.getActor();
		if (actor instanceof NPC && event.getHitsplat().isMine())
		{
			recentTargets.put(((NPC) actor).getIndex(), tickCount);
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();
		if (!(actor instanceof NPC))
		{
			return;
		}
		NPC npc = (NPC) actor;
		Integer lastHit = recentTargets.remove(npc.getIndex());
		if (lastHit == null || tickCount - lastHit > ATTRIBUTION_WINDOW_TICKS)
		{
			return;
		}

		String speciesId = follower.getActiveSpeciesId();
		if (speciesId == null || !roster.isLoaded())
		{
			return;
		}
		SpeciesDef species = db.species(speciesId);
		PetInstance pet = roster.getOrCreatePet(speciesId);
		if (species == null || pet == null || pet.getLevel() >= Leveling.MAX_LEVEL)
		{
			return;
		}

		boolean typeMatch = isTypeMatch(species, npc);
		long xp = Leveling.killXp(npc.getCombatLevel(), typeMatch);
		int oldLevel = pet.getLevel();
		int gained = pet.addXp(xp);
		roster.petChanged();

		if (gained > 0)
		{
			int newLevel = pet.getLevel();
			message(species.getName() + " grew to level " + newLevel + "!"
				+ (typeMatch ? " (home-turf bonus)" : ""));
			announceNewMoves(pet, species, oldLevel, newLevel);
			onXpGained.run();
		}
		else if (typeMatch && config.showXpMessages())
		{
			message(species.getName() + " gained " + xp + " XP (home-turf bonus)!");
		}
	}

	private static boolean isTypeMatch(SpeciesDef species, NPC npc)
	{
		String name = npc.getName();
		if (name == null)
		{
			return false;
		}
		String lower = name.toLowerCase();
		for (String tag : species.getNpcXpTags())
		{
			if (lower.contains(tag.toLowerCase()))
			{
				return true;
			}
		}
		return false;
	}

	private void announceNewMoves(PetInstance pet, SpeciesDef species, int oldLevel, int newLevel)
	{
		for (LearnsetEntry entry : species.getLearnset())
		{
			if (entry.getLevel() > oldLevel && entry.getLevel() <= newLevel)
			{
				MoveDef move = db.move(entry.getMove());
				if (move == null)
				{
					continue;
				}
				if (pet.getEquippedMoves().size() < PetInstance.MAX_EQUIPPED_MOVES)
				{
					pet.equipMove(entry.getMove());
					roster.petChanged();
				}
				message(species.getName() + " learned " + move.getName() + "!");
			}
		}
	}

	private void message(String text)
	{
		if (config.showXpMessages())
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"<col=ff7700>Pet Battles:</col> " + text, null);
		}
	}
}
