package com.petbattles.engine;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LevelingTest
{
	@Test
	public void levelOneIsZeroXp()
	{
		assertEquals(0, Leveling.xpForLevel(1));
	}

	@Test
	public void curveIsStrictlyIncreasing()
	{
		for (int lvl = 2; lvl <= Leveling.MAX_LEVEL; lvl++)
		{
			assertTrue("level " + lvl, Leveling.xpForLevel(lvl) > Leveling.xpForLevel(lvl - 1));
		}
	}

	@Test
	public void levelForXpInvertsXpForLevel()
	{
		for (int lvl = 1; lvl <= Leveling.MAX_LEVEL; lvl++)
		{
			assertEquals(lvl, Leveling.levelForXp(Leveling.xpForLevel(lvl)));
			if (lvl > 1)
			{
				assertEquals(lvl - 1, Leveling.levelForXp(Leveling.xpForLevel(lvl) - 1));
			}
		}
	}

	@Test
	public void curveIsScaledOsrs()
	{
		// Real OSRS level 99 is 13,034,431 xp; ours is that / 20
		assertEquals(13034431L / 20, Leveling.xpForLevel(99), 1);
	}

	@Test
	public void fullCurveMatchesUnscaledOsrs()
	{
		try
		{
			Leveling.setFullCurve(true);
			// Full curve is the real OSRS table (level 99 = 13,034,431 xp), ~20x the default
			assertEquals(13034431L, Leveling.xpForLevel(99), 20);
			assertTrue(Leveling.xpForLevel(99) > 10_000_000L);
		}
		finally
		{
			Leveling.setFullCurve(false);
		}
		// Default pacing restored for the rest of the suite
		assertEquals(13034431L / 20, Leveling.xpForLevel(99), 1);
	}

	@Test
	public void statsGrowWithLevel()
	{
		assertTrue(Leveling.statAtLevel(50, 99) > Leveling.statAtLevel(50, 1));
		assertTrue(Leveling.hpAtLevel(50, 99) > Leveling.hpAtLevel(50, 1));
		// Level 1 pets are still viable
		assertTrue(Leveling.hpAtLevel(40, 1) >= 20);
	}

	@Test
	public void xpAwards()
	{
		assertEquals(5, Leveling.killXp(0, false));
		assertEquals(65, Leveling.killXp(120, false));
		assertEquals(195, Leveling.killXp(120, true));
		// battle win xp clamps to [10, 200]
		assertEquals(10, Leveling.battleWinXp(1, 99));
		assertEquals(200, Leveling.battleWinXp(99, 1));
		assertEquals(25, Leveling.battleWinXp(50, 50));
	}

	@Test
	public void repeatWinsTaperTenPercentPerWinToAHalfRateFloor()
	{
		assertEquals(1.0, Leveling.repeatFactor(0), 1e-9);
		assertEquals(0.9, Leveling.repeatFactor(1), 1e-9);
		assertEquals(0.5, Leveling.repeatFactor(5), 1e-9);
		// The taper settles at the floor rather than running to nothing.
		assertEquals(Leveling.REPEAT_FLOOR, Leveling.repeatFactor(6), 1e-9);
		assertEquals(Leveling.REPEAT_FLOOR, Leveling.repeatFactor(500), 1e-9);
		// A nonsensical negative count can't pay more than the first win.
		assertEquals(1.0, Leveling.repeatFactor(-3), 1e-9);
	}

	@Test
	public void repeatWinXpFollowsTheTaper()
	{
		long first = Leveling.battleWinXp(50, 50, 0);
		assertEquals(Leveling.battleWinXp(50, 50), first);
		assertEquals(Math.round(first * 0.9), Leveling.battleWinXp(50, 50, 1));
		assertTrue("the second win still beats the old flat half rate",
			Leveling.battleWinXp(50, 50, 1) > Math.round(first * Leveling.REPEAT_FLOOR));
		assertEquals(Math.round(first * Leveling.REPEAT_FLOOR), Leveling.battleWinXp(50, 50, 9));
		// Repeats never round down to zero
		assertTrue(Leveling.battleWinXp(1, 99, 9) >= 1);
	}

	@Test
	public void battleWinCoinsScaleWithTrainerStrengthAndClamp()
	{
		// Flat per-battle reward scales with the combined enemy team level.
		assertTrue(Leveling.battleWinCoins(60) > Leveling.battleWinCoins(10));
		// Clamped to [10, 500] so a trivial or a giant trainer can't pay nothing / a fortune.
		assertEquals(10, Leveling.battleWinCoins(0));
		assertEquals(500, Leveling.battleWinCoins(10_000));
	}

	@Test
	public void repeatWinCoinsFollowTheSameTaper()
	{
		long first = Leveling.battleWinCoins(60, 0);
		assertEquals(Leveling.battleWinCoins(60), first);
		assertEquals(Math.round(first * 0.9), Leveling.battleWinCoins(60, 1));
		assertEquals(Math.round(first * Leveling.REPEAT_FLOOR), Leveling.battleWinCoins(60, 5));
		assertTrue(Leveling.battleWinCoins(60, 1) < first);
		// Repeats never round down to zero.
		assertTrue(Leveling.battleWinCoins(0, 9) >= 1);
	}

	@Test
	public void capBattleXpLimitsLevelsPerBattle()
	{
		int start = 1;
		long ceiling = Leveling.xpForLevel(start + Leveling.MAX_LEVELS_PER_BATTLE);

		// A level-1 pet (0 xp) offered a huge award lands exactly at the cap level.
		long capped = Leveling.capBattleXp(0, 10_000_000L, start);
		assertEquals(ceiling, capped);
		assertEquals(start + Leveling.MAX_LEVELS_PER_BATTLE, Leveling.levelForXp(capped));

		// An award that stays within the cap passes through untouched.
		assertEquals(ceiling - 1, Leveling.capBattleXp(0, ceiling - 1, start));

		// Once the pet has already reached the cap this battle, further awards give nothing.
		assertEquals(0, Leveling.capBattleXp(ceiling, 500, start));

		// Near the level ceiling the cap never blocks progress.
		assertEquals(500, Leveling.capBattleXp(Leveling.xpForLevel(97), 500, 97));
	}

	@Test
	public void petInstanceLevelsUp()
	{
		PetInstance pet = new PetInstance("baby_mole");
		assertEquals(1, pet.getLevel());
		int gained = pet.addXp(Leveling.xpForLevel(5));
		assertEquals(4, gained);
		assertEquals(5, pet.getLevel());
	}
}
