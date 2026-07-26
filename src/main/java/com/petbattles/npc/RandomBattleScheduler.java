package com.petbattles.npc;

import com.petbattles.PetBattlesConfig;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.TrainerDef;
import com.petbattles.persist.RosterManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * Periodic "Random Battle" cadence, modelled on OSRS random-event timing. On a loose interval,
 * when the player is idle (no battle running) with a team that can fight, one random-event trainer
 * ({@link TrainerDef#isRandomEvent()}) is surfaced as a pending challenge: a chat toast invites the
 * player to open the panel and fight, and that trainer becomes fightable there without being
 * in-world near it. Never a forced interrupt and never anything server-side (AGENTS.md / roadmap §1).
 *
 * Tick-driven — it counts game ticks to a randomised threshold, so there is no executor or
 * scheduled future to cancel on shutdown; unregistering it from the event bus is enough.
 */
@Slf4j
public class RandomBattleScheduler
{
	// ~15-30 min at 0.6s/tick in production; a handful of seconds in dev so the cadence is testable.
	private static final int MIN_TICKS = 1500;
	private static final int MAX_TICKS = 3000;
	private static final int DEV_MIN_TICKS = 20;
	private static final int DEV_MAX_TICKS = 60;

	private final Client client;
	private final RosterManager roster;
	private final BooleanSupplier battleActive;
	private final Runnable onChange;
	private final Random rng = new Random();
	private final List<TrainerDef> eligible = new ArrayList<>();

	private int ticksUntilNext;
	private volatile String pendingTrainerId;

	public RandomBattleScheduler(Client client, PetDatabase db, RosterManager roster,
		BooleanSupplier battleActive, Runnable onChange)
	{
		this.client = client;
		this.roster = roster;
		this.battleActive = battleActive;
		this.onChange = onChange;
		for (TrainerDef t : db.allTrainers())
		{
			// A random "wild challenger" must be an accessible fight, so the pool is EASY-only —
			// a flagged HARD boss would spring on an idle low-level player (roadmap/plan §1.2).
			if (t.isRandomEvent() && t.getDifficulty() == TrainerDef.Difficulty.EASY)
			{
				eligible.add(t);
			}
		}
		armTimer();
	}

	/**
	 * The trainer id currently offered as a random challenge, or null.
	 */
	public String getPending()
	{
		return pendingTrainerId;
	}

	/**
	 * Whether this trainer is the current random challenge (so the panel lets you fight it now).
	 */
	public boolean isPending(String trainerId)
	{
		return pendingTrainerId != null && pendingTrainerId.equals(trainerId);
	}

	/**
	 * Consume/cancel the pending challenge (e.g. once its battle has started) and re-arm the timer.
	 */
	public void clearPending()
	{
		if (pendingTrainerId != null)
		{
			pendingTrainerId = null;
			armTimer();
			onChange.run();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (eligible.isEmpty() || !roster.isLoaded())
		{
			return;
		}
		// Don't stack challenges or interrupt a fight; hold the timer until the player is idle with
		// a team that can actually take a battle.
		if (pendingTrainerId != null || battleActive.getAsBoolean()
			|| roster.getTeam().isEmpty() || !roster.teamCanFight())
		{
			return;
		}
		if (--ticksUntilNext > 0)
		{
			return;
		}
		TrainerDef choice = chooseChallenge(eligible, rng);
		if (choice == null)
		{
			armTimer();
			return;
		}
		pendingTrainerId = choice.getId();
		log.debug("Random Battle challenge: {}", choice.getId());
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			"<col=ff7700>Pet Battles:</col> A wild challenger appears! Open Pet Battles to fight "
				+ choice.getName() + ".", null);
		onChange.run();
	}

	/**
	 * Pick a random eligible trainer (pure; exposed for testing). Returns null if none are eligible.
	 */
	static TrainerDef chooseChallenge(List<TrainerDef> eligible, Random rng)
	{
		return eligible.isEmpty() ? null : eligible.get(rng.nextInt(eligible.size()));
	}

	private void armTimer()
	{
		int min = PetBattlesConfig.DEV ? DEV_MIN_TICKS : MIN_TICKS;
		int max = PetBattlesConfig.DEV ? DEV_MAX_TICKS : MAX_TICKS;
		ticksUntilNext = min + rng.nextInt(max - min + 1);
	}
}
