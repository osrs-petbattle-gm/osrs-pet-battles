package com.petbattles.ui;

import com.petbattles.battle.BattleSession;
import com.petbattles.persist.RosterManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.function.Supplier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseWheelListener;

/**
 * Routes canvas clicks on the floating {@link HubOverlay} to its {@link HubView}'s buttons,
 * mirroring {@link com.petbattles.battle.BattleInputHandler}. Mouse events arrive on the AWT
 * thread; overlay state changes and roster mutations are marshalled onto the client thread via
 * {@link HubActions}. Clicks that land on the hub are consumed so they don't leak into the world.
 * While a battle is running the hub is hidden, so nothing here fires.
 */
public class HubInputHandler extends MouseAdapter implements MouseWheelListener
{
	private final HubView view;
	private final HubActions actions;
	private final Supplier<Rectangle> bounds;
	private final RosterManager roster;
	private final BattleSession session;
	private final ClientThread clientThread;
	// The team species currently being dragged to reorder, or null. AWT-thread only.
	private String dragSpecies;
	// Overlay-local point where the drag began, for tap-vs-drag discrimination.
	private Point dragStart;

	public HubInputHandler(HubView view, HubActions actions, Supplier<Rectangle> bounds,
		RosterManager roster, BattleSession session, ClientThread clientThread)
	{
		this.view = view;
		this.actions = actions;
		this.bounds = bounds;
		this.roster = roster;
		this.session = session;
		this.clientThread = clientThread;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		// Hub is hidden during battle; Alt+click is overlay repositioning.
		if (session.isActive() || e.getButton() != MouseEvent.BUTTON1 || e.isAltDown())
		{
			return e;
		}
		Rectangle bnds = bounds.get();
		if (bnds == null || !bnds.contains(e.getPoint()))
		{
			return e;
		}
		Point local = new Point(e.getX() - bnds.x, e.getY() - bnds.y);
		for (HubView.Button button : view.getButtons())
		{
			if (button.rect.contains(local))
			{
				String action = button.action;
				if (action.startsWith("team.slot:"))
				{
					// Press on a team slot begins a drag-to-reorder; a tap (no movement) opens the
					// pet's detail pane on release instead.
					dragSpecies = action.substring("team.slot:".length());
					dragStart = local;
					view.beginTeamDrag(dragSpecies, local);
				}
				else
				{
					clientThread.invokeLater(() -> actions.dispatch(action));
				}
				break;
			}
		}
		// Swallow every click on the hub so it never falls through to the game world.
		e.consume();
		return e;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent e)
	{
		if (dragSpecies == null || session.isActive())
		{
			return e;
		}
		Rectangle bnds = bounds.get();
		if (bnds != null)
		{
			view.updateDragPoint(new Point(e.getX() - bnds.x, e.getY() - bnds.y));
		}
		e.consume();
		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e)
	{
		if (dragSpecies == null)
		{
			return e;
		}
		String dragged = dragSpecies;
		Point start = dragStart;
		dragSpecies = null;
		dragStart = null;
		view.endTeamDrag();
		Rectangle bnds = bounds.get();
		if (bnds != null)
		{
			Point local = new Point(e.getX() - bnds.x, e.getY() - bnds.y);
			if (start != null && local.distance(start) < 5)
			{
				// A tap opens the pet's detail pane rather than reordering.
				clientThread.invokeLater(() -> actions.dispatch("open:pet:" + dragged));
			}
			else
			{
				int index = view.teamDropIndex(local.x);
				clientThread.invokeLater(() -> roster.reorderTeamToIndex(dragged, index));
			}
		}
		e.consume();
		return e;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e)
	{
		if (session.isActive())
		{
			view.setHoverPoint(null);
			return e;
		}
		Rectangle bnds = bounds.get();
		if (bnds != null && bnds.contains(e.getPoint()))
		{
			view.setHoverPoint(new Point(e.getX() - bnds.x, e.getY() - bnds.y));
		}
		else
		{
			view.setHoverPoint(null);
		}
		return e;
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent e)
	{
		if (session.isActive())
		{
			return e;
		}
		Rectangle bnds = bounds.get();
		if (bnds == null || !bnds.contains(e.getPoint()) || !view.isScrollablePaneOpen())
		{
			return e;
		}
		int rotation = e.getWheelRotation();
		if (rotation != 0)
		{
			// Scroll the hovered pane's list instead of zooming the game camera underneath.
			clientThread.invokeLater(() -> view.scroll(rotation));
			e.consume();
		}
		return e;
	}
}
