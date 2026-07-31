package com.petbattles;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.api.coords.WorldPoint;
import com.petbattles.bank.AtBankTracker;
import com.petbattles.battle.BattleInputHandler;
import com.petbattles.battle.BattleKeyListener;
import com.petbattles.battle.BattleSession;
import com.petbattles.collection.CollectionLogSync;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import com.petbattles.easteregg.EasterEggTracker;
import com.petbattles.engine.Leveling;
import com.petbattles.engine.TrainerDef;
import com.petbattles.item.Item;
import com.petbattles.follower.FollowerTracker;
import com.petbattles.follower.HeldItemTracker;
import com.petbattles.npc.NearTrainerTracker;
import com.petbattles.persist.RosterManager;
import com.petbattles.persist.RosterStore;
import com.petbattles.ui.BattleOverlay;
import com.petbattles.ui.HubInputHandler;
import com.petbattles.ui.HubKeyListener;
import com.petbattles.ui.HubOverlay;
import com.petbattles.ui.PetBattlesPanel;
import com.petbattles.ui.PetChatheads;
import com.petbattles.ui.Portraits;
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

	@Inject
	private WorldMapPointManager worldMapPointManager;

	// "Locate" pins currently on the world map (one per known trainer location). Replaced on each
	// Locate and cleared on shutdown. A trainer like Man has many, so this is a list.
	private final java.util.List<WorldMapPoint> locatePins = new java.util.ArrayList<>();
	private BufferedImage mapPinImage;

	private PetDatabase db;
	private RosterManager roster;
	private PetBattlesPanel panel;
	private NavigationButton navButton;
	private BattleSession session;
	private BattleOverlay overlay;
	private RestOverlay restOverlay;
	private HubOverlay hubOverlay;
	private HubInputHandler hubInputHandler;
	private HubKeyListener hubKeyListener;
	private BattleInputHandler inputHandler;
	private BattleKeyListener keyListener;
	private AtBankTracker atBankTracker;
	private NearTrainerTracker nearTrainerTracker;
	private CollectionLogSync collectionLogSync;
	private FollowerTracker followerTracker;
	private HeldItemTracker heldItemTracker;
	private KillXpTracker killXpTracker;
	private EasterEggTracker easterEggTracker;

	@Override
	protected void startUp() throws Exception
	{
		db = PetDatabase.load(new ContentLoader(gson));
		Leveling.setFullCurve(PetBattlesConfig.devFullXpCurve());
		roster = new RosterManager(db, new RosterStore(configManager, gson));
		atBankTracker = new AtBankTracker(client, () ->
		{
			if (panel != null)
			{
				panel.refresh();
			}
		});
		roster.setTeamEditGate(atBankTracker::isAtBank);
		nearTrainerTracker = new NearTrainerTracker(client, db, () ->
		{
			if (panel != null)
			{
				panel.refresh();
			}
		});
		restOverlay = new RestOverlay();
		panel = new PetBattlesPanel(db, roster, sprites, this::startTrainerBattle,
			() -> restOverlay.play(), nearTrainerTracker::isNear);
		session = new BattleSession(client, db, roster, config, () -> panel.refresh());
		overlay = new BattleOverlay(session, sprites, new PetChatheads(), new Portraits());
		inputHandler = new BattleInputHandler(session, overlay, clientThread);
		keyListener = new BattleKeyListener(client, session, clientThread);
		hubOverlay = new HubOverlay(db, roster, sprites, new Portraits(), session,
			atBankTracker::isAtBank, nearTrainerTracker::getNearTrainerIds);
		hubInputHandler = new HubInputHandler(hubOverlay, roster, session,
			this::startTrainerBattle, this::locateTrainer, this::examineItem, restOverlay::play, clientThread);
		hubKeyListener = new HubKeyListener(hubOverlay, session, clientThread);
		overlayManager.add(overlay);
		overlayManager.add(restOverlay);
		overlayManager.add(hubOverlay);
		mouseManager.registerMouseListener(inputHandler);
		mouseManager.registerMouseListener(hubInputHandler);
		mouseManager.registerMouseWheelListener(hubInputHandler);
		keyManager.registerKeyListener(keyListener);
		keyManager.registerKeyListener(hubKeyListener);

		Runnable refreshPanel = () -> panel.refresh();
		collectionLogSync = new CollectionLogSync(client, db, roster, refreshPanel);
		followerTracker = new FollowerTracker(client, db, roster, refreshPanel);
		heldItemTracker = new HeldItemTracker(client, db, roster, refreshPanel);
		killXpTracker = new KillXpTracker(client, db, roster, followerTracker, config, refreshPanel);
		easterEggTracker = new EasterEggTracker(client, db, roster, followerTracker, refreshPanel);
		eventBus.register(atBankTracker);
		eventBus.register(nearTrainerTracker);
		eventBus.register(collectionLogSync);
		eventBus.register(followerTracker);
		eventBus.register(heldItemTracker);
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
		log.debug("Pet Battles started with {} species, {} moves, {} trainers (dev mode: {})",
			db.allSpecies().size(), db.allMoves().size(), db.allTrainers().size(), PetBattlesConfig.DEV);
	}

	@Override
	protected void shutDown() throws Exception
	{
		clientToolbar.removeNavigation(navButton);
		clearLocatePins();
		mapPinImage = null;
		if (collectionLogSync != null)
		{
			eventBus.unregister(atBankTracker);
			eventBus.unregister(nearTrainerTracker);
			eventBus.unregister(collectionLogSync);
			eventBus.unregister(followerTracker);
			eventBus.unregister(heldItemTracker);
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
		if (hubOverlay != null)
		{
			overlayManager.remove(hubOverlay);
		}
		if (hubInputHandler != null)
		{
			mouseManager.unregisterMouseListener(hubInputHandler);
			mouseManager.unregisterMouseWheelListener(hubInputHandler);
		}
		if (inputHandler != null)
		{
			mouseManager.unregisterMouseListener(inputHandler);
		}
		if (keyListener != null)
		{
			keyManager.unregisterKeyListener(keyListener);
		}
		if (hubKeyListener != null)
		{
			keyManager.unregisterKeyListener(hubKeyListener);
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
		hubOverlay = null;
		hubInputHandler = null;
		hubKeyListener = null;
		inputHandler = null;
		keyListener = null;
		atBankTracker = null;
		nearTrainerTracker = null;
		collectionLogSync = null;
		followerTracker = null;
		heldItemTracker = null;
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
			Leveling.setFullCurve(PetBattlesConfig.devFullXpCurve());
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
				// Most likely cause: empty or fully-fainted team
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

	/**
	 * Drop a "Locate" pin on the world map at every known location of this trainer and centre the
	 * map on the one nearest the player. Pins persist so the player sees them when they open the
	 * map; the next Locate replaces them. No-op for trainers with no known location. Runs on the
	 * client thread (dispatched from the hub input handler).
	 */
	private void locateTrainer(String trainerId)
	{
		TrainerDef trainer = db.trainer(trainerId);
		if (trainer == null || trainer.getLocations().isEmpty())
		{
			return;
		}
		clearLocatePins();
		if (mapPinImage == null)
		{
			mapPinImage = buildMapPin();
		}
		WorldPoint me = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
		WorldPoint nearest = null;
		int nearestDist = Integer.MAX_VALUE;
		for (TrainerDef.Location loc : trainer.getLocations())
		{
			WorldPoint wp = new WorldPoint(loc.getX(), loc.getY(), loc.getPlane());
			WorldMapPoint pin = new WorldMapPoint(wp, mapPinImage);
			pin.setName(trainer.getName());
			pin.setTarget(wp);
			pin.setJumpOnClick(true);
			pin.setSnapToEdge(true);
			worldMapPointManager.add(pin);
			locatePins.add(pin);
			int dist = me != null && me.getPlane() == wp.getPlane() ? me.distanceTo(wp) : Integer.MAX_VALUE;
			if (nearest == null || dist < nearestDist)
			{
				nearest = wp;
				nearestDist = dist;
			}
		}
		// getWorldMap() is null until the map widget has initialised; the pins still register, and
		// centring is a best-effort nicety, so just skip it when unavailable.
		if (client.getWorldMap() != null)
		{
			client.getWorldMap().setWorldMapPositionTarget(nearest);
		}
		int count = trainer.getLocations().size();
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			"<col=ff7700>Pet Battles:</col> " + trainer.getName() + " marked on your world map"
				+ (count > 1 ? " (" + count + " locations)." : "."), null);
	}

	private void clearLocatePins()
	{
		for (WorldMapPoint pin : locatePins)
		{
			worldMapPointManager.remove(pin);
		}
		locatePins.clear();
	}

	/**
	 * Print a held item's examine text to the chatbox, like a RuneScape item examine. Dispatched
	 * from the hub's Items grid on the client thread, so it may touch the client directly.
	 */
	private void examineItem(String itemId)
	{
		Item item = Item.byId(itemId);
		if (item == null)
		{
			return;
		}
		String body = roster.ownsItem(item) ? item.getDescription() : "You haven't earned this yet.";
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			"<col=ff7700>" + item.getName() + ":</col> " + body, null);
	}

	/**
	 * The world-map marker. The bare paw icon washes out against the tan map, so it's composited
	 * onto a bright, dark-outlined disc and the paw is redrawn as a solid dark silhouette on top —
	 * high-contrast at any map zoom regardless of the source icon's own colours.
	 */
	private BufferedImage buildMapPin()
	{
		BufferedImage paw = ImageUtil.loadImageResource(getClass(), "/com/petbattles/icons/panel_icon.png");
		int size = 28;
		BufferedImage pin = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = pin.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		int inset = 3;
		int d = size - inset * 2;
		g.setColor(new Color(240, 170, 40));
		g.fillOval(inset, inset, d, d);
		g.setColor(new Color(25, 20, 10));
		g.setStroke(new BasicStroke(2.5f));
		g.drawOval(inset, inset, d, d);
		if (paw != null)
		{
			BufferedImage silhouette = ImageUtil.recolorImage(paw, new Color(25, 20, 10));
			int inner = d - 6;
			g.drawImage(silhouette, (size - inner) / 2, (size - inner) / 2, inner, inner, null);
		}
		g.dispose();
		return pin;
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
