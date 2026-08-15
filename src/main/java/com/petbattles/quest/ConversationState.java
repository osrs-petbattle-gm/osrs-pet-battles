package com.petbattles.quest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A runtime cursor that pages through a {@link Conversation} of {@link Conversation.Node nodes}.
 * Pure logic (no UI), so both render surfaces — the Quests-pane Story view and the battle
 * end-screen — drive the same state machine, and it is unit-testable.
 *
 * <p>Flow: a line is shown until {@link #advance()} (click-to-continue); a choice presents its
 * option labels until {@link #pick(int)}. Picking an option plays its lines, then either returns to
 * the choice menu (so the player can ask another question) or, if the option is an
 * {@link Conversation.Option#isExit() exit}, proceeds past the choice. The conversation is
 * {@link #isDone() done} once the cursor runs off the end.
 */
public final class ConversationState
{
	/** What the current cursor position asks the UI to render. */
	public enum Kind
	{
		LINE,
		CHOICE
	}

	/** A snapshot of the current beat: either a spoken line or a choice menu. */
	public static final class Frame
	{
		private final Kind kind;
		private final String speaker;
		private final String text;
		private final List<String> options;

		private Frame(Kind kind, String speaker, String text, List<String> options)
		{
			this.kind = kind;
			this.speaker = speaker;
			this.text = text;
			this.options = options;
		}

		public Kind getKind()
		{
			return kind;
		}

		/** LINE only: the speaker id ("player"/trainer id/null for narration). */
		public String getSpeaker()
		{
			return speaker;
		}

		/** LINE only: the spoken text. */
		public String getText()
		{
			return text;
		}

		/** CHOICE only: the option labels, in order (indices match {@link ConversationState#pick}). */
		public List<String> getOptions()
		{
			return options == null ? Collections.emptyList() : options;
		}
	}

	private final List<Conversation.Node> nodes;
	private int index;
	// The lines of the option the player just picked (null when not in a branch), the position within
	// them, whether finishing them exits the choice, and the choice node to return to.
	private List<Conversation.Node> branch;
	private int branchIndex;
	private boolean branchExits;
	private int choiceIndex;

	public ConversationState(List<Conversation.Node> nodes)
	{
		this.nodes = nodes == null ? Collections.emptyList() : nodes;
	}

	/** The current beat to render, or null when the conversation is finished. */
	public Frame current()
	{
		if (branch != null && branchIndex < branch.size())
		{
			Conversation.Node n = branch.get(branchIndex);
			return line(n);
		}
		if (index >= nodes.size())
		{
			return null;
		}
		Conversation.Node n = nodes.get(index);
		if (n.isChoice())
		{
			List<String> labels = new ArrayList<>();
			for (Conversation.Option opt : n.getChoice())
			{
				labels.add(opt.getLabel());
			}
			return new Frame(Kind.CHOICE, null, null, labels);
		}
		return line(n);
	}

	private Frame line(Conversation.Node n)
	{
		return new Frame(Kind.LINE, n.getSpeaker(), n.getText(), null);
	}

	public boolean isDone()
	{
		return current() == null;
	}

	/**
	 * Click-to-continue on a line. In a branch, steps to the next branch line and, when the branch is
	 * spent, either leaves the choice (exit option) or returns to its menu. On a main line, steps to
	 * the next node. No-op while a choice menu is showing (the UI calls {@link #pick(int)} there).
	 */
	public void advance()
	{
		if (branch != null)
		{
			branchIndex++;
			if (branchIndex >= branch.size())
			{
				boolean exit = branchExits;
				branch = null;
				branchIndex = 0;
				if (exit)
				{
					index = choiceIndex + 1;
				}
				// else: index stays on the choice node, so its menu shows again.
			}
			return;
		}
		if (index < nodes.size() && !nodes.get(index).isChoice())
		{
			index++;
		}
	}

	/**
	 * Pick option {@code i} of the current choice menu: play its lines (if any), then return to the
	 * menu unless it is an exit option. No-op unless a choice is currently showing and {@code i} is
	 * in range.
	 */
	public void pick(int i)
	{
		if (branch != null || index >= nodes.size())
		{
			return;
		}
		Conversation.Node node = nodes.get(index);
		if (!node.isChoice() || i < 0 || i >= node.getChoice().size())
		{
			return;
		}
		Conversation.Option opt = node.getChoice().get(i);
		choiceIndex = index;
		branchExits = opt.isExit();
		List<Conversation.Node> lines = opt.getLines();
		if (lines.isEmpty())
		{
			// Nothing to say: an exit option leaves the choice immediately; otherwise no-op.
			if (opt.isExit())
			{
				index = choiceIndex + 1;
			}
			return;
		}
		branch = lines;
		branchIndex = 0;
	}
}
