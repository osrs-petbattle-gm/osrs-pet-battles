package com.petbattles.quest;

import com.google.gson.Gson;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import com.petbattles.item.Item;
import com.petbattles.persist.RosterManager;
import com.petbattles.persist.RosterStore;
import java.util.ArrayList;
import java.util.List;
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

	// No shipped chapter is a hunt any more, but QuestDef/QuestManager still support one, so the hunt
	// engine is covered against this fixture rather than against quests.json. Ch.0 is a plain talk so
	// the hunt sits at step 1, proving the counter isn't tied to being the first chapter.
	private static final String HUNT_QUEST = "hunt_fixture";
	private static final String SUSPECT_A = "greybeard_a";
	private static final String SUSPECT_B = "greybeard_b";
	private static final String SUSPECT_C = "greybeard_c";
	private static final String HUNT_QUEST_JSON = "{"
		+ "'id':'" + HUNT_QUEST + "','title':'Hunt Fixture','chapters':["
		+ "{'step':0,'title':'Opening','intro':[{'text':'A rumour of greybeards.'}]},"
		+ "{'step':1,'title':'The Hunt',"
		+ "'battlePool':['" + SUSPECT_A + "','" + SUSPECT_B + "','" + SUSPECT_C + "'],"
		+ "'battlesRequired':3,"
		+ "'suspects':[{'trainer':'" + SUSPECT_C + "',"
		+ "'concede':[{'speaker':'" + SUSPECT_C + "','text':'Not I.'}],"
		+ "'finale':[{'speaker':'" + SUSPECT_C + "','text':'Which leaves Draynor.'}]}]}"
		+ "]}";

	private PetDatabase database()
	{
		return PetDatabase.load(new ContentLoader(new Gson()));
	}

	/** The bundled content plus {@link #HUNT_QUEST_JSON}, for exercising the dormant hunt path. */
	private PetDatabase databaseWithHuntFixture()
	{
		ContentLoader loader = new ContentLoader(new Gson());
		List<QuestDef> quests = new ArrayList<>(loader.loadQuests());
		quests.add(new Gson().fromJson(HUNT_QUEST_JSON.replace('\'', '"'), QuestDef.class));
		return new PetDatabase(loader.loadSpecies(), loader.loadMoves(), loader.loadTrainers(),
			loader.loadEquipItems(), quests, loader.loadTypeChart());
	}

	private RosterManager loadedManager()
	{
		return loadedManager(database());
	}

	private RosterManager loadedManager(PetDatabase db)
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

	private QuestManager questManager(RosterManager roster)
	{
		return questManager(roster, database());
	}

	private QuestManager questManager(RosterManager roster, PetDatabase db)
	{
		return new QuestManager(db, roster);
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

		// The Draynor guard carries no reward block: the win still clears the chapter and returns its
		// end-screen conversation, it just hands over nothing.
		driveCapstoneToStep(roster, quests, 1);
		QuestManager.DefeatResult guard = quests.onTrainerDefeated("draynor_guard");
		assertNotNull("beating the guard clears the chapter", guard);
		assertNull("no reward block on the guard chapter", guard.getRewardItemName());
		assertEquals(0, guard.getRewardCoins());
		assertFalse("the guard's payoff still plays", guard.getConversation().isEmpty());
		assertEquals(2, roster.getQuestStep(CAPSTONE));

		driveCapstoneToStep(roster, quests, 3);
		QuestManager.DefeatResult martin = quests.onTrainerDefeated("martin_thwait");
		assertEquals("Amulet of the Rogue", martin.getRewardItemName());
		assertEquals(60, martin.getRewardCoins());
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
		PetDatabase db = databaseWithHuntFixture();
		RosterManager roster = loadedManager(db);
		QuestManager quests = questManager(roster, db);
		assertTrue("fixture talk chapter clears onto the hunt", quests.completeTalk(HUNT_QUEST));
		assertEquals(1, roster.getQuestStep(HUNT_QUEST));
		assertTrue(quests.currentChapter(HUNT_QUEST).isHunt());

		assertNotNull(quests.onTrainerDefeated(SUSPECT_A));   // progress
		assertEquals(1, roster.getQuestStep(HUNT_QUEST));
		assertNull(quests.onTrainerDefeated(SUSPECT_A));      // repeat: no double-count
		assertEquals(1, quests.countHuntBeaten(quests.quest(HUNT_QUEST), quests.currentChapter(HUNT_QUEST)));
		assertNotNull(quests.onTrainerDefeated(SUSPECT_B));
		assertEquals(1, roster.getQuestStep(HUNT_QUEST));
		assertNotNull(quests.onTrainerDefeated(SUSPECT_C));   // third distinct -> clears
		assertEquals(2, roster.getQuestStep(HUNT_QUEST));
		assertTrue(quests.isComplete(HUNT_QUEST));
	}

	@Test
	public void finaleAwardsBluePartyHatAndCompletes()
	{
		RosterManager roster = loadedManager();
		QuestManager quests = questManager(roster);
		driveCapstoneToStep(roster, quests, 6); // the Wise Old Man closes the story
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
		// Read the end from the content, so adding or dropping a chapter doesn't strand this test.
		driveCapstoneToStep(roster, quests, quests.quest(CAPSTONE).completeStep());
		assertTrue(quests.isComplete(CAPSTONE));
		assertTrue(roster.ownsItem(Item.SEALED_ENVELOPE));
		assertTrue(roster.ownsItem(Item.BLUE_PARTY_HAT));
	}
}
