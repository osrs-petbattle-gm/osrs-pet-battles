package com.petbattles;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import com.petbattles.persist.RosterManager;
import com.petbattles.persist.RosterStore;
import com.petbattles.ui.PetBattlesPanel;
import com.petbattles.ui.Sprites;

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

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private Gson gson;

	@Inject
	private Sprites sprites;

	private PetDatabase db;
	private RosterManager roster;
	private PetBattlesPanel panel;
	private NavigationButton navButton;

	@Override
	protected void startUp() throws Exception
	{
		db = PetDatabase.load(new ContentLoader(gson));
		roster = new RosterManager(db, config, new RosterStore(configManager, gson));
		panel = new PetBattlesPanel(db, roster, sprites, this::startTrainerBattle);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/com/petbattles/icons/panel_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Pet Battles")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		// Handle plugin being enabled while already logged in
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			roster.load();
			panel.refresh();
		}
		log.debug("Pet Battles started with {} species, {} moves, {} trainers",
			db.allSpecies().size(), db.allMoves().size(), db.allTrainers().size());
	}

	@Override
	protected void shutDown() throws Exception
	{
		clientToolbar.removeNavigation(navButton);
		if (roster != null)
		{
			roster.unload();
		}
		navButton = null;
		panel = null;
		roster = null;
		db = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			if (!roster.isLoaded())
			{
				roster.load();
			}
			panel.refresh();
		}
		else if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			if (e.getGameState() == GameState.LOGIN_SCREEN)
			{
				roster.unload();
			}
			panel.refresh();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (PetBattlesConfig.GROUP.equals(e.getGroup()))
		{
			panel.refresh();
		}
	}

	private void startTrainerBattle(String trainerId)
	{
		// Wired up by the battle session in the next stage
		log.debug("startTrainerBattle({})", trainerId);
	}

	public PetDatabase getDb()
	{
		return db;
	}

	public RosterManager getRoster()
	{
		return roster;
	}

	@Provides
	PetBattlesConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PetBattlesConfig.class);
	}
}
