package com.petbattles.bank;

import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Shared "is the player at a bank?" check: true while the bank interface is open or a
 * banker NPC is within a few tiles. Gates team composition changes and pet resting.
 * Bankers are tracked from spawn events (no scene scans); proximity is re-evaluated
 * once per game tick against that small set.
 */
public class AtBankTracker
{
	private static final int BANK_RADIUS_TILES = 10;

	private final Client client;
	private final Runnable onChanged;
	private final Set<NPC> bankers = new HashSet<>();
	private boolean bankInterfaceOpen;
	// Read from the Swing EDT by the panel; written on the client thread
	private volatile boolean atBank;

	public AtBankTracker(Client client, Runnable onChanged)
	{
		this.client = client;
		this.onChanged = onChanged;
	}

	public boolean isAtBank()
	{
		return atBank;
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned e)
	{
		if (isBanker(e.getNpc()))
		{
			bankers.add(e.getNpc());
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned e)
	{
		bankers.remove(e.getNpc());
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankInterfaceOpen = true;
			update();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (e.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankInterfaceOpen = false;
			update();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOADING || e.getGameState() == GameState.LOGIN_SCREEN
			|| e.getGameState() == GameState.HOPPING)
		{
			bankers.clear();
			bankInterfaceOpen = false;
			update();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		update();
	}

	private void update()
	{
		boolean now = bankInterfaceOpen || bankerNearby();
		if (now != atBank)
		{
			atBank = now;
			onChanged.run();
		}
	}

	private boolean bankerNearby()
	{
		if (bankers.isEmpty() || client.getLocalPlayer() == null)
		{
			return false;
		}
		WorldPoint me = client.getLocalPlayer().getWorldLocation();
		for (NPC banker : bankers)
		{
			WorldPoint p = banker.getWorldLocation();
			if (p != null && me.distanceTo(p) <= BANK_RADIUS_TILES)
			{
				return true;
			}
		}
		return false;
	}

	private static boolean isBanker(NPC npc)
	{
		NPCComposition comp = npc.getComposition();
		if (comp == null || comp.getActions() == null)
		{
			return false;
		}
		for (String action : comp.getActions())
		{
			if ("Bank".equalsIgnoreCase(action))
			{
				return true;
			}
		}
		return false;
	}
}
