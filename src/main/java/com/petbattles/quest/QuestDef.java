package com.petbattles.quest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A data-described, multi-chapter quest loaded from quests.json. A {@code QuestDef} is a sequence of
 * {@link Chapter chapters}, each a proximity-gated conversation: the player reaches the chapter's NPC
 * (or it plays immediately), works through its {@link Chapter#getIntro() intro} conversation, and
 * then either advances (a talk chapter) or is offered a battle whose victory advances the story and
 * hands over the reward (a battle or hunt chapter).
 *
 * <p>Progress is a per-quest int step
 * ({@link com.petbattles.persist.RosterStore.RosterData#questProgress}) = the index of the current
 * chapter, so the quest is complete once the step reaches {@link #getChapters()} size. Only the
 * mechanic is coded ({@link QuestManager}); the dialogue and rewards are data.
 */
public class QuestDef
{
	/**
	 * A reward granted when its chapter is cleared: any combination of an equip item, coins, and a
	 * one-off {@link com.petbattles.persist.RosterManager#setFlag(String) quest flag}. All optional.
	 */
	public static class Reward
	{
		private String item;
		private String keyItem;
		private long coins;
		private String flag;

		public Reward()
		{
		}

		/** Equip-item id to grant (one copy) via the inventory, or null for none. */
		public String getItem()
		{
			return item;
		}

		/**
		 * Key-item ({@link com.petbattles.item.Item}) id this chapter awards, or null. Unlike
		 * {@link #getItem()} these are trophies whose ownership is derived from quest progress (see
		 * {@link com.petbattles.persist.RosterManager#ownsItem}), so this is only used to name the
		 * reward in the announcement — nothing is granted into the inventory.
		 */
		public String getKeyItem()
		{
			return keyItem;
		}

		/** Coins to add to the wallet on top of the normal battle reward (0 = none). */
		public long getCoins()
		{
			return coins;
		}

		/** Quest flag to set, or null for none. */
		public String getFlag()
		{
			return flag;
		}
	}

	/**
	 * One hunt suspect's own dialogue, so the greybeards don't all speak with one voice. The
	 * {@code accuse} conversation plays when you confront them in person, before their Challenge
	 * appears; {@code concede} plays on a win that leaves the hunt unfinished (and points at another
	 * suspect); {@code finale} plays on the win that completes it, whoever happens to be last — so
	 * every suspect needs a finale that hands the story on to the next chapter.
	 */
	public static class Suspect
	{
		private String trainer;
		private List<Conversation.Node> accuse = new ArrayList<>();
		private List<Conversation.Node> concede = new ArrayList<>();
		private List<Conversation.Node> finale = new ArrayList<>();

		public Suspect()
		{
		}

		/** Trainer id this dialogue belongs to; matches an entry in the chapter's battle pool. */
		public String getTrainer()
		{
			return trainer;
		}

		/** Played in the Story view when you confront this suspect in person (may be empty). */
		public List<Conversation.Node> getAccuse()
		{
			return accuse == null ? Collections.emptyList() : accuse;
		}

		/** Played after beating them while the hunt is still unfinished (may be empty). */
		public List<Conversation.Node> getConcede()
		{
			return concede == null ? Collections.emptyList() : concede;
		}

		/** Played after beating them when that win completes the hunt (may be empty). */
		public List<Conversation.Node> getFinale()
		{
			return finale == null ? Collections.emptyList() : finale;
		}
	}

	/**
	 * One story beat: a proximity-gated conversation plus what clears it. A <b>talk</b> chapter
	 * (no battle/hunt) is cleared by finishing its {@link #getIntro() intro}; a <b>battle</b> chapter
	 * offers a fight against {@link #getBattleTrainer()} after the intro, then plays its
	 * {@link #getRewardConversation() reward conversation}; a <b>hunt</b> chapter needs several wins
	 * from {@link #getBattlePool()}.
	 */
	public static class Chapter
	{
		private int step;
		private String title;
		// The conversation played in the Story view once the chapter is reachable (see QuestManager /
		// ConversationState). Each node is a spoken line or a player choice.
		private List<Conversation.Node> intro = new ArrayList<>();
		// The indirect objective shown until the chapter's NPC is reached (e.g. "Ask around
		// Lumbridge."); the theme is to point, not name the destination.
		private String objective;
		// Trainer id the player must stand near to start a talk chapter's conversation; null = the
		// conversation plays immediately (pure narration).
		private String nearTrainer;
		// Trainer id to fight for a battle chapter (also the NPC to stand near); null otherwise.
		private String battleTrainer;
		// A hunt chapter: beat at least battlesRequired distinct members of this trainer-id pool.
		private List<String> battlePool = new ArrayList<>();
		private int battlesRequired;
		// Optional per-suspect dialogue for a hunt chapter (see Suspect). A pool member with no entry
		// here falls back to a generated concede line and the chapter's reward conversation.
		private List<Suspect> suspects = new ArrayList<>();
		// The conversation played on the battle end screen after the win (the NPC's payoff + hand-over).
		private List<Conversation.Node> rewardConversation = new ArrayList<>();
		private Reward reward;

		public Chapter()
		{
		}

		/** Index of this chapter; matches the quest's stored step while it is the current chapter. */
		public int getStep()
		{
			return step;
		}

		public String getTitle()
		{
			return title;
		}

		/** The Story-view conversation for this chapter (may be empty). */
		public List<Conversation.Node> getIntro()
		{
			return intro == null ? Collections.emptyList() : intro;
		}

		/** The indirect objective shown until the chapter's NPC is reached, or null. */
		public String getObjective()
		{
			return objective;
		}

		/** Trainer id to stand near to start a talk chapter, or null (plays immediately). */
		public String getNearTrainer()
		{
			return nearTrainer;
		}

		/** Trainer id to fight, or null for a talk chapter. */
		public String getBattleTrainer()
		{
			return battleTrainer;
		}

		/** The pool of trainer ids for a hunt chapter (empty if not a hunt). */
		public List<String> getBattlePool()
		{
			return battlePool == null ? Collections.emptyList() : battlePool;
		}

		/** How many distinct pool members must be beaten to clear a hunt chapter. */
		public int getBattlesRequired()
		{
			return battlesRequired;
		}

		/** Per-suspect dialogue for a hunt chapter (empty if none is authored). */
		public List<Suspect> getSuspects()
		{
			return suspects == null ? Collections.emptyList() : suspects;
		}

		/** The authored dialogue for one hunt suspect, or null if that trainer has none. */
		public Suspect getSuspect(String trainerId)
		{
			for (Suspect s : getSuspects())
			{
				if (s.getTrainer() != null && s.getTrainer().equals(trainerId))
				{
					return s;
				}
			}
			return null;
		}

		/** Whether this chapter is cleared by winning a single named battle. */
		public boolean isBattle()
		{
			return battleTrainer != null && !battleTrainer.isEmpty();
		}

		/** Whether this chapter is cleared by beating several trainers from a pool. */
		public boolean isHunt()
		{
			return !getBattlePool().isEmpty() && battlesRequired > 0;
		}

		/**
		 * The NPC whose proximity gates reaching this chapter's conversation (its battle trainer, or
		 * a talk chapter's {@code nearTrainer}), or null if it plays immediately.
		 */
		public String getGateTrainer()
		{
			if (isBattle())
			{
				return battleTrainer;
			}
			return nearTrainer != null && !nearTrainer.isEmpty() ? nearTrainer : null;
		}

		/** The end-screen conversation played after the story win (may be empty). */
		public List<Conversation.Node> getRewardConversation()
		{
			return rewardConversation == null ? Collections.emptyList() : rewardConversation;
		}

		/** The reward for clearing this chapter, or null for none. */
		public Reward getReward()
		{
			return reward;
		}
	}

	private String id;
	private String title;
	// Optional quest id that must be complete before this quest becomes available.
	private String requires;
	private List<Chapter> chapters = new ArrayList<>();

	public String getId()
	{
		return id;
	}

	public String getTitle()
	{
		return title;
	}

	/** Quest id that must be complete before this quest is available, or null for always available. */
	public String getRequires()
	{
		return requires;
	}

	public List<Chapter> getChapters()
	{
		return chapters == null ? Collections.emptyList() : chapters;
	}

	/** The step at (or beyond) which this quest is complete: past its last chapter. */
	public int completeStep()
	{
		return getChapters().size();
	}
}
