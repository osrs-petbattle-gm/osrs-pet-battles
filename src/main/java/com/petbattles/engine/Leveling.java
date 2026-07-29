package com.petbattles.engine;

/**
 * XP curve and stat scaling. By default the curve is the real OSRS experience table
 * divided by 20, so a level-99 pet takes ~650k pet-XP instead of 13M. A dev toggle
 * ({@link #setFullCurve}) swaps in the full, unscaled OSRS table for authentic pacing.
 */
public final class Leveling
{
	public static final int MAX_LEVEL = 99;
	/** Default shipped pacing: the OSRS table divided by 20 (~650k xp to 99). */
	private static final long[] XP_FAST = buildTable(20);
	/** Full unscaled OSRS table (~13M xp to 99), behind the dev toggle. */
	private static final long[] XP_FULL = buildTable(1);
	// Read on the client thread by pet-level lookups; written from config changes.
	private static volatile boolean fullCurve;

	private static long[] buildTable(int divisor)
	{
		// OSRS formula: points(n) = floor(n + 300 * 2^(n/7)); xp(level) = floor(sum(points 1..level-1) / 4)
		long[] table = new long[MAX_LEVEL + 1];
		double points = 0;
		table[1] = 0;
		for (int lvl = 2; lvl <= MAX_LEVEL; lvl++)
		{
			int n = lvl - 1;
			points += Math.floor(n + 300.0 * Math.pow(2.0, n / 7.0));
			table[lvl] = (long) Math.floor(points / 4.0) / divisor;
		}
		return table;
	}

	private Leveling()
	{
	}

	/**
	 * Select the XP curve: false (default) uses the ÷20 fast pacing, true uses the full
	 * unscaled OSRS table. Changes the effective level of every pet, so callers should
	 * refresh any level-derived UI after flipping it.
	 */
	public static void setFullCurve(boolean full)
	{
		fullCurve = full;
	}

	private static long[] table()
	{
		return fullCurve ? XP_FULL : XP_FAST;
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
		return table()[level];
	}

	public static int levelForXp(long xp)
	{
		long[] table = table();
		for (int lvl = MAX_LEVEL; lvl >= 1; lvl--)
		{
			if (xp >= table[lvl])
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
	 * Fraction of first-win XP awarded when re-fighting an already-defeated trainer. Kept
	 * generous (half of the first-win amount) so re-fights still feel worth doing (feedback #3).
	 */
	public static final double REPEAT_WIN_FACTOR = 0.5;

	/**
	 * Most levels a single pet may gain from one battle. On the compressed low-level curve a
	 * single early win would otherwise vault a level-1 pet deep into the teens (feedback #3);
	 * this caps the jump while leaving late-game pacing untouched (a capped 200 XP is a small
	 * fraction of a level by then anyway).
	 */
	public static final int MAX_LEVELS_PER_BATTLE = 5;

	/**
	 * Clamp a battle XP award so a pet finishes the battle at most {@link #MAX_LEVELS_PER_BATTLE}
	 * levels above the level it started that battle at. {@code currentXp} is the pet's XP right now
	 * (it may already have gained levels earlier in the same battle); returns the XP to actually
	 * apply, never more than {@code award} and never negative.
	 */
	public static long capBattleXp(long currentXp, long award, int battleStartLevel)
	{
		if (award <= 0)
		{
			return Math.max(0, award);
		}
		int capLevel = battleStartLevel + MAX_LEVELS_PER_BATTLE;
		if (capLevel >= MAX_LEVEL)
		{
			return award; // no cap needed near the level ceiling
		}
		long ceiling = xpForLevel(capLevel);
		if (currentXp >= ceiling)
		{
			return 0;
		}
		return Math.min(award, ceiling - currentXp);
	}

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
