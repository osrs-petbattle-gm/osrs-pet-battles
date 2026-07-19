package com.petbattles;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import com.petbattles.bank.AtBankTracker;
import com.petbattles.battle.BattleInputHandler;
import com.petbattles.battle.BattleKeyListener;
import com.petbattles.battle.BattleSession;
import com.petbattles.collection.CollectionLogSync;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import com.petbattles.easteregg.EasterEggTracker;
import com.petbattles.follower.FollowerTracker;
import com.petbattles.npc.NpcTrainerTracker;
import com.petbattles.persist.RosterManager;
import com.petbattles.persist.RosterStore;
import com.petbattles.ui.BattleOverlay;
import com.petbattles.ui.PetBattlesPanel;
import com.petbattles.ui.RestOverlay;
import com.petbattles.ui.Sprites;
import com.petbattles.xp.KillXpTracker;
import net.runelite.client.eventbus.EventBus;

@Slf4j
@PluginDescriptor(
	name = "Pet Battles",
	description = "Turn-based battles between your collection log pets",
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

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private EventBus eventBus;

	private PetDatabase db;
	private RosterManager roster;
	private PetBattlesPanel panel;
	private NavigationButton navButton;
	private BattleSession session;
	private BattleOverlay overlay;
	private RestOverlay restOverlay;
	private BattleInputHandler inputHandler;
	private BattleKeyListener keyListener;
	private AtBankTracker atBankTracker;
	private NpcTrainerTracker npcTrainerTracker;
	private CollectionLogSync collectionLogSync;
	private FollowerTracker followerTracker;
	private KillXpTracker killXpTracker;
	private EasterEggTracker easterEggTracker;

	@Override
	protected void startUp() throws Exception
	{
		db = PetDatabase.load(new ContentLoader(gson));
		roster = new RosterManager(db, config, new RosterStore(configManager, gson));
		atBankTracker = new AtBankTracker(client, () ->
		{
			if (panel != null)
			{
				panel.refresh();
			}
		});
		roster.setTeamEditGate(atBankTracker::isAtBank);
		restOverlay = new RestOverlay();
		panel = new PetBattlesPanel(db, roster, sprites, this::startTrainerBattle, () -> restOverlay.play());
		session = new BattleSession(db, roster, config, () -> panel.refresh());
		overlay = new BattleOverlay(session, sprites);
		inputHandler = new BattleInputHandler(session, overlay, clientThread);
		keyListener = new BattleKeyListener(client, session, clientThread);
		overlayManager.add(overlay);
		overlayManager.add(restOverlay);
		mouseManager.registerMouseListener(inputHandler);
		keyManager.registerKeyListener(keyListener);

		Runnable refreshPanel = () -> panel.refresh();
		collectionLogSync = new CollectionLogSync(client, db, roster, refreshPanel);
		followerTracker = new FollowerTracker(client, db, roster, refreshPanel);
		killXpTracker = new KillXpTracker(client, db, roster, followerTracker, config, refreshPanel);
		easterEggTracker = new EasterEggTracker(client, db, roster, followerTracker, refreshPanel);
		npcTrainerTracker = new NpcTrainerTracker(client, db, this::startTrainerBattle);
		eventBus.register(atBankTracker);
		eventBus.register(npcTrainerTracker);
		eventBus.register(collectionLogSync);
		eventBus.register(followerTracker);
		eventBus.register(killXpTracker);
		eventBus.register(easterEggTracker);

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
			clientThread.invokeLater(followerTracker::refresh);
			panel.refresh();
		}
		log.debug("Pet Battles started with {} species, {} moves, {} trainers",
			db.allSpecies().size(), db.allMoves().size(), db.allTrainers().size());
	}

	@Override
	protected void shutDown() throws Exception
	{
		clientToolbar.removeNavigation(navButton);
		if (collectionLogSync != null)
		{
			eventBus.unregister(atBankTracker);
			eventBus.unregister(npcTrainerTracker);
			eventBus.unregister(collectionLogSync);
			eventBus.unregister(followerTracker);
			eventBus.unregister(killXpTracker);
			eventBus.unregister(easterEggTracker);
		}
		if (overlay != null)
		{
			overlayManager.remove(overlay);
		}
		if (restOverlay != null)
		{
			overlayManager.remove(restOverlay);
		}
		if (inputHandler != null)
		{
			mouseManager.unregisterMouseListener(inputHandler);
		}
		if (keyListener != null)
		{
			keyManager.unregisterKeyListener(keyListener);
		}
		if (session != null)
		{
			session.close();
		}
		if (roster != null)
		{
			roster.unload();
		}
		navButton = null;
		panel = null;
		roster = null;
		db = null;
		session = null;
		overlay = null;
		restOverlay = null;
		inputHandler = null;
		keyListener = null;
		atBankTracker = null;
		npcTrainerTracker = null;
		collectionLogSync = null;
		followerTracker = null;
		killXpTracker = null;
		easterEggTracker = null;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		session.tick();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			if (!roster.isLoaded())
			{
				roster.load();
				clientThread.invokeLater(followerTracker::refresh);
			}
			panel.refresh();
		}
		else if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			if (e.getGameState() == GameState.LOGIN_SCREEN)
			{
				roster.unload();
				easterEggTracker.resetBaselines();
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
		clientThread.invokeLater(() ->
		{
			if (session.isActive() && session.getPhase() != BattleSession.Phase.ENDED)
			{
				return;
			}
			session.close();
			if (!session.startTrainerBattle(trainerId))
			{
				log.debug("Could not start battle vs {}", trainerId);
				// Most likely cause: empty or fully-fainted team (e.g. via the
				// in-world Challenge entry, which has no panel tooltip to explain)
				String reason = roster.getTeam().isEmpty()
					? "Add a pet to your team first."
					: !roster.teamCanFight()
					? "Your team is knocked out — rest your pets at a bank first."
					: "Could not start the battle.";
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Pet Battles: " + reason, null);
			}
		});
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
