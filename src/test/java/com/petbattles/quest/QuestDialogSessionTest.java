package com.petbattles.quest;

import com.google.gson.Gson;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import com.petbattles.persist.RosterManager;
import com.petbattles.persist.RosterStore;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The quest dialog frame's state machine, driven against the real bundled content ("Where's the
 * Remote?": a Hans talk chapter followed by the Professor's battle chapter) plus an in-memory
 * {@link RosterStore}, so no RuneLite ConfigManager or UI is involved.
 */
public class QuestDialogSessionTest
{
	private static final String REMOTE = "wheres_the_remote";

	private final Set<String> near = new HashSet<>();
	private boolean battling;

	private PetDatabase database()
	{
		return PetDatabase.load(new ContentLoader(new Gson()));
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

	private QuestDialogSession session(RosterManager roster, PetDatabase db)
	{
		return new QuestDialogSession(db, roster, new QuestManager(db, roster), () -> near,
			() -> battling);
	}

	/**
	 * Page through whatever conversation is open until it is spent, taking the last reply of any
	 * choice (the authored exit option). Guarded against a cursor that refuses to move on.
	 */
	private void talkThrough(QuestDialogSession dialog)
	{
		dialog.open();
		int guard = 0;
		while (guard++ < 100)
		{
			QuestDialogSession.Scene scene = dialog.current();
			if (scene == null)
			{
				return;
			}
			if (scene.getKind() == QuestDialogSession.Kind.LINE)
			{
				dialog.advance();
			}
			else if (scene.getKind() == QuestDialogSession.Kind.CHOICE)
			{
				dialog.pick(scene.getChoices().size() - 1);
			}
			else
			{
				return;
			}
		}
	}

	@Test
	public void nothingIsOfferedWhileNoQuestNpcIsInReach()
	{
		PetDatabase db = database();
		QuestDialogSession dialog = session(loadedManager(db), db);

		assertNull("no beat with nobody around", dialog.current());
	}

	@Test
	public void reachingTheChapterNpcOffersACollapsedPrompt()
	{
		PetDatabase db = database();
		QuestDialogSession dialog = session(loadedManager(db), db);
		near.add("hans");

		QuestDialogSession.Scene scene = dialog.current();
		assertNotNull(scene);
		assertEquals(QuestDialogSession.Kind.PROMPT, scene.getKind());
		assertEquals("Talk to Hans", scene.getText());
		assertEquals("Start from Scratch", scene.getTitle());
		assertEquals("hans", scene.getSpeaker());
		assertEquals(QuestDialogSession.ACTION_OPEN, scene.getChoices().get(0).getAction());
	}

	@Test
	public void openingThePromptStartsTheConversationAndDismissingFoldsItBack()
	{
		PetDatabase db = database();
		QuestDialogSession dialog = session(loadedManager(db), db);
		near.add("hans");

		dialog.open();
		QuestDialogSession.Scene scene = dialog.current();
		assertEquals(QuestDialogSession.Kind.LINE, scene.getKind());
		assertEquals("Hans", scene.getSpeakerName());
		assertTrue(scene.getText().startsWith("Hello. Lovely day for it."));
		assertEquals(QuestDialogSession.ACTION_CONTINUE, scene.getChoices().get(0).getAction());

		dialog.dismiss();
		assertEquals("dismissing collapses without losing the cursor",
			QuestDialogSession.Kind.PROMPT, dialog.current().getKind());
		dialog.open();
		assertEquals("reopening resumes the same line", scene.getText(), dialog.current().getText());
	}

	@Test
	public void payingThroughATalkChapterClearsItAndTheNextChapterStartsCollapsed()
	{
		PetDatabase db = database();
		RosterManager roster = loadedManager(db);
		QuestDialogSession dialog = session(roster, db);
		near.add("hans");

		talkThrough(dialog);

		assertEquals("the talk chapter cleared itself", 1, roster.getQuestStep(REMOTE));
		assertNull("Hans has nothing left to say", dialog.current());
	}

	@Test
	public void aBattleChapterEndsInAChallengeInsideTheFrame()
	{
		PetDatabase db = database();
		RosterManager roster = loadedManager(db);
		QuestDialogSession dialog = session(roster, db);
		near.add("hans");
		talkThrough(dialog);
		near.clear();
		near.add("professor_oddenstein");

		talkThrough(dialog);

		QuestDialogSession.Scene scene = dialog.current();
		assertNotNull(scene);
		assertEquals(QuestDialogSession.Kind.ACTION, scene.getKind());
		QuestDialogSession.Choice challenge = scene.getChoices().get(0);
		assertEquals("Challenge Professor Oddenstein", challenge.getLabel());
		assertEquals(QuestDialogSession.ACTION_FIGHT + "professor_oddenstein", challenge.getAction());
		assertFalse("no team, so the challenge is offered but not pressable", challenge.isEnabled());
		assertEquals("Add a pet to your team first", scene.getHint());
		assertEquals("the battle, not the frame, clears the chapter", 1, roster.getQuestStep(REMOTE));
	}

	@Test
	public void anOpenConversationSurvivesTheNpcWanderingOffButTheChallengeDoesNot()
	{
		PetDatabase db = database();
		QuestDialogSession dialog = session(loadedManager(db), db);
		near.add("hans");
		dialog.open();
		dialog.advance();

		near.clear();
		QuestDialogSession.Scene scene = dialog.current();
		assertNotNull("a patrolling Hans can't cut his own scene short", scene);
		assertEquals(QuestDialogSession.Kind.LINE, scene.getKind());

		// Once it is spent the latch ends, so the chapter is out of reach again.
		near.add("hans");
		talkThrough(dialog);
		near.clear();
		assertNull("the Professor still has to be found in person", dialog.current());
	}

	@Test
	public void repliesAreOfferedAsAChoiceAndPickingOnePlaysItsLines()
	{
		PetDatabase db = database();
		RosterManager roster = loadedManager(db);
		QuestDialogSession dialog = session(roster, db);
		near.add("hans");
		talkThrough(dialog);
		near.clear();
		near.add("professor_oddenstein");
		dialog.open();

		QuestDialogSession.Scene scene = dialog.current();
		while (scene.getKind() == QuestDialogSession.Kind.LINE)
		{
			dialog.advance();
			scene = dialog.current();
		}
		assertEquals(QuestDialogSession.Kind.CHOICE, scene.getKind());
		assertFalse("the Professor's chapter asks the player something", scene.getChoices().isEmpty());
		assertEquals(QuestDialogSession.ACTION_PICK + "0", scene.getChoices().get(0).getAction());

		dialog.pick(0);
		assertEquals("picking a reply plays its lines", QuestDialogSession.Kind.LINE,
			dialog.current().getKind());
	}

	@Test
	public void resetDropsEveryCursorSoTheNextLoginStartsFresh()
	{
		PetDatabase db = database();
		QuestDialogSession dialog = session(loadedManager(db), db);
		near.add("hans");
		dialog.open();
		dialog.advance();
		dialog.advance();

		dialog.reset();

		QuestDialogSession.Scene scene = dialog.current();
		assertEquals(QuestDialogSession.Kind.PROMPT, scene.getKind());
		dialog.open();
		assertTrue("the chapter reads from its first line again",
			dialog.current().getText().startsWith("Hello. Lovely day for it."));
	}

	@Test
	public void thePayoffWaitsForTheBattleWindowThenOutranksTheWorld()
	{
		PetDatabase db = database();
		QuestDialogSession dialog = session(loadedManager(db), db);
		near.add("hans");
		battling = true;

		assertNull("the battle window owns the screen while a fight runs", dialog.current());

		// The battle clears a chapter and hands over its payoff, but the summary (level-ups, learned
		// moves) and any forget-a-move prompt still have the screen: the NPC waits his turn.
		dialog.play(db.quest(REMOTE).getChapters().get(1).getRewardConversation());
		assertNull("nothing talks over the level-up summary", dialog.current());

		battling = false;
		QuestDialogSession.Scene scene = dialog.current();
		assertNotNull(scene);
		assertEquals("an earned conversation opens expanded, not as a pill",
			QuestDialogSession.Kind.LINE, scene.getKind());
		assertEquals("Professor Oddenstein", scene.getSpeakerName());
		assertEquals("it outranks the Hans beat the world is offering", "Professor Oddenstein",
			dialog.current().getSpeakerName());

		int guard = 0;
		while (dialog.current() != null && dialog.current().getKind() == QuestDialogSession.Kind.LINE
			&& guard++ < 100)
		{
			dialog.advance();
		}
		assertEquals("Hans is offered again once the payoff is spent",
			QuestDialogSession.Kind.PROMPT, dialog.current().getKind());
	}

	@Test
	public void anEmptyPayoffLeavesTheFrameAlone()
	{
		PetDatabase db = database();
		QuestDialogSession dialog = session(loadedManager(db), db);

		dialog.play(Collections.emptyList());

		assertNull("a chapter with no payoff conversation shows nothing", dialog.current());
	}

	@Test
	public void nothingIsOfferedBeforeTheRosterHasLoaded()
	{
		PetDatabase db = database();
		RosterManager roster = new RosterManager(db, new RosterStore(null, null)
		{
			@Override
			public RosterData load()
			{
				return new RosterData();
			}

			@Override
			public void save(RosterData d)
			{
			}
		});
		QuestDialogSession dialog = session(roster, db);
		near.addAll(Collections.singleton("hans"));

		assertNull("no quest prompts on the login screen", dialog.current());
	}
}
