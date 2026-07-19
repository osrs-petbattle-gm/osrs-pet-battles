package com.petbattles.battle;

import java.awt.event.KeyEvent;
import net.runelite.api.Client;
import net.runelite.api.VarClientStr;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyListener;

/**
 * Consumes Space to advance battle text while a battle is on-screen in
 * manual-advance mode. We only react to real keypresses — never generate them.
 */
public class BattleKeyListener implements KeyListener
{
	private final Client client;
	private final BattleSession session;
	private final ClientThread clientThread;

	public BattleKeyListener(Client client, BattleSession session, ClientThread clientThread)
	{
		this.client = client;
		this.session = session;
		this.clientThread = clientThread;
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (e.getKeyCode() != KeyEvent.VK_SPACE)
		{
			return;
		}
		if (!session.isActive() || !session.isManualAdvance()
			|| session.getPhase() != BattleSession.Phase.ANIMATING)
		{
			return;
		}
		// Don't steal Space from a chat message the player is typing
		String typed = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);
		if (typed != null && !typed.isEmpty())
		{
			return;
		}
		e.consume();
		clientThread.invokeLater(session::advance);
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
	}
}
