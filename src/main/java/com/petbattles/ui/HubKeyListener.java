package com.petbattles.ui;

import com.petbattles.battle.BattleSession;
import java.awt.event.KeyEvent;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyListener;

/**
 * Feeds typed characters into the hub's Trainers name-search box while it has focus. It only ever
 * reacts to real keypresses, and only while the box is focused — every key passes straight through
 * untouched otherwise, so it never steals input from the game (or generates any).
 */
public class HubKeyListener implements KeyListener
{
	private final HubView view;
	private final BattleSession session;
	private final ClientThread clientThread;

	public HubKeyListener(HubView view, BattleSession session, ClientThread clientThread)
	{
		this.view = view;
		this.session = session;
		this.clientThread = clientThread;
	}

	private boolean active()
	{
		return view.isSearchFocused() && !session.isActive();
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
		if (!active())
		{
			return;
		}
		char c = e.getKeyChar();
		if (c != KeyEvent.CHAR_UNDEFINED && c >= ' ' && !Character.isISOControl(c))
		{
			clientThread.invokeLater(() -> view.appendSearch(c));
			e.consume();
		}
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (!active())
		{
			return;
		}
		switch (e.getKeyCode())
		{
			case KeyEvent.VK_BACK_SPACE:
				clientThread.invokeLater(view::backspaceSearch);
				e.consume();
				break;
			case KeyEvent.VK_ESCAPE:
			case KeyEvent.VK_ENTER:
				clientThread.invokeLater(view::blurSearch);
				e.consume();
				break;
			default:
				break;
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
	}
}
