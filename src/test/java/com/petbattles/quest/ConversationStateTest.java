package com.petbattles.quest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The pure conversation cursor: line paging, choice branches (ask again vs exit), completion. */
public class ConversationStateTest
{
	private static Conversation.Node line(String speaker, String text)
	{
		return new Conversation.Node(speaker, text);
	}

	private static Conversation.Node choice(Conversation.Option... options)
	{
		// The choice list is private + Gson-populated in production, so tests use a small subtype
		// whose choice is set directly.
		return new TestChoiceNode(Arrays.asList(options));
	}

	/** A Node whose choice list is set directly, for tests (production sets it via Gson). */
	private static final class TestChoiceNode extends Conversation.Node
	{
		private final List<Conversation.Option> options;

		TestChoiceNode(List<Conversation.Option> options)
		{
			this.options = options;
		}

		@Override
		public List<Conversation.Option> getChoice()
		{
			return options;
		}

		@Override
		public boolean isChoice()
		{
			return options != null && !options.isEmpty();
		}
	}

	@Test
	public void emptyConversationIsImmediatelyDone()
	{
		ConversationState s = new ConversationState(new ArrayList<>());
		assertTrue(s.isDone());
		assertNull(s.current());
	}

	@Test
	public void linearLinesPageThroughInOrder()
	{
		ConversationState s = new ConversationState(Arrays.asList(
			line("hans", "Rumour has it..."),
			line("player", "Where, exactly?"),
			line("hans", "Draynor way, I heard.")));

		assertEquals(ConversationState.Kind.LINE, s.current().getKind());
		assertEquals("hans", s.current().getSpeaker());
		assertEquals("Rumour has it...", s.current().getText());
		s.advance();
		assertEquals("player", s.current().getSpeaker());
		s.advance();
		assertEquals("Draynor way, I heard.", s.current().getText());
		assertFalse(s.isDone());
		s.advance();
		assertTrue(s.isDone());
		assertNull(s.current());
	}

	@Test
	public void choiceQuestionReturnsToMenuThenExitProceeds()
	{
		Conversation.Option ask = new Conversation.Option("Ask about the wall",
			Arrays.asList(line("banker", "Fresh mortar, that.")), false);
		Conversation.Option leave = new Conversation.Option("Say nothing", new ArrayList<>(), true);
		ConversationState s = new ConversationState(Arrays.asList(
			line("banker", "Deposit? Withdrawal?"),
			choice(ask, leave),
			line("guard", "Stop snooping!")));

		s.advance(); // past the opening line -> the choice menu
		assertEquals(ConversationState.Kind.CHOICE, s.current().getKind());
		assertEquals(Arrays.asList("Ask about the wall", "Say nothing"), s.current().getOptions());

		// Ask the question: its line plays, then we return to the menu.
		s.pick(0);
		assertEquals("Fresh mortar, that.", s.current().getText());
		s.advance();
		assertEquals(ConversationState.Kind.CHOICE, s.current().getKind());

		// Ask again — a non-exit option is repeatable.
		s.pick(0);
		assertEquals("Fresh mortar, that.", s.current().getText());
		s.advance();
		assertEquals(ConversationState.Kind.CHOICE, s.current().getKind());

		// Take the exit option: proceed past the choice to the next line.
		s.pick(1);
		assertEquals(ConversationState.Kind.LINE, s.current().getKind());
		assertEquals("Stop snooping!", s.current().getText());
		s.advance();
		assertTrue(s.isDone());
	}

	@Test
	public void exitOptionWithLinesPlaysThemThenProceeds()
	{
		Conversation.Option go = new Conversation.Option("Let's get on with it",
			Arrays.asList(line("player", "Right. Enough talk.")), true);
		ConversationState s = new ConversationState(Arrays.asList(
			choice(go),
			line("professor", "Splendid!")));

		assertEquals(ConversationState.Kind.CHOICE, s.current().getKind());
		s.pick(0);
		assertEquals("Right. Enough talk.", s.current().getText());
		s.advance(); // finish the exit branch -> past the choice
		assertEquals("Splendid!", s.current().getText());
		s.advance();
		assertTrue(s.isDone());
	}

	@Test
	public void pickAndAdvanceAreNoOpsInTheWrongState()
	{
		ConversationState s = new ConversationState(Arrays.asList(line("hans", "Hello.")));
		s.pick(0); // no choice showing -> ignored
		assertEquals("Hello.", s.current().getText());
		// advance() on a choice menu is a no-op (UI must pick):
		Conversation.Option leave = new Conversation.Option("Bye", new ArrayList<>(), true);
		ConversationState c = new ConversationState(Arrays.asList(choice(leave), line("x", "after")));
		c.advance();
		assertEquals(ConversationState.Kind.CHOICE, c.current().getKind());
	}
}
