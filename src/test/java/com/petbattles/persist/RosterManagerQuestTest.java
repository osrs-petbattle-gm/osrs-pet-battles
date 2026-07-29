package com.petbattles.persist;

import com.google.gson.Gson;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import com.petbattles.quest.Quest;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Quest progression and the remote-battle unlock it gates. Uses the real bundled content plus an
 * in-memory {@link RosterStore} so no RuneLite ConfigManager is needed.
 */
public class RosterManagerQuestTest
{
	private RosterManager loadedManager()
	{
		PetDatabase db = PetDatabase.load(new ContentLoader(new Gson()));
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

	@Test
	public void questStartsUnstartedAndRemoteLocked()
	{
		RosterManager roster = loadedManager();
		assertEquals(Quest.STEP_START, roster.getQuestStep(Quest.WHERES_THE_REMOTE.getId()));
		assertFalse("remote battles locked until the quest is done", roster.isRemoteBattlesUnlocked());
	}

	@Test
	public void completingQuestUnlocksRemoteBattles()
	{
		RosterManager roster = loadedManager();
		assertTrue(roster.advanceQuest(Quest.WHERES_THE_REMOTE.getId(), Quest.STEP_COMPLETE));
		assertEquals(Quest.STEP_COMPLETE, roster.getQuestStep(Quest.WHERES_THE_REMOTE.getId()));
		assertTrue(roster.isRemoteBattlesUnlocked());
	}

	@Test
	public void advanceQuestIsIdempotentAndNeverRegresses()
	{
		RosterManager roster = loadedManager();
		assertTrue(roster.advanceQuest(Quest.WHERES_THE_REMOTE.getId(), Quest.STEP_COMPLETE));
		// Already at (or beyond) the target step: no forward movement.
		assertFalse(roster.advanceQuest(Quest.WHERES_THE_REMOTE.getId(), Quest.STEP_COMPLETE));
		assertFalse(roster.advanceQuest(Quest.WHERES_THE_REMOTE.getId(), Quest.STEP_START));
		assertEquals(Quest.STEP_COMPLETE, roster.getQuestStep(Quest.WHERES_THE_REMOTE.getId()));
	}
}
