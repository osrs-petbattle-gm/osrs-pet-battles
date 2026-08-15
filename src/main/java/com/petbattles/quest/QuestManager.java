package com.petbattles.quest;

import com.petbattles.data.PetDatabase;
import com.petbattles.item.EquipItemDef;
import com.petbattles.item.Item;
import com.petbattles.persist.RosterManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * The data-described quest hook: it drives the {@link QuestDef data quests} loaded from quests.json,
 * where every chapter is a proximity-gated conversation followed by a talk/battle/hunt objective.
 * Pure logic over {@link PetDatabase} and {@link RosterManager} (both fully synchronised), so it is
 * safe to call from either hub surface or the battle thread, and unit-testable with an in-memory
 * roster store.
 *
 * <p>A quest's stored step ({@link RosterManager#getQuestStep(String)}) is the index of its current
 * chapter; the quest is complete once the step passes the last chapter
 * ({@link QuestDef#completeStep()}). Rewards are applied at the moment a chapter is cleared, tied to
 * the step actually moving forward, so each grants exactly once.
 */
@Slf4j
public class QuestManager
{
	/**
	 * What clearing a battle/hunt chapter produced, for the caller to present on the battle end
	 * screen: the conversation to play (the NPC's payoff + hand-over) and what was awarded (for the
	 * plugin's system chat line). Returned only when a battle actually progressed a quest.
	 */
	public static final class DefeatResult
	{
		private final List<Conversation.Node> conversation;
		private final String rewardItemName;
		private final long rewardCoins;

		DefeatResult(List<Conversation.Node> conversation, String rewardItemName, long rewardCoins)
		{
			this.conversation = conversation;
			this.rewardItemName = rewardItemName;
			this.rewardCoins = rewardCoins;
		}

		/** The end-screen conversation to play (never null; may be empty). */
		public List<Conversation.Node> getConversation()
		{
			return conversation == null ? Collections.emptyList() : conversation;
		}

		/** Display name of the item the chapter awarded (held or key-item trophy), or null. */
		public String getRewardItemName()
		{
			return rewardItemName;
		}

		/** Coins granted by the chapter (0 if none). */
		public long getRewardCoins()
		{
			return rewardCoins;
		}
	}

	private final PetDatabase db;
	private final RosterManager roster;

	public QuestManager(PetDatabase db, RosterManager roster)
	{
		this.db = db;
		this.roster = roster;
	}

	/** The quest definition for this id, or null if unknown. */
	public QuestDef quest(String questId)
	{
		return db.quest(questId);
	}

	/**
	 * Whether a quest is available to the player: true unless its {@code requires} names another
	 * quest that isn't complete yet.
	 */
	public boolean isAvailable(QuestDef quest)
	{
		if (quest == null)
		{
			return false;
		}
		String requires = quest.getRequires();
		return requires == null || requires.isEmpty() || isComplete(requires);
	}

	/** Whether this quest has been carried past its last chapter. */
	public boolean isComplete(String questId)
	{
		QuestDef quest = db.quest(questId);
		return quest != null && roster.getQuestStep(questId) >= quest.completeStep();
	}

	/**
	 * The chapter this quest is currently on (its step), or null if the quest is unknown or complete.
	 */
	public QuestDef.Chapter currentChapter(String questId)
	{
		QuestDef quest = db.quest(questId);
		if (quest == null)
		{
			return null;
		}
		int step = roster.getQuestStep(questId);
		for (QuestDef.Chapter chapter : quest.getChapters())
		{
			if (chapter.getStep() == step)
			{
				return chapter;
			}
		}
		return null;
	}

	/**
	 * The player finished a talk chapter's conversation (a non-battle, non-hunt chapter): apply its
	 * reward and advance. No-op (returns false) if the quest is unavailable/complete or the current
	 * chapter is a battle/hunt chapter (which a win clears). The UI only calls this once the chapter
	 * is reachable and its conversation has run out, so proximity is enforced there.
	 */
	public boolean completeTalk(String questId)
	{
		QuestDef quest = db.quest(questId);
		if (quest == null || !isAvailable(quest))
		{
			return false;
		}
		QuestDef.Chapter chapter = currentChapter(questId);
		if (chapter == null || chapter.isBattle() || chapter.isHunt())
		{
			return false;
		}
		return applyRewardAndAdvance(questId, chapter);
	}

	/**
	 * A trainer was just beaten: if an available quest's current chapter is waiting on this trainer —
	 * a battle chapter naming it, or a hunt chapter with it in the pool — clear/progress that chapter
	 * and return the end-screen conversation. Returns null if no quest was waiting on this trainer, so
	 * a plain re-fight (or a re-fight of an already-counted suspect) does nothing.
	 */
	public DefeatResult onTrainerDefeated(String trainerId)
	{
		for (QuestDef quest : db.allQuests())
		{
			if (!isAvailable(quest))
			{
				continue;
			}
			QuestDef.Chapter chapter = currentChapter(quest.getId());
			if (chapter == null)
			{
				continue;
			}
			if (chapter.isBattle() && trainerId.equals(chapter.getBattleTrainer()))
			{
				return resolveClear(quest, chapter, chapter.getRewardConversation());
			}
			if (chapter.isHunt() && chapter.getBattlePool().contains(trainerId))
			{
				return resolveHunt(quest, chapter, trainerId);
			}
		}
		return null;
	}

	/** Clear a battle chapter: reward + advance, returning its end-screen conversation (or null). */
	private DefeatResult resolveClear(QuestDef quest, QuestDef.Chapter chapter,
		List<Conversation.Node> conversation)
	{
		QuestDef.Reward reward = chapter.getReward();
		String rewardName = rewardName(reward);
		long coins = reward != null ? reward.getCoins() : 0;
		if (!applyRewardAndAdvance(quest.getId(), chapter))
		{
			return null;
		}
		return new DefeatResult(conversation, rewardName, coins);
	}

	/**
	 * Progress a hunt chapter: flag this suspect if newly beaten, and once enough distinct suspects
	 * are down, clear the chapter (its reward conversation plays). Earlier wins return a short
	 * progress line; a re-fight of an already-counted suspect returns null.
	 */
	private DefeatResult resolveHunt(QuestDef quest, QuestDef.Chapter chapter, String trainerId)
	{
		boolean newlyBeaten = roster.setFlag(huntFlag(quest, trainerId));
		int beaten = countHuntBeaten(quest, chapter);
		int required = chapter.getBattlesRequired();
		QuestDef.Suspect suspect = chapter.getSuspect(trainerId);
		if (beaten >= required)
		{
			// Whoever went down last delivers the hand-off, so each suspect authors their own finale.
			List<Conversation.Node> finale = suspect != null ? suspect.getFinale() : null;
			return resolveClear(quest, chapter,
				finale == null || finale.isEmpty() ? chapter.getRewardConversation() : finale);
		}
		if (!newlyBeaten)
		{
			return null;
		}
		List<Conversation.Node> lines = new ArrayList<>(
			suspect != null ? suspect.getConcede() : Collections.<Conversation.Node>emptyList());
		if (lines.isEmpty())
		{
			lines.add(new Conversation.Node(trainerId,
				"Bah - you have me. But I'm not the one you're after; try another greybeard."));
		}
		// Narration (no speaker) so the authored concede lines stay verbatim.
		lines.add(new Conversation.Node(null, "Suspects defeated: " + beaten + "/" + required + "."));
		return new DefeatResult(lines, null, 0);
	}

	/**
	 * Grant a chapter's reward then advance the quest one step. The advance is what makes the reward
	 * one-off: it only moves forward from the chapter's own step, so a repeat call finds a different
	 * current chapter and skips. Returns whether the step moved forward.
	 */
	private boolean applyRewardAndAdvance(String questId, QuestDef.Chapter chapter)
	{
		// Advance first, so the reward is bound to the step actually moving forward: a defensive
		// repeat call can't re-grant because the step is already past this chapter.
		if (!roster.advanceQuest(questId, chapter.getStep() + 1))
		{
			return false;
		}
		QuestDef.Reward reward = chapter.getReward();
		if (reward != null)
		{
			if (reward.getItem() != null && !reward.getItem().isEmpty())
			{
				roster.grantItem(reward.getItem(), 1);
			}
			if (reward.getCoins() > 0)
			{
				roster.addCoins(reward.getCoins());
			}
			if (reward.getFlag() != null && !reward.getFlag().isEmpty())
			{
				roster.setFlag(reward.getFlag());
			}
		}
		log.debug("Quest {} cleared chapter {} ({})", questId, chapter.getStep(), chapter.getTitle());
		return true;
	}

	private String huntFlag(QuestDef quest, String trainerId)
	{
		return quest.getId() + ":wb:" + trainerId;
	}

	/** Whether a specific hunt suspect has already been beaten (for the Story view). */
	public boolean isHuntMemberBeaten(QuestDef quest, String trainerId)
	{
		return roster.hasFlag(huntFlag(quest, trainerId));
	}

	/** How many distinct hunt suspects have been beaten so far (for the Story view's N/M counter). */
	public int countHuntBeaten(QuestDef quest, QuestDef.Chapter chapter)
	{
		int n = 0;
		for (String member : chapter.getBattlePool())
		{
			if (roster.hasFlag(huntFlag(quest, member)))
			{
				n++;
			}
		}
		return n;
	}

	/** Display name of a chapter reward (held item or key-item trophy), or null for none. */
	private String rewardName(QuestDef.Reward reward)
	{
		if (reward == null)
		{
			return null;
		}
		if (reward.getItem() != null && !reward.getItem().isEmpty())
		{
			EquipItemDef item = db.equipItem(reward.getItem());
			return item != null ? item.getName() : null;
		}
		if (reward.getKeyItem() != null && !reward.getKeyItem().isEmpty())
		{
			Item item = Item.byId(reward.getKeyItem());
			return item != null ? item.getName() : null;
		}
		return null;
	}
}
