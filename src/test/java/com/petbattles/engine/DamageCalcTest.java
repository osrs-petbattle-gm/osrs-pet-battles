package com.petbattles.engine;

import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DamageCalcTest
{
	@Test
	public void damageIsWithinRollBounds()
	{
		// With rng fixed at extremes, damage spans exactly the 0.85..1.0 roll window
		int min = DamageCalc.damage(50, 80, 100, 100, false, 1.0, fixed(0.0));
		int max = DamageCalc.damage(50, 80, 100, 100, false, 1.0, fixed(0.9999));
		assertTrue(min > 0);
		assertTrue(max >= min);
		assertTrue("max/min ratio should be ~1.176", max <= Math.ceil(min / 0.85));
	}

	@Test
	public void stabMultiplies()
	{
		int plain = DamageCalc.damage(50, 80, 100, 100, false, 1.0, fixed(0.5));
		int stab = DamageCalc.damage(50, 80, 100, 100, true, 1.0, fixed(0.5));
		assertTrue(stab > plain);
	}

	@Test
	public void typeEffectivenessScales()
	{
		int neutral = DamageCalc.damage(50, 80, 100, 100, false, 1.0, fixed(0.5));
		int superEff = DamageCalc.damage(50, 80, 100, 100, false, 2.0, fixed(0.5));
		int notVery = DamageCalc.damage(50, 80, 100, 100, false, 0.5, fixed(0.5));
		assertTrue(superEff > neutral && neutral > notVery);
	}

	@Test
	public void minimumOneDamage()
	{
		assertTrue(DamageCalc.damage(1, 1, 1, 999, false, 0.5, fixed(0.0)) >= 1);
	}

	@Test
	public void zeroPowerDealsNothing()
	{
		assertEquals(0, DamageCalc.damage(50, 0, 100, 100, true, 2.0, fixed(0.5)));
	}

	@Test
	public void deterministicWithSeed()
	{
		assertEquals(
			DamageCalc.damage(50, 80, 120, 90, true, 2.0, new Random(42)),
			DamageCalc.damage(50, 80, 120, 90, true, 2.0, new Random(42)));
	}

	private static Random fixed(double value)
	{
		return new Random()
		{
			@Override
			public double nextDouble()
			{
				return value;
			}
		};
	}
}
