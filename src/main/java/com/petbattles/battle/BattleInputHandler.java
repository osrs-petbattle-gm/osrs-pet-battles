package com.petbattles.battle;

import com.petbattles.ui.BattleOverlay;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.MouseAdapter;

/**
 * Routes canvas clicks to the battle overlay's buttons. Mouse events arrive on the
 * AWT thread; session mutations are marshalled onto the client thread. Clicks on
 * overlay buttons are consumed so they don't leak into the game world.
 */
public class BattleInputHandler extends MouseAdapter
{
	private final BattleSession session;
	private final BattleOverlay overlay;
	private final ClientThread clientThread;

	public BattleInputHandler(BattleSession session, BattleOverlay overlay, ClientThread clientThread)
	{
		this.session = session;
		this.overlay = overlay;
		this.clientThread = clientThread;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		if (!session.isActive() || e.getButton() != MouseEvent.BUTTON1)
		{
			return e;
		}
		Rectangle bounds = overlay.getBounds();
		if (bounds == null || !bounds.contains(e.getPoint()))
		{
			return e;
		}
		Point local = new Point(e.getX() - bounds.x, e.getY() - bounds.y);
		for (BattleOverlay.Button button : overlay.getButtons())
		{
			if (button.rect.contains(local))
			{
				String action = button.action;
				clientThread.invokeLater(() -> dispatch(action));
				e.consume();
				return e;
			}
		}
		// Swallow clicks anywhere on the battle window so they don't hit the game world
		e.consume();
		return e;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e)
	{
		if (!session.isActive())
		{
			return e;
		}
		Rectangle bounds = overlay.getBounds();
		if (bounds != null && bounds.contains(e.getPoint()))
		{
			overlay.setHoverPoint(new Point(e.getX() - bounds.x, e.getY() - bounds.y));
		}
		else
		{
			overlay.setHoverPoint(null);
		}
		return e;
	}

	private void dispatch(String action)
	{
		if (action.startsWith("move:"))
		{
			session.submitMove(Integer.parseInt(action.substring(5)));
		}
		else if ("flee".equals(action))
		{
			session.submitFlee();
		}
		else if ("close".equals(action))
		{
			session.close();
		}
	}
}
