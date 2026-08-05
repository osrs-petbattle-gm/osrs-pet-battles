package com.petbattles.ui;

import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * The side-panel nav host. Its entire body is the shared {@link HubPanel} — the same hub the
 * {@link HubOverlay} draws, embedded in the drawer — so the two surfaces are one implementation.
 * The toolbar wraps this in a scroll pane; the hub sizes itself to its content height so the
 * scrollbar appears when a pane overflows.
 */
public class PetBattlesPanel extends PluginPanel
{
	private final HubPanel hub;

	public PetBattlesPanel(HubPanel hub)
	{
		this.hub = hub;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(hub, BorderLayout.CENTER);
	}

	/** Repaint the hub with fresh roster state; called by the plugin's trackers. */
	public void refresh()
	{
		hub.refresh();
	}
}
