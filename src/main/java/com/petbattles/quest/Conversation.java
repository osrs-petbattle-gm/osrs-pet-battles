package com.petbattles.quest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The data vocabulary for a RuneScape-style conversation loaded from quests.json: an ordered list of
 * {@link Node nodes}, each either a spoken <b>line</b> or a player <b>choice</b>. A chapter's
 * {@code intro} and {@code rewardConversation} are {@code List<Conversation.Node>}; the runtime
 * cursor that pages through them is {@link ConversationState}.
 *
 * <p>Only the vocabulary lives here (plain Gson-populated fields). Branching is one level deep: a
 * choice option's {@code lines} are spoken lines, not further choices.
 */
public final class Conversation
{
	private Conversation()
	{
	}

	/** One beat of a conversation: a spoken line, or a choice menu ({@link #getChoice()} set). */
	public static class Node
	{
		// A line: who is speaking (a trainer id for their chathead, "player" for the player's, or
		// null for narration) and what they say.
		private String speaker;
		private String text;
		// A choice: the options the player may pick. When non-empty this node is a choice, not a line.
		private List<Option> choice;

		public Node()
		{
		}

		public Node(String speaker, String text)
		{
			this.speaker = speaker;
			this.text = text;
		}

		public String getSpeaker()
		{
			return speaker;
		}

		public String getText()
		{
			return text;
		}

		public List<Option> getChoice()
		{
			return choice == null ? Collections.emptyList() : choice;
		}

		/** Whether this node presents a choice menu (vs. a single spoken line). */
		public boolean isChoice()
		{
			return choice != null && !choice.isEmpty();
		}
	}

	/**
	 * One option in a choice menu: the button {@code label} the player picks, the {@code lines} that
	 * play in response, and whether picking it {@code exit}s the menu (vs. returning to it so the
	 * player can ask another question).
	 */
	public static class Option
	{
		private String label;
		private List<Node> lines = new ArrayList<>();
		private boolean exit;

		public Option()
		{
		}

		public Option(String label, List<Node> lines, boolean exit)
		{
			this.label = label;
			this.lines = lines;
			this.exit = exit;
		}

		public String getLabel()
		{
			return label;
		}

		/** The lines spoken in response to picking this option (may be empty). */
		public List<Node> getLines()
		{
			return lines == null ? Collections.emptyList() : lines;
		}

		/** Whether picking this option leaves the choice (true) or returns to the menu (false). */
		public boolean isExit()
		{
			return exit;
		}
	}
}
