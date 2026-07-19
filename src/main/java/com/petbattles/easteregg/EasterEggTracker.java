package com.petbattles.easteregg;

import com.petbattles.data.PetDatabase;
import com.petbattles.engine.EasterEggDef;
import com.petbattles.engine.MoveDef;
import com.petbattles.engine.PetInstance;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.follower.FollowerTracker;
import com.petbattles.persist.RosterManager;
import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Watches for hidden move-unlock triggers performed while the pet is following:
 * EMOTE (player animation), LOCATION (map region), STAT (xp gain in a skill).
 */
public class EasterEggTracker
{
	private static final int LOCATION_CHECK_INTERVAL_TICKS = 5;

	private final Client client;
	private final PetDatabase db;
	private final RosterManager roster;
	private final FollowerTracker follower;
	private final Runnable onUnlock;

	private final Map<Skill, Long> lastSkillXp = new EnumMap<>(Skill.class);
	private int tickCount;

	public EasterEggTracker(Client client, PetDatabase db, RosterManager roster,
		FollowerTracker follower, Runnable onUnlock)
	{
		this.client = client;
		this.db = db;
		this.roster = roster;
		this.follower = follower;
		this.onUnlock = onUnlock;
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		Player local = client.getLocalPlayer();
		if (event.getActor() != local || local == null)
		{
			return;
		}
		int animId = local.getAnimation();
		if (animId <= 0)
		{
			return;
		}
		forEachActiveEgg(EasterEggDef.TriggerKind.EMOTE, (species, pet, egg) ->
		{
			EasterEggDef.Trigger t = egg.getTrigger();
			if (t.getAnimId() == animId
				&& (t.getRegionId() <= 0 || t.getRegionId() == currentRegion()))
			{
				unlock(species, pet, egg);
			}
		});
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (++tickCount % LOCATION_CHECK_INTERVAL_TICKS != 0)
		{
			return;
		}
		int region = currentRegion();
		if (region <= 0)
		{
			return;
		}
		forEachActiveEgg(EasterEggDef.TriggerKind.LOCATION, (species, pet, egg) ->
		{
			if (egg.getTrigger().getRegionId() == region)
			{
				unlock(species, pet, egg);
			}
		});
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		Long previous = lastSkillXp.put(skill, (long) event.getXp());
		// First observation after login is baseline, not a gain
		if (previous == null || event.getXp() <= previous)
		{
			return;
		}
		forEachActiveEgg(EasterEggDef.TriggerKind.STAT, (species, pet, egg) ->
		{
			if (skill.name().equalsIgnoreCase(egg.getTrigger().getSkill()))
			{
				unlock(species, pet, egg);
			}
		});
	}

	/**
	 * Reset xp baselines (on logout) so relogging doesn't count the initial sync as gains.
	 */
	public void resetBaselines()
	{
		lastSkillXp.clear();
	}

	private interface EggVisitor
	{
		void visit(SpeciesDef species, PetInstance pet, EasterEggDef egg);
	}

	private void forEachActiveEgg(EasterEggDef.TriggerKind kind, EggVisitor visitor)
	{
		String speciesId = follower.getActiveSpeciesId();
		if (speciesId == null || !roster.isLoaded())
		{
			return;
		}
		SpeciesDef species = db.species(speciesId);
		if (species == null || species.getEasterEggs().isEmpty())
		{
			return;
		}
		PetInstance pet = roster.getOrCreatePet(speciesId);
		if (pet == null)
		{
			return;
		}
		for (EasterEggDef egg : species.getEasterEggs())
		{
			if (egg.getTrigger() != null
				&& egg.getTrigger().getKind() == kind
				&& !pet.getUnlockedEggMoves().contains(egg.getMove()))
			{
				visitor.visit(species, pet, egg);
			}
		}
	}

	private void unlock(SpeciesDef species, PetInstance pet, EasterEggDef egg)
	{
		MoveDef move = db.move(egg.getMove());
		if (move == null)
		{
			return;
		}
		pet.getUnlockedEggMoves().add(egg.getMove());
		if (pet.getEquippedMoves().size() < PetInstance.MAX_EQUIPPED_MOVES)
		{
			pet.equipMove(egg.getMove());
		}
		roster.petChanged();
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			"<col=ff7700>Pet Battles:</col> ✦ " + species.getName()
				+ " discovered a secret move: " + move.getName() + "!", null);
		onUnlock.run();
	}

	private int currentRegion()
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return -1;
		}
		WorldPoint wp = local.getWorldLocation();
		return wp == null ? -1 : wp.getRegionID();
	}
}
