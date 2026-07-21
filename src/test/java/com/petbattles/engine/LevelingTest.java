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
	public void repeatWinsGiveAThirdOfFirstWinXp()
	{
		long first = Leveling.battleWinXp(50, 50, true);
		long repeat = Leveling.battleWinXp(50, 50, false);
		assertEquals(Leveling.battleWinXp(50, 50), first);
		assertEquals(Math.round(first * Leveling.REPEAT_WIN_FACTOR), repeat);
		assertTrue(repeat < first);
		// Repeats never round down to zero
		assertTrue(Leveling.battleWinXp(1, 99, false) >= 1);
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
