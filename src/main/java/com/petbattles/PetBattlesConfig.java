package com.petbattles;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(PetBattlesConfig.GROUP)
public interface PetBattlesConfig extends Config
{
	String GROUP = "petbattles";

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

	@ConfigItem(
		keyName = "devSelectLockedPets",
		name = "Dev: select locked pets",
		description = "Testing option: adds an 'Unlock' button to pets you haven't obtained, so you can build teams with them. "
			+ "These dev unlocks are kept separate from your real collection log and are ignored while this is off.",
		position = 4
	)
	default boolean devSelectLockedPets()
	{
		return false;
	}

	@ConfigItem(
		keyName = "devRemoteBattles",
		name = "Dev: remote battles",
		description = "Testing option: fight any trainer from the panel without meeting them in-game first. "
			+ "Trivializes training — normally a trainer must be challenged in the world once before remote re-fights unlock.",
		position = 5
	)
	default boolean devRemoteBattles()
	{
		return false;
	}

	@ConfigItem(
		keyName = "devUnlockAll",
		name = "Unlock all pets (dev)",
		description = "Developer option: treat every pet as owned, for testing without the collection log",
		hidden = true
	)
	default boolean devUnlockAll()
	{
		return false;
	}

	@ConfigItem(
		keyName = "devFullXpCurve",
		name = "Full OSRS XP curve (dev)",
		description = "Testing option: use the full, unscaled OSRS experience table (~13M XP to level 99) instead of the "
			+ "default 20x-faster pacing. Changes the effective level of every pet while enabled.",
		hidden = true
	)
	default boolean devFullXpCurve()
	{
		return false;
	}
}
