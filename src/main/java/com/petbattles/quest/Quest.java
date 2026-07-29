package com.petbattles.quest;

/**
 * The plugin's quests. Progress is stored per-quest as an int step in the roster blob
 * ({@link com.petbattles.persist.RosterStore.RosterData#questProgress}); an absent entry means
 * {@link #STEP_START}. Kept tiny and data-shaped so the Quests panel and the battle reward hooks
 * share one definition.
 */
public enum Quest
{
	/**
	 * The first quest: travel to Draynor Manor's top floor and beat Professor Oddenstein, who hands
	 * over his Remote Battle Device — the invention he battles trainers with (and keeps losing with)
	 * — permanently unlocking remote battles
	 * ({@link com.petbattles.persist.RosterManager#isRemoteBattlesUnlocked()}).
	 */
	WHERES_THE_REMOTE("wheres_the_remote", "Where's the remote?",
		"Investigate Draynor Manor and challenge Professor Oddenstein on the manor's top floor.");

	/** A quest sits at step 0 until it reaches its completion step. */
	public static final int STEP_START = 0;
	public static final int STEP_COMPLETE = 1;

	private final String id;
	private final String title;
	private final String hint;

	Quest(String id, String title, String hint)
	{
		this.id = id;
		this.title = title;
		this.hint = hint;
	}

	public String getId()
	{
		return id;
	}

	public String getTitle()
	{
		return title;
	}

	/** The player-facing objective shown while the quest is in progress. */
	public String getHint()
	{
		return hint;
	}
}
