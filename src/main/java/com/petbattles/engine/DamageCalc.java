package com.petbattles.engine;

import java.util.Random;

/**
 * Damage formula: classic monster-battler shape, tuned for our stat scale.
 * damage = (((2*level/5 + 2) * power * atk/def) / 50 + 2) * STAB * typeEff * roll(0.85..1.0)
 */
public final class DamageCalc
{
	public static final double STAB_MULTIPLIER = 1.5;

	private DamageCalc()
	{
	}

	public static int damage(int attackerLevel, int power, int atk, int def,
		boolean stab, double typeEffectiveness, Random rng)
	{
		if (power <= 0 || typeEffectiveness <= 0)
		{
			return 0;
		}
		double base = ((2.0 * attackerLevel / 5.0 + 2.0) * power * atk / Math.max(1, def)) / 50.0 + 2.0;
		double roll = 0.85 + 0.15 * rng.nextDouble();
		double dmg = base * (stab ? STAB_MULTIPLIER : 1.0) * typeEffectiveness * roll;
		return Math.max(1, (int) Math.floor(dmg));
	}
}
