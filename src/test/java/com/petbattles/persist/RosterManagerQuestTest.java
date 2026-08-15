package com.petbattles.persist;

import com.google.gson.Gson;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Quest progression and the remote-battle unlock it gates. "Where's the Remote?" is a data quest;
 * completing it (owning the Remote Battle Device) is what lets the player re-fight already-beaten
 * trainers remotely. Uses the real bundled content plus an in-memory {@link RosterStore}.
 */
public class RosterManagerQuestTest
{
	private static final String REMOTE = "wheres_the_remote";

	private final PetDatabase db = PetDatabase.load(new ContentLoader(new Gson()));

	private RosterManager loadedManager()
	{
		RosterStore store = new RosterStore(null, null)
		{
			private RosterData data = new RosterData();

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

	private int completeStep()
	{
		return db.quest(REMOTE).completeStep();
	}

	@Test
	public void questStartsUnstartedAndRemoteLocked()
	{
		RosterManager roster = loadedManager();
		assertEquals(0, roster.getQuestStep(REMOTE));
		assertFalse("remote battles locked until the device is earned", roster.isRemoteBattlesUnlocked());
	}

	@Test
	public void completingQuestUnlocksRemoteBattles()
	{
		RosterManager roster = loadedManager();
		assertTrue(roster.advanceQuest(REMOTE, completeStep()));
		assertTrue(roster.isRemoteBattlesUnlocked());
	}

	@Test
	public void canRemoteFightOnlyAfterBeatingInPerson()
	{
		RosterManager roster = loadedManager();
		roster.advanceQuest(REMOTE, completeStep()); // owns the device
		assertFalse("un-calibrated trainer: not yet remote-fightable", roster.canRemoteFight("gertrude"));
		roster.recordTrainerDefeated("gertrude");
		assertTrue("beaten in person -> now remote-fightable", roster.canRemoteFight("gertrude"));
	}

	@Test
	public void advanceQuestIsIdempotentAndNeverRegresses()
	{
		RosterManager roster = loadedManager();
		assertTrue(roster.advanceQuest(REMOTE, 1));
		assertFalse("already at (or beyond) the target step: no forward movement",
			roster.advanceQuest(REMOTE, 1));
		assertFalse(roster.advanceQuest(REMOTE, 0));
		assertEquals(1, roster.getQuestStep(REMOTE));
	}
}
