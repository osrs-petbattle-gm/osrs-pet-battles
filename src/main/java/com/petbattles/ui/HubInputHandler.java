package com.petbattles.ui;

import com.petbattles.battle.BattleSession;
import com.petbattles.engine.TrainerDef;
import com.petbattles.persist.RosterManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.function.Consumer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseWheelListener;

/**
 * Routes canvas clicks to the hub overlay's buttons, mirroring
 * {@link com.petbattles.battle.BattleInputHandler}. Mouse events arrive on the AWT
 * thread; overlay state changes and roster mutations are marshalled onto the client
 * thread. Clicks that land on the hub are consumed so they don't leak into the world.
 * While a battle is running the hub is hidden, so nothing here fires.
 */
public class HubInputHandler extends MouseAdapter implements MouseWheelListener
{
	private final HubOverlay overlay;
	private final RosterManager roster;
	private final BattleSession session;
	private final Consumer<String> fightAction;
	private final Consumer<String> locateAction;
	private final Consumer<String> examineAction;
	private final Runnable onRest;
	private final ClientThread clientThread;
	// The team species currently being dragged to reorder, or null. AWT-thread only.
	private String dragSpecies;

	public HubInputHandler(HubOverlay overlay, RosterManager roster, BattleSession session,
		Consumer<String> fightAction, Consumer<String> locateAction, Consumer<String> examineAction,
		Runnable onRest, ClientThread clientThread)
	{
		this.overlay = overlay;
		this.roster = roster;
		this.session = session;
		this.fightAction = fightAction;
		this.locateAction = locateAction;
		this.examineAction = examineAction;
		this.onRest = onRest;
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
		Rectangle bounds = overlay.getBounds();
		if (bounds == null || !bounds.contains(e.getPoint()))
		{
			return e;
		}
		Point local = new Point(e.getX() - bounds.x, e.getY() - bounds.y);
		for (HubOverlay.Button button : overlay.getButtons())
		{
			if (button.rect.contains(local))
			{
				String action = button.action;
				if (action.startsWith("team.slot:"))
				{
					// Press on a team slot begins a drag-to-reorder; the move happens on release.
					dragSpecies = action.substring("team.slot:".length());
					overlay.beginTeamDrag(dragSpecies, local);
				}
				else
				{
					clientThread.invokeLater(() -> dispatch(action));
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
		Rectangle bounds = overlay.getBounds();
		if (bounds != null)
		{
			overlay.updateDragPoint(new Point(e.getX() - bounds.x, e.getY() - bounds.y));
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
		dragSpecies = null;
		overlay.endTeamDrag();
		Rectangle bounds = overlay.getBounds();
		if (bounds != null)
		{
			int index = overlay.teamDropIndex(e.getX() - bounds.x);
			clientThread.invokeLater(() -> roster.reorderTeamToIndex(dragged, index));
		}
		e.consume();
		return e;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e)
	{
		if (session.isActive())
		{
			overlay.setHoverPoint(null);
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

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent e)
	{
		if (session.isActive())
		{
			return e;
		}
		Rectangle bounds = overlay.getBounds();
		if (bounds == null || !bounds.contains(e.getPoint()) || !overlay.isScrollablePaneOpen())
		{
			return e;
		}
		int rotation = e.getWheelRotation();
		if (rotation != 0)
		{
			// Scroll the hovered pane's list instead of zooming the game camera underneath.
			clientThread.invokeLater(() -> overlay.scroll(rotation));
			e.consume();
		}
		return e;
	}

	private void dispatch(String action)
	{
		// Any hub action other than typing into / clearing the search field drops its focus.
		if (!"trainers.search.focus".equals(action) && !"trainers.search.clear".equals(action))
		{
			overlay.blurSearch();
		}
		if ("chip".equals(action) || "menu".equals(action))
		{
			overlay.openMenu();
		}
		else if ("collapse".equals(action))
		{
			overlay.collapse();
		}
		else if ("open:team".equals(action))
		{
			overlay.openPane(HubOverlay.Pane.TEAM);
		}
		else if ("open:challenge".equals(action))
		{
			overlay.openPane(HubOverlay.Pane.CHALLENGE);
		}
		else if ("open:trainers".equals(action))
		{
			overlay.openPane(HubOverlay.Pane.TRAINERS);
		}
		else if ("open:quests".equals(action))
		{
			overlay.openPane(HubOverlay.Pane.QUESTS);
		}
		else if ("open:items".equals(action))
		{
			overlay.openPane(HubOverlay.Pane.ITEMS);
		}
		else if (action.startsWith("quest:"))
		{
			overlay.toggleQuest(action.substring(6));
		}
		else if (action.startsWith("trainers.filter:"))
		{
			String value = action.substring(16);
			if ("RANDOM".equals(value))
			{
				overlay.setRandomFilter();
			}
			else
			{
				overlay.setTrainerFilter("ALL".equals(value) ? null : TrainerDef.Difficulty.valueOf(value));
			}
		}
		else if ("trainers.page:1".equals(action))
		{
			overlay.trainersPage(+1);
		}
		else if ("trainers.page:-1".equals(action))
		{
			overlay.trainersPage(-1);
		}
		else if ("trainers.search.focus".equals(action))
		{
			overlay.focusSearch();
		}
		else if ("trainers.search.clear".equals(action))
		{
			overlay.clearSearch();
		}
		else if (action.startsWith("locate:"))
		{
			locateAction.accept(action.substring(7));
		}
		else if (action.startsWith("item.examine:"))
		{
			examineAction.accept(action.substring("item.examine:".length()));
		}
		else if (action.startsWith("team.remove:"))
		{
			roster.removeFromTeam(action.substring(12));
		}
		else if (action.startsWith("team.add:"))
		{
			roster.addToTeam(action.substring(9));
		}
		else if ("team.page:1".equals(action))
		{
			overlay.addPage(+1);
		}
		else if ("team.page:-1".equals(action))
		{
			overlay.addPage(-1);
		}
		else if ("rest".equals(action))
		{
			if (roster.restAllPets())
			{
				onRest.run();
			}
		}
		else if (action.startsWith("fight:"))
		{
			fightAction.accept(action.substring(6));
		}
		else if ("chal.page:1".equals(action))
		{
			overlay.challengePageDelta(+1);
		}
		else if ("chal.page:-1".equals(action))
		{
			overlay.challengePageDelta(-1);
		}
	}
}
