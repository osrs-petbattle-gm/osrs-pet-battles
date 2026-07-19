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
		keyName = "showXpMessages",
		name = "Pet XP chat messages",
		description = "Announce pet XP gains and level-ups in the chat box",
		position = 2
	)
	default boolean showXpMessages()
	{
		return true;
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
}
