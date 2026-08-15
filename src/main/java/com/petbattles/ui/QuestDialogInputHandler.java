package com.petbattles.ui;

import com.petbattles.quest.QuestDialogSession;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.MouseAdapter;

/**
 * Routes canvas clicks on the {@link QuestDialogOverlay} to its buttons, mirroring
 * {@link HubInputHandler}. Mouse events arrive on the AWT thread; conversation state changes are
 * marshalled onto the client thread. Clicks that land on the frame are consumed so they don't leak
 * into the world; when the frame is drawing nothing it has no buttons, so nothing here fires.
 */
public class QuestDialogInputHandler extends MouseAdapter
{
	private final QuestDialogOverlay overlay;
	private final QuestDialogSession dialog;
	private final ClientThread clientThread;
	private final Consumer<String> fightAction;

	public QuestDialogInputHandler(QuestDialogOverlay overlay, QuestDialogSession dialog,
		ClientThread clientThread, Consumer<String> fightAction)
	{
		this.overlay = overlay;
		this.dialog = dialog;
		this.clientThread = clientThread;
		this.fightAction = fightAction;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		// Alt+click is overlay repositioning. An empty button list means the frame drew nothing this
		// frame, so its bounds are stale and the click isn't ours.
		List<QuestDialogOverlay.Button> hits = overlay.getButtons();
		if (hits.isEmpty() || e.getButton() != MouseEvent.BUTTON1 || e.isAltDown())
		{
			return e;
		}
		Rectangle bounds = overlay.getBounds();
		if (bounds == null || !bounds.contains(e.getPoint()))
		{
			return e;
		}
		Point local = new Point(e.getX() - bounds.x, e.getY() - bounds.y);
		for (QuestDialogOverlay.Button button : hits)
		{
			if (button.rect.contains(local))
			{
				String action = button.action;
				clientThread.invokeLater(() -> dispatch(action));
				break;
			}
		}
		// Swallow every click on the frame so it never falls through to the game world.
		e.consume();
		return e;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e)
	{
		Rectangle bounds = overlay.getButtons().isEmpty() ? null : overlay.getBounds();
		overlay.setHoverPoint(bounds != null && bounds.contains(e.getPoint())
			? new Point(e.getX() - bounds.x, e.getY() - bounds.y) : null);
		return e;
	}

	private void dispatch(String action)
	{
		if (QuestDialogSession.ACTION_OPEN.equals(action))
		{
			dialog.open();
		}
		else if (QuestDialogSession.ACTION_DISMISS.equals(action))
		{
			dialog.dismiss();
		}
		else if (QuestDialogSession.ACTION_CONTINUE.equals(action))
		{
			dialog.advance();
		}
		else if (action.startsWith(QuestDialogSession.ACTION_PICK))
		{
			dialog.pick(Integer.parseInt(action.substring(QuestDialogSession.ACTION_PICK.length())));
		}
		else if (QuestDialogSession.ACTION_DONE.equals(action))
		{
			dialog.completeTalk();
		}
		else if (action.startsWith(QuestDialogSession.ACTION_FIGHT))
		{
			fightAction.accept(action.substring(QuestDialogSession.ACTION_FIGHT.length()));
		}
	}
}
