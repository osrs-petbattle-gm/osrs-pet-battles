package com.petbattles;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Pet Battles",
	description = "Pokemon-style battles between your collection log pets",
	tags = {"pets", "minigame", "fun", "battle"}
)
public class PetBattlesPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private PetBattlesConfig config;

	@Override
	protected void startUp() throws Exception
	{
		log.debug("Pet Battles started");
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.debug("Pet Battles stopped");
	}

	@Provides
	PetBattlesConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PetBattlesConfig.class);
	}
}
