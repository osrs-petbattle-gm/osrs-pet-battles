package com.petbattles.ui;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JLabel;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Bridges item icons onto Swing labels. Item images must be requested on the client
 * thread; AsyncBufferedImage repaints the target when the sprite arrives.
 */
@Singleton
public class Sprites
{
	private final ItemManager itemManager;
	private final ClientThread clientThread;

	@Inject
	public Sprites(ItemManager itemManager, ClientThread clientThread)
	{
		this.itemManager = itemManager;
		this.clientThread = clientThread;
	}

	public void applyItemIcon(JLabel label, int itemId)
	{
		clientThread.invokeLater(() ->
		{
			AsyncBufferedImage img = itemManager.getImage(itemId);
			img.addTo(label);
		});
	}

	/**
	 * Fetch the raw async image for overlay use (caller may keep the reference; it
	 * fills in once loaded).
	 */
	public AsyncBufferedImage itemImage(int itemId)
	{
		return itemManager.getImage(itemId);
	}
}
