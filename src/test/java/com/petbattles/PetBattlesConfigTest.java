package com.petbattles;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * The dev-tool gate: with no {@code -Dpetbattles.dev} property set (as in the hub build and this
 * test JVM), every developer affordance must be inert, so nothing cheat-y ships enabled.
 */
public class PetBattlesConfigTest
{
	private final PetBattlesConfig config = new PetBattlesConfig()
	{
	};

	@Test
	public void devFlagIsOffByDefault()
	{
		assertFalse("DEV must be off unless -Dpetbattles.dev=true", PetBattlesConfig.DEV);
	}

	@Test
	public void everyDevAffordanceIsInertInProduction()
	{
		assertFalse("select locked pets", config.devSelectLockedPets());
		assertFalse("remote battles", config.devRemoteBattles());
		assertFalse("unlock all", config.devUnlockAll());
		assertFalse("full xp curve", config.devFullXpCurve());
		assertFalse("battle trace", config.devBattleTrace());
		assertEquals("xp multiplier is neutral", 1, config.devXpMultiplier());
	}

	@Test
	public void realUserConfigDefaultsAreUnchanged()
	{
		assertEquals(1, config.battleSpeed());
		assertFalse(config.autoAdvanceBattleText());
		// XP chat messages default on
		org.junit.Assert.assertTrue(config.showXpMessages());
	}
}
