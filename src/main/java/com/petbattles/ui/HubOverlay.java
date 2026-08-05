package com.petbattles.ui;

import com.petbattles.battle.BattleSession;
import com.petbattles.data.PetDatabase;
import com.petbattles.persist.RosterManager;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

/**
 * Floating adapter around a {@link HubView}: a movable RuneLite overlay pinned bottom-left that
 * collapses to a launcher chip and auto-opens world-context panes. All state and drawing live in the
 * shared {@link HubView}; this class only supplies the overlay chrome (position, layer, movability)
 * and hands its {@code Graphics2D} to the view each frame. Input is routed by {@link HubInputHandler}
 * against {@link #getView()} and the overlay's {@link #getBounds() rendered bounds}.
 */
public class HubOverlay extends Overlay
{
	// Fixed floating width, matched to an OSRS interface panel.
	private static final int WIDTH = 234;

	private final HubView view;

	public HubOverlay(PetDatabase db, RosterManager roster, Sprites sprites, Portraits portraits,
		BattleSession session, BooleanSupplier atBank, Supplier<Set<String>> nearTrainers,
		TooltipManager tooltipManager)
	{
		this.view = new HubView(db, roster, sprites, portraits, session, atBank, nearTrainers,
			tooltipManager, WIDTH, false);
		setPosition(OverlayPosition.BOTTOM_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
	}

	/** The shared hub state/renderer this overlay draws; the input handlers drive it directly. */
	public HubView getView()
	{
		return view;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		return view.render(g);
	}
}
