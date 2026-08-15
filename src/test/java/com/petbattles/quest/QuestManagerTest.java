package com.petbattles.quest;

import com.google.gson.Gson;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import com.petbattles.item.Item;
import com.petbattles.persist.RosterManager;
import com.petbattles.persist.RosterStore;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The data-described quest framework, driven through the whole capstone "A Series of Fortunate
 * Events" plus its prerequisite "Where's the Remote?". Uses the real bundled content plus an
 * in-memory {@link RosterStore} so no RuneLite ConfigManager is needed.
 */
public class QuestManagerTest
{
	private static final String REMOTE = "wheres_the_remote";
	private static final String CAPSTONE = "series_of_fortunate_events";
	private static final String GIMBLEWAP = "ambassador_gimblewap";
	private static final String WISE_OLD_MAN = "wise_old_man";
	private static final String SIR_TIFFY = "sir_tiffy";
	private static final String LUMBRIDGE_GUIDE = "lumbridge_guide";
	private static final String SWENSEN = "swensen";

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

	private QuestManager questManager(RosterManager roster)
	{
		return new QuestManager(PetDatabase.load(new ContentLoader(new Gson())), roster);
	}

	/** Mark the prerequisite quest complete so the capstone is available. */
	private void unlockCapstone(RosterManager roster, QuestManager quests)
	{
		roster.advanceQuest(REMOTE, quests.quest(REMOTE).completeStep());
	}

	/** Perform the correct clearing action for whatever chapter a quest is currently on. */
	private void clearCurrentChapter(QuestManager quests, String questId)
	{
		QuestDef.Chapter ch = quests.currentChapter(questId);
		if (ch == null)
		{
			return;
		}
		if (ch.isBattle())
		{
			quests.onTrainerDefeated(ch.getBattleTrainer());
		}
		else if (ch.isHunt())
		{
			for (String member : ch.getBattlePool())
			{
				quests.onTrainerDefeated(member);
			}
		}
		else
		{
			quests.completeTalk(questId);
		}
	}

	/** Drive an available capstone forward until it sits on {@code targetStep}. */
	private void driveCapstoneToStep(RosterManager roster, QuestManager quests, int targetStep)
	{
		unlockCapstone(roster, quests);
		int guard = 0;
		while (roster.getQuestStep(CAPSTONE) < targetStep && guard++ < 50)
		{
			clearCurrentChapter(quests, CAPSTONE);
		}
		assertEquals("driven to the expected capstone step", targetStep, roster.getQuestStep(CAPSTONE));
	}

	@Test
	public void remoteQuestStartsWithHansTalkChapter()
	{
		RosterManager roster = loadedManager();
		QuestManager quests = questManager(roster);

		QuestDef.Chapter ch = quests.currentChapter(REMOTE);
		assertNotNull(ch);
		assertEquals(0, ch.getStep());
		assertFalse("Hans is a talk chapter, not a battle", ch.isBattle());
		assertEquals("hans", ch.getGateTrainer());
	}

	@Test
	public void beatingTheProfessorCompletesTheRemoteQuest()
	{
		RosterManager roster = loadedManager();
		QuestManager quests = questManager(roster);
		assertFalse(roster.isRemoteBattlesUnlocked());

		quests.completeTalk(REMOTE);                       // Hans -> advance to the Professor
		assertEquals(1, roster.getQuestStep(REMOTE));
		QuestManager.DefeatResult r = quests.onTrainerDefeated("professor_oddenstein");
		assertNotNull(r);
		assertEquals("Remote Battle Device", r.getRewardItemName());
		assertTrue("the device unlocks remote battles", roster.isRemoteBattlesUnlocked());
		assertTrue(quests.isComplete(REMOTE));
	}

	@Test
	public void capstoneLockedUntilRemoteQuestComplete()
	{
		RosterManager roster = loadedManager();
		QuestManager quests = questManager(roster);

		assertFalse("locked before the prerequisite is done", quests.isAvailable(quests.quest(CAPSTONE)));
		unlockCapstone(roster, quests);
		assertTrue("unlocked once the prerequisite is complete", quests.isAvailable(quests.quest(CAPSTONE)));
	}

	@Test
	public void offChapterOrLockedTrainerDefeatDoesNothing()
	{
		RosterManager roster = loadedManager();
		QuestManager quests = questManager(roster);
		// Capstone locked (no prerequisite): beating a story trainer must not progress it.
		assertNull(quests.onTrainerDefeated("draynor_guard"));

		driveCapstoneToStep(roster, quests, 1); // on the guard battle chapter now
		// A trainer that isn't this chapter's target does nothing.
		assertNull(quests.onTrainerDefeated("martin_thwait"));
		assertEquals(1, roster.getQuestStep(CAPSTONE));
	}

	@Test
	public void battleChaptersGrantHeldItemsAndAdvance()
	{
		RosterManager roster = loadedManager();
		QuestManager quests = questManager(roster);

		driveCapstoneToStep(roster, quests, 1);
		assertEquals("Stick", quests.onTrainerDefeated("draynor_guard").getRewardItemName());
		assertEquals(1, roster.itemCount("stick"));

		driveCapstoneToStep(roster, quests, 3);
		assertEquals("Amulet of the Rogue", quests.onTrainerDefeated("martin_thwait").getRewardItemName());
		assertEquals(1, roster.itemCount("amulet_of_the_rogue"));
	}

	@Test
	public void talkChapterAdvancesOnCompleteTalk()
	{
		RosterManager roster = loadedManager();
		QuestManager quests = questManager(roster);
		driveCapstoneToStep(roster, quests, 4); // Audience with the King (a talk chapter)

		assertFalse(quests.currentChapter(CAPSTONE).isBattle());
		assertTrue(quests.completeTalk(CAPSTONE));
		assertEquals(5, roster.getQuestStep(CAPSTONE));
	}

	@Test
	public void gimblewapAwardsTheSealedEnvelope()
	{
		RosterManager roster = loadedManager();
		QuestManager quests = questManager(roster);
		driveCapstoneToStep(roster, quests, 5);
		assertFalse(roster.ownsItem(Item.SEALED_ENVELOPE));

		QuestManager.DefeatResult r = quests.onTrainerDefeated(GIMBLEWAP);
		assertEquals("Sealed Envelope", r.getRewardItemName());
		assertTrue(roster.ownsItem(Item.SEALED_ENVELOPE));
		assertEquals(6, roster.getQuestStep(CAPSTONE));
	}

	@Test
	public void huntNeedsThreeDistinctWinsAndIgnoresRepeats()
	{
		RosterManager roster = loadedManager();
		QuestManager quests = questManager(roster);
		driveCapstoneToStep(roster, quests, 6); // the white-beard hunt

		assertNotNull(quests.onTrainerDefeated(SIR_TIFFY));   // progress
		assertEquals(6, roster.getQuestStep(CAPSTONE));
		assertNull(quests.onTrainerDefeated(SIR_TIFFY));      // repeat: no double-count
		assertEquals(1, quests.countHuntBeaten(quests.quest(CAPSTONE), quests.currentChapter(CAPSTONE)));
		assertNotNull(quests.onTrainerDefeated(LUMBRIDGE_GUIDE));
		assertEquals(6, roster.getQuestStep(CAPSTONE));
		assertNotNull(quests.onTrainerDefeated(SWENSEN));     // third distinct -> clears
		assertEquals(7, roster.getQuestStep(CAPSTONE));
	}

	@Test
	public void finaleAwardsBluePartyHatAndCompletes()
	{
		RosterManager roster = loadedManager();
		QuestManager quests = questManager(roster);
		driveCapstoneToStep(roster, quests, 7);
		assertFalse(roster.ownsItem(Item.BLUE_PARTY_HAT));
		long before = roster.getCoins();

		QuestManager.DefeatResult r = quests.onTrainerDefeated(WISE_OLD_MAN);
		assertEquals("Blue party hat", r.getRewardItemName());
		assertEquals(before + 500, roster.getCoins());
		assertTrue(roster.ownsItem(Item.BLUE_PARTY_HAT));
		assertTrue(quests.isComplete(CAPSTONE));
	}

	@Test
	public void wholeArcPlaysThroughToComplete()
	{
		RosterManager roster = loadedManager();
		QuestManager quests = questManager(roster);
		driveCapstoneToStep(roster, quests, 8);
		assertTrue(quests.isComplete(CAPSTONE));
		assertTrue(roster.ownsItem(Item.SEALED_ENVELOPE));
		assertTrue(roster.ownsItem(Item.BLUE_PARTY_HAT));
	}
}
