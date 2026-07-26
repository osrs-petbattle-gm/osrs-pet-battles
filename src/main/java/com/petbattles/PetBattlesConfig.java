package com.petbattles;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(PetBattlesConfig.GROUP)
public interface PetBattlesConfig extends Config
{
	String GROUP = "petbattles";

	/**
	 * Developer mode. Enable it with {@code -Dpetbattles.dev=true} when running the client from
	 * source (e.g. {@code ./gradlew run -Dpetbattles.dev=true}). It is never set in the plugin-hub
	 * build, so every dev affordance below is inert and invisible in production — the testing
	 * toggles are intentionally NOT {@code @ConfigItem}s, so they never appear in the config panel
	 * and there is no branch to maintain. See docs/plans/roadmap.md §S.
	 */
	boolean DEV = Boolean.getBoolean("petbattles.dev");

	@ConfigItem(
		keyName = "battleSpeed",
		name = "Battle speed",
		description = "Game ticks between battle messages (lower = faster battles)",
		position = 1
	)
	@Range(min = 1, max = 5)
	default int battleSpeed()
	{
		return 1;
	}

	@ConfigItem(
		keyName = "autoAdvanceBattleText",
		name = "Auto-advance battle text",
		description = "Advance battle messages on a timer instead of waiting for a click or Space on each line",
		position = 2
	)
	default boolean autoAdvanceBattleText()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showXpMessages",
		name = "Pet XP chat messages",
		description = "Announce pet XP gains and level-ups in the chat box",
		position = 3
	)
	default boolean showXpMessages()
	{
		return true;
	}

	// --- Developer-only affordances (gated on DEV; not config items) ---
	// These read the DEV flag (and, for the graduated knobs, extra -D properties) rather than
	// stored config, so a hub build can never show or enable them regardless of a user's profile.

	/**
	 * Adds per-card "Unlock (dev)" buttons and the "Reset progression (dev)" button so a developer
	 * can build teams with unowned pets. Dev-only.
	 */
	default boolean devSelectLockedPets()
	{
		return DEV;
	}

	/**
	 * Lets a developer fight any trainer from the panel without meeting them in-world first. Dev-only.
	 */
	default boolean devRemoteBattles()
	{
		return DEV;
	}

	/**
	 * Treat every pet as owned, for testing without the collection log. Off unless the developer
	 * additionally passes {@code -Dpetbattles.unlockAll=true}. Dev-only.
	 */
	default boolean devUnlockAll()
	{
		return DEV && Boolean.getBoolean("petbattles.unlockAll");
	}

	/**
	 * Use the full, unscaled OSRS experience table instead of the 20x-faster default pacing. Off
	 * unless the developer passes {@code -Dpetbattles.fullXpCurve=true}. Dev-only.
	 */
	default boolean devFullXpCurve()
	{
		return DEV && Boolean.getBoolean("petbattles.fullXpCurve");
	}

	/**
	 * Multiply battle XP rewards so pets level quickly in testing. Set with
	 * {@code -Dpetbattles.xpMult=<n>} (clamped to at least 1). Dev-only; always 1 in production.
	 */
	default int devXpMultiplier()
	{
		return DEV ? Math.max(1, Integer.getInteger("petbattles.xpMult", 1)) : 1;
	}

	/**
	 * Log the battle event sequence at debug level to diagnose animation/sequencing. Off unless the
	 * developer passes {@code -Dpetbattles.trace=true}. Dev-only.
	 */
	default boolean devBattleTrace()
	{
		return DEV && Boolean.getBoolean("petbattles.trace");
	}
}
