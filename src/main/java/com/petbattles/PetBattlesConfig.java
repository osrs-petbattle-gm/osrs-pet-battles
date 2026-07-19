package com.petbattles;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(PetBattlesConfig.GROUP)
public interface PetBattlesConfig extends Config
{
	String GROUP = "petbattles";

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
