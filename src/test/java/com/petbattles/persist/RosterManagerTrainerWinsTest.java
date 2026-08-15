package com.petbattles.persist;

import com.google.gson.Gson;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.Leveling;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The per-trainer win counter that drives the re-fight reward taper
 * ({@link Leveling#repeatFactor(int)}). Uses the real bundled content plus an in-memory
 * {@link RosterStore} so no RuneLite ConfigManager is needed.
 */
public class RosterManagerTrainerWinsTest
{
	private static final String TRAINER = "hans";

	/** An in-memory store seeded with {@code seed}, so a pre-taper save can be replayed. */
	private RosterManager loadedManager(RosterStore.RosterData seed)
	{
		PetDatabase db = PetDatabase.load(new ContentLoader(new Gson()));
		RosterStore store = new RosterStore(null, null)
		{
			private RosterData data = seed;

			@Override
			public RosterData load()
			{
				return data;
			}

			@Override
			public void save(RosterData d)
			{
				this.data = d;
			}
		};
		RosterManager roster = new RosterManager(db, store);
		roster.load();
		return roster;
	}

	private RosterManager loadedManager()
	{
		return loadedManager(new RosterStore.RosterData());
	}

	@Test
	public void winsCountUpFromZeroAndFlagTheFirstDefeat()
	{
		RosterManager roster = loadedManager();
		assertEquals(0, roster.trainerWins(TRAINER));
		assertFalse(roster.isTrainerDefeated(TRAINER));

		roster.recordTrainerDefeated(TRAINER);
		assertEquals(1, roster.trainerWins(TRAINER));
		assertTrue("the first win is what unlocks remote re-fights", roster.isTrainerDefeated(TRAINER));

		roster.recordTrainerDefeated(TRAINER);
		roster.recordTrainerDefeated(TRAINER);
		assertEquals(3, roster.trainerWins(TRAINER));
		assertEquals("other trainers are counted separately", 0, roster.trainerWins("probita"));
	}

	@Test
	public void unknownTrainersAreNotRecorded()
	{
		RosterManager roster = loadedManager();
		roster.recordTrainerDefeated("not_a_trainer");
		assertEquals(0, roster.trainerWins("not_a_trainer"));
		assertFalse(roster.isTrainerDefeated("not_a_trainer"));
	}

	@Test
	public void aSaveFromBeforeTheCounterReadsAsASingleWin()
	{
		// Pre-taper saves only recorded *that* a trainer was beaten. Reading that as one win keeps a
		// long-farmed trainer off the full first-win rate; the next win then counts from there.
		RosterStore.RosterData legacy = new RosterStore.RosterData();
		legacy.defeatedTrainers.add(TRAINER);
		RosterManager roster = loadedManager(legacy);

		assertEquals(1, roster.trainerWins(TRAINER));
		assertTrue(Leveling.repeatFactor(roster.trainerWins(TRAINER)) < 1.0);

		roster.recordTrainerDefeated(TRAINER);
		assertEquals(2, roster.trainerWins(TRAINER));
	}

	@Test
	public void countsForTrainersDroppedFromTheContentAreCleanedUpOnLoad()
	{
		RosterStore.RosterData stale = new RosterStore.RosterData();
		stale.trainerWins.put("removed_trainer", 4);
		stale.defeatedTrainers.add("removed_trainer");

		assertEquals(0, loadedManager(stale).trainerWins("removed_trainer"));
	}
}
