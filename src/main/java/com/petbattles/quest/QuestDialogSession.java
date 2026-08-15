package com.petbattles.quest;

import com.petbattles.data.PetDatabase;
import com.petbattles.engine.TrainerDef;
import com.petbattles.persist.RosterManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The single owner of the player's live quest conversation, and the thing that decides what the quest
 * dialog frame shows at any moment. Quest talking is one surface now: the hub's Quests pane is a log
 * (chapter title + objective), while every spoken line, reply and Challenge comes from here.
 *
 * <p>Two things can occupy the frame. A conversation the player <b>earned</b> — a chapter's reward
 * payoff, handed in by {@link #play(List)} by the battle that cleared it — outranks everything and
 * plays expanded, so the post-battle scene reads exactly like every other quest scene. It waits for
 * the battle window to close first, so the NPC never talks over the level-up summary. Otherwise
 * each render resolves the one <b>beat</b> the world currently offers — the reachable chapter's
 * intro, a hunt suspect's accusation, or the Challenge left behind once a conversation is spent — and
 * turns it into a {@link Scene}. A beat the player hasn't clicked into renders as a compact
 * {@link Kind#PROMPT prompt} ("Talk to Hans"); {@link #open()} expands it into the full dialog, and
 * {@link #dismiss()} folds it back. A new beat always starts collapsed, so nothing ever expands into
 * the player's screen unasked.
 *
 * <p>Cursors are kept per beat key ({@code questId#step} for a chapter intro, {@code questId|trainer}
 * for an accusation) so stepping between them can't rewind either, and {@code latchKey} keeps an
 * open conversation on screen when a patrolling NPC wanders off mid-sentence — the latch ends with
 * the conversation, so the Challenge that follows still needs them genuinely in reach.
 *
 * <p>Pure logic over {@link PetDatabase} / {@link RosterManager} / {@link QuestManager} (all
 * synchronised), and unit-testable without any UI.
 */
public class QuestDialogSession
{
	/** Expand the prompt into the full dialog. */
	public static final String ACTION_OPEN = "questdialog.open";
	/** Fold the dialog back to its prompt. */
	public static final String ACTION_DISMISS = "questdialog.dismiss";
	/** Click-to-continue on a spoken line. */
	public static final String ACTION_CONTINUE = "questdialog.continue";
	/** Pick reply N of a choice; the index is appended. */
	public static final String ACTION_PICK = "questdialog.pick:";
	/** Finish a talk chapter whose conversation is already spent. */
	public static final String ACTION_DONE = "questdialog.done";
	/** Start a battle; the trainer id is appended (shared with the hub's own fight buttons). */
	public static final String ACTION_FIGHT = "fight:";

	/** What the dialog frame is currently being asked to draw. */
	public enum Kind
	{
		/** The collapsed pill: a chathead and "Talk to X" / "Challenge X". */
		PROMPT,
		/** A spoken line with a Continue. */
		LINE,
		/** A reply menu. */
		CHOICE,
		/** The conversation is spent: a Challenge (or a Continue that clears a talk chapter). */
		ACTION
	}

	/** One button in a scene: what it reads, what it dispatches, and whether it can be pressed. */
	public static final class Choice
	{
		private final String label;
		private final String action;
		private final boolean enabled;

		Choice(String label, String action, boolean enabled)
		{
			this.label = label;
			this.action = action;
			this.enabled = enabled;
		}

		public String getLabel()
		{
			return label;
		}

		public String getAction()
		{
			return action;
		}

		public boolean isEnabled()
		{
			return enabled;
		}
	}

	/** A snapshot of the dialog frame's contents; the renderer needs nothing else. */
	public static final class Scene
	{
		private final Kind kind;
		private final String title;
		private final String speaker;
		private final String speakerName;
		private final String text;
		private final List<Choice> choices;
		private final String hint;

		Scene(Kind kind, String title, String speaker, String speakerName, String text,
			List<Choice> choices, String hint)
		{
			this.kind = kind;
			this.title = title;
			this.speaker = speaker;
			this.speakerName = speakerName;
			this.text = text;
			this.choices = choices;
			this.hint = hint;
		}

		public Kind getKind()
		{
			return kind;
		}

		/** The chapter title, shown as the prompt's second line. */
		public String getTitle()
		{
			return title;
		}

		/** The chathead to draw: a trainer id, "player", or null for narration. */
		public String getSpeaker()
		{
			return speaker;
		}

		/** The speaker's display name ("You" for the player, "" for narration). */
		public String getSpeakerName()
		{
			return speakerName;
		}

		/** The body text (a spoken line, the prompt's label, or the Challenge's question). */
		public String getText()
		{
			return text;
		}

		/** The buttons, in order. */
		public List<Choice> getChoices()
		{
			return choices == null ? Collections.emptyList() : choices;
		}

		/** A muted warning drawn above the buttons (e.g. a knocked-out team), or null. */
		public String getHint()
		{
			return hint;
		}
	}

	/** The one thing the world currently offers the player, resolved fresh from quest state. */
	private static final class Beat
	{
		private String key;
		private String questId;
		private QuestDef.Chapter chapter;
		// The NPC this beat is with (the gate trainer or the suspect being confronted); null for
		// pure narration.
		private String trainerId;
		// The live conversation cursor, or null once it is spent.
		private ConversationState cursor;
		// Conversation spent, and a battle against trainerId is what clears the chapter.
		private boolean challenge;
	}

	private final PetDatabase db;
	private final RosterManager roster;
	private final QuestManager questManager;
	private final Supplier<Set<String>> nearTrainers;
	private final BooleanSupplier battleActive;

	// One cursor per beat key, kept for the login session so a spent conversation stays spent.
	private final Map<String, ConversationState> cursors = new HashMap<>();
	// The beat whose conversation is mid-flight (kept on screen even if its NPC walks away), or null.
	private String latchKey;
	// The beat the player has clicked into; anything else renders as a collapsed prompt.
	private String expandedKey;
	// A conversation handed in from outside (a battle's reward payoff): it outranks anything the world
	// is offering and plays expanded, because the player earned it rather than walked into it.
	private ConversationState playback;

	public QuestDialogSession(PetDatabase db, RosterManager roster, QuestManager questManager,
		Supplier<Set<String>> nearTrainers, BooleanSupplier battleActive)
	{
		this.db = db;
		this.roster = roster;
		this.questManager = questManager;
		this.nearTrainers = nearTrainers;
		this.battleActive = battleActive;
	}

	/**
	 * Play a conversation the player has just earned — a quest chapter's reward payoff, handed over by
	 * the battle that cleared it. It waits for the battle window to close, then takes over the frame
	 * until it is spent, after which the frame goes back to whatever the world is offering. Empty
	 * conversations are ignored.
	 */
	public synchronized void play(List<Conversation.Node> nodes)
	{
		playback = nodes == null || nodes.isEmpty() ? null : new ConversationState(nodes);
	}

	/** What the dialog frame should draw right now, or null when the player has nothing waiting. */
	public synchronized Scene current()
	{
		ConversationState.Frame earned = playbackFrame();
		if (earned != null)
		{
			return sceneOf(earned, speakerName(earned.getSpeaker()), null);
		}
		Beat beat = resolve();
		if (beat == null)
		{
			expandedKey = null;
			return null;
		}
		if (!beat.key.equals(expandedKey))
		{
			return promptScene(beat);
		}
		if (beat.cursor != null)
		{
			return frameScene(beat);
		}
		return actionScene(beat);
	}

	/** Expand the current prompt into the full dialog. */
	public synchronized void open()
	{
		Beat beat = resolve();
		if (beat != null)
		{
			expandedKey = beat.key;
		}
	}

	/** Fold the dialog back to its prompt. */
	public synchronized void dismiss()
	{
		expandedKey = null;
	}

	/** Click-to-continue on the open line; clears a talk chapter when the conversation runs out. */
	public synchronized void advance()
	{
		if (playbackFrame() != null)
		{
			playback.advance();
			return;
		}
		Beat beat = resolve();
		if (beat == null || beat.cursor == null)
		{
			return;
		}
		beat.cursor.advance();
		completeTalkIfSpent(beat);
	}

	/** Pick a reply in the open conversation; clears a talk chapter if that ends it. */
	public synchronized void pick(int option)
	{
		if (playbackFrame() != null)
		{
			playback.pick(option);
			return;
		}
		Beat beat = resolve();
		if (beat == null || beat.cursor == null)
		{
			return;
		}
		beat.cursor.pick(option);
		completeTalkIfSpent(beat);
	}

	/**
	 * Finish a talk chapter whose conversation was already spent when the player reopened it (the
	 * dialog's fallback Continue). Battle and hunt chapters are cleared by winning, not by this.
	 */
	public synchronized void completeTalk()
	{
		Beat beat = resolve();
		if (beat != null && beat.cursor == null && !beat.challenge)
		{
			questManager.completeTalk(beat.questId);
		}
	}

	/** One beat of a conversation as a scene: a spoken line with a Continue, or a reply menu. */
	private static Scene sceneOf(ConversationState.Frame frame, String speakerName, String title)
	{
		if (frame.getKind() == ConversationState.Kind.CHOICE)
		{
			List<Choice> choices = new ArrayList<>();
			List<String> options = frame.getOptions();
			for (int i = 0; i < options.size(); i++)
			{
				choices.add(new Choice(options.get(i), ACTION_PICK + i, true));
			}
			return new Scene(Kind.CHOICE, title, null, "", null, choices, null);
		}
		return new Scene(Kind.LINE, title, frame.getSpeaker(), speakerName, frame.getText(),
			Collections.singletonList(new Choice("Continue", ACTION_CONTINUE, true)), null);
	}

	/** Drop every cursor and collapse the frame (called on logout — progress itself is persisted). */
	public synchronized void reset()
	{
		cursors.clear();
		latchKey = null;
		expandedKey = null;
		playback = null;
	}

	/** The earned conversation's current beat, dropping it once it is spent. */
	private ConversationState.Frame playbackFrame()
	{
		// Held until the battle window is gone entirely: the player watches the fight out, answers any
		// forget-a-move prompts and reads their level-ups on the summary before the NPC says a word.
		if (playback == null || battleActive.getAsBoolean())
		{
			return null;
		}
		ConversationState.Frame frame = playback.current();
		if (frame == null)
		{
			playback = null;
		}
		return frame;
	}

	/**
	 * The one beat the world offers: the first available quest whose current chapter is reachable —
	 * its intro still running, or spent and leaving a Challenge / a hunt suspect to confront. Null
	 * when nothing is in reach.
	 */
	private Beat resolve()
	{
		// The battle overlay owns the screen while a fight is running; only an earned conversation
		// (the payoff waiting on its end screen) shows through.
		if (!roster.isLoaded() || battleActive.getAsBoolean())
		{
			return null;
		}
		Set<String> near = nearTrainers.get();
		for (QuestDef quest : db.allQuests())
		{
			if (!questManager.isAvailable(quest))
			{
				continue;
			}
			QuestDef.Chapter chapter = questManager.currentChapter(quest.getId());
			if (chapter == null)
			{
				continue;
			}
			String gate = chapter.getGateTrainer();
			String key = quest.getId() + "#" + chapter.getStep();
			if (gate != null && !near.contains(gate) && !key.equals(latchKey))
			{
				continue;
			}
			ConversationState cursor = cursor(key, chapter.getIntro());
			if (cursor.current() != null)
			{
				latchKey = key;
				return beat(key, quest.getId(), chapter, gate, cursor, false);
			}
			// Conversation spent: the latch ends here, so a Challenge still needs the NPC in reach —
			// a walked-away trainer can't be fought from across Gielinor.
			releaseLatch(key);
			if (gate != null && !near.contains(gate))
			{
				continue;
			}
			if (chapter.isHunt())
			{
				Beat hunt = huntBeat(quest, chapter, near);
				if (hunt != null)
				{
					return hunt;
				}
				continue;
			}
			return beat(key, quest.getId(), chapter, chapter.isBattle() ? chapter.getBattleTrainer() : gate,
				null, chapter.isBattle());
		}
		return null;
	}

	/**
	 * The hunt's beat: the first unbeaten suspect standing in front of the player, either mid-
	 * accusation or offering their Challenge. Suspects elsewhere in the world are the log's business.
	 */
	private Beat huntBeat(QuestDef quest, QuestDef.Chapter chapter, Set<String> near)
	{
		for (String memberId : chapter.getBattlePool())
		{
			if (questManager.isHuntMemberBeaten(quest, memberId))
			{
				continue;
			}
			String key = quest.getId() + "|" + memberId;
			if (!near.contains(memberId) && !key.equals(latchKey))
			{
				continue;
			}
			QuestDef.Suspect suspect = chapter.getSuspect(memberId);
			if (suspect != null && !suspect.getAccuse().isEmpty())
			{
				ConversationState cursor = cursor(key, suspect.getAccuse());
				if (cursor.current() != null)
				{
					latchKey = key;
					return beat(key, quest.getId(), chapter, memberId, cursor, false);
				}
				releaseLatch(key);
				if (!near.contains(memberId))
				{
					continue;
				}
			}
			return beat(key, quest.getId(), chapter, memberId, null, true);
		}
		return null;
	}

	private Beat beat(String key, String questId, QuestDef.Chapter chapter, String trainerId,
		ConversationState cursor, boolean challenge)
	{
		Beat beat = new Beat();
		beat.key = key;
		beat.questId = questId;
		beat.chapter = chapter;
		beat.trainerId = trainerId;
		beat.cursor = cursor;
		beat.challenge = challenge;
		return beat;
	}

	private ConversationState cursor(String key, List<Conversation.Node> nodes)
	{
		return cursors.computeIfAbsent(key, k -> new ConversationState(nodes));
	}

	private void releaseLatch(String key)
	{
		if (key.equals(latchKey))
		{
			latchKey = null;
		}
	}

	/** A talk chapter (neither battle nor hunt) clears the moment its conversation runs out. */
	private void completeTalkIfSpent(Beat beat)
	{
		if (beat.cursor.isDone() && !beat.chapter.isBattle() && !beat.chapter.isHunt())
		{
			questManager.completeTalk(beat.questId);
		}
	}

	/** The collapsed pill: whoever is waiting, and what opening the dialog would do. */
	private Scene promptScene(Beat beat)
	{
		String name = trainerName(beat.trainerId);
		String label;
		if (beat.cursor != null)
		{
			label = name.isEmpty() ? "Continue the story" : "Talk to " + name;
		}
		else if (beat.challenge)
		{
			label = "Challenge " + name;
		}
		else
		{
			label = "Continue the story";
		}
		return new Scene(Kind.PROMPT, beat.chapter.getTitle(), beat.trainerId, name, label,
			Collections.singletonList(new Choice(label, ACTION_OPEN, true)), null);
	}

	/** The open world beat's conversation, tagged with its chapter title for the prompt pill. */
	private Scene frameScene(Beat beat)
	{
		ConversationState.Frame frame = beat.cursor.current();
		return sceneOf(frame, speakerName(frame.getSpeaker()), beat.chapter.getTitle());
	}

	/** The conversation is spent: the Challenge it led to, or the Continue that clears a talk chapter. */
	private Scene actionScene(Beat beat)
	{
		if (!beat.challenge)
		{
			return new Scene(Kind.ACTION, beat.chapter.getTitle(), beat.trainerId,
				trainerName(beat.trainerId), "There's nothing more to say here.",
				Collections.singletonList(new Choice("Continue", ACTION_DONE, true)), null);
		}
		String name = trainerName(beat.trainerId);
		boolean ready = !roster.getTeam().isEmpty() && roster.teamCanFight();
		String hint = ready ? null
			: roster.getTeam().isEmpty() ? "Add a pet to your team first"
			: "Team knocked out - rest at a bank";
		return new Scene(Kind.ACTION, beat.chapter.getTitle(), beat.trainerId, name,
			"Send out your pets and settle this.",
			Collections.singletonList(new Choice("Challenge " + name, ACTION_FIGHT + beat.trainerId, ready)),
			hint);
	}

	/** Display name for a conversation speaker id: "You" for the player, else the trainer's name. */
	private String speakerName(String speaker)
	{
		return "player".equals(speaker) ? "You" : trainerName(speaker);
	}

	private String trainerName(String trainerId)
	{
		if (trainerId == null)
		{
			return "";
		}
		TrainerDef trainer = db.trainer(trainerId);
		return trainer != null ? trainer.getName() : trainerId;
	}
}
