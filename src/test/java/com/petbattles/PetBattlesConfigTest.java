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
		assertFalse("select locked pets", PetBattlesConfig.devSelectLockedPets());
		assertFalse("remote battles", PetBattlesConfig.devRemoteBattles());
		assertFalse("unlock all", PetBattlesConfig.devUnlockAll());
		assertFalse("full xp curve", PetBattlesConfig.devFullXpCurve());
		assertFalse("battle trace", PetBattlesConfig.devBattleTrace());
		assertEquals("xp multiplier is neutral", 1, PetBattlesConfig.devXpMultiplier());
	}

	@Test
	public void realUserConfigDefaultsAreUnchanged()
	{
		assertEquals(1, config.battleSpeed());
		assertFalse(config.autoAdvanceBattleText());
		// XP chat messages default on
		org.junit.Assert.assertTrue(config.showXpMessages());
		// The hub overlay is opt-out, not opt-in: existing players must see no change.
		org.junit.Assert.assertTrue(config.showHubOverlay());
	}
}
