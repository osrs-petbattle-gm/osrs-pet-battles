package com.petbattles.engine;

/**
 * A hidden move unlock: perform the trigger in-game (with the pet out) to learn the move.
 * Pure data — the RuneLite-side EasterEggTracker interprets triggers.
 */
public class EasterEggDef
{
	public enum TriggerKind
	{
		EMOTE,    // play animation animId (optionally within regionId)
		LOCATION, // stand in regionId
		STAT      // gain xp in skill (RuneLite Skill name) while pet is out
	}

	public static class Trigger
	{
		private TriggerKind kind;
		private int animId = -1;
		private int regionId = -1;
		private String skill;
		private String desc;

		public TriggerKind getKind()
		{
			return kind;
		}

		public int getAnimId()
		{
			return animId;
		}

		public int getRegionId()
		{
			return regionId;
		}

		public String getSkill()
		{
			return skill;
		}

		public String getDesc()
		{
			return desc;
		}
	}

	private String move;
	private Trigger trigger;

	public String getMove()
	{
		return move;
	}

	public Trigger getTrigger()
	{
		return trigger;
	}
}
