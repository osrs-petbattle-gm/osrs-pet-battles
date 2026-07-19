package com.petbattles.engine;

/**
 * XP curve and stat scaling. The curve is the real OSRS experience table divided by 20,
 * so a level-99 pet takes ~650k pet-XP instead of 13M.
 */
public final class Leveling
{
	public static final int MAX_LEVEL = 99;
	private static final long[] XP_FOR_LEVEL = new long[MAX_LEVEL + 1];

	static
	{
		// OSRS formula: points(n) = floor(n + 300 * 2^(n/7)); xp(level) = floor(sum(points 1..level-1) / 4)
		double points = 0;
		XP_FOR_LEVEL[1] = 0;
		for (int lvl = 2; lvl <= MAX_LEVEL; lvl++)
		{
			int n = lvl - 1;
			points += Math.floor(n + 300.0 * Math.pow(2.0, n / 7.0));
			XP_FOR_LEVEL[lvl] = (long) Math.floor(points / 4.0) / 20;
		}
	}

	private Leveling()
	{
	}

	public static long xpForLevel(int level)
	{
		if (level < 1)
		{
			return 0;
		}
		if (level > MAX_LEVEL)
		{
			level = MAX_LEVEL;
		}
		return XP_FOR_LEVEL[level];
	}

	public static int levelForXp(long xp)
	{
		for (int lvl = MAX_LEVEL; lvl >= 1; lvl--)
		{
			if (xp >= XP_FOR_LEVEL[lvl])
			{
				return lvl;
			}
		}
		return 1;
	}

	public static int statAtLevel(int base, int level)
	{
		return (int) Math.round(base * (1.0 + level / 50.0));
	}

	public static int hpAtLevel(int baseHp, int level)
	{
		return (int) Math.round(baseHp * (1.0 + level / 40.0)) + 10;
	}

	/**
	 * XP for the follower pet when the player kills an NPC in the real game.
	 */
	public static long killXp(int npcCombatLevel, boolean typeMatch)
	{
		long xp = 5 + Math.max(0, npcCombatLevel) / 2;
		return typeMatch ? xp * 3 : xp;
	}

	/**
	 * Fraction of first-win XP awarded when re-fighting an already-defeated trainer.
	 */
	public static final double REPEAT_WIN_FACTOR = 0.33;

	/**
	 * XP for winning a plugin battle, scaled by relative levels (first-win amount).
	 */
	public static long battleWinXp(int enemyLevel, int yourLevel)
	{
		long xp = Math.round(25.0 * enemyLevel / Math.max(1, yourLevel));
		return Math.max(10, Math.min(200, xp));
	}

	/**
	 * XP for a battle win, reduced to {@link #REPEAT_WIN_FACTOR} on repeat wins
	 * against a trainer that was already defeated.
	 */
	public static long battleWinXp(int enemyLevel, int yourLevel, boolean firstWin)
	{
		long xp = battleWinXp(enemyLevel, yourLevel);
		return firstWin ? xp : Math.max(1, Math.round(xp * REPEAT_WIN_FACTOR));
	}
}
