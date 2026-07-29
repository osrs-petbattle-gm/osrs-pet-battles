package com.petbattles.ui;

import com.petbattles.battle.BattleSession;
import com.petbattles.engine.TrainerDef;
import com.petbattles.persist.RosterManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.MouseAdapter;

/**
 * Routes canvas clicks to the hub overlay's buttons, mirroring
 * {@link com.petbattles.battle.BattleInputHandler}. Mouse events arrive on the AWT
 * thread; overlay state changes and roster mutations are marshalled onto the client
 * thread. Clicks that land on the hub are consumed so they don't leak into the world.
 * While a battle is running the hub is hidden, so nothing here fires.
 */
public class HubInputHandler extends MouseAdapter
{
	private final HubOverlay overlay;
	private final RosterManager roster;
	private final BattleSession session;
	private final Consumer<String> fightAction;
	private final Consumer<String> locateAction;
	private final Runnable onRest;
	private final ClientThread clientThread;

	public HubInputHandler(HubOverlay overlay, RosterManager roster, BattleSession session,
		Consumer<String> fightAction, Consumer<String> locateAction, Runnable onRest, ClientThread clientThread)
	{
		this.overlay = overlay;
		this.roster = roster;
		this.session = session;
		this.fightAction = fightAction;
		this.locateAction = locateAction;
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
				clientThread.invokeLater(() -> dispatch(action));
				break;
			}
		}
		// Swallow every click on the hub so it never falls through to the game world.
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

	private void dispatch(String action)
	{
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
		else if ("open:rest".equals(action))
		{
			overlay.openPane(HubOverlay.Pane.REST);
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
		else if (action.startsWith("quest:"))
		{
			overlay.toggleQuest(action.substring(6));
		}
		else if (action.startsWith("trainers.filter:"))
		{
			String value = action.substring(16);
			overlay.setTrainerFilter("ALL".equals(value) ? null : TrainerDef.Difficulty.valueOf(value));
		}
		else if ("trainers.page:1".equals(action))
		{
			overlay.trainersPage(+1);
		}
		else if ("trainers.page:-1".equals(action))
		{
			overlay.trainersPage(-1);
		}
		else if (action.startsWith("locate:"))
		{
			locateAction.accept(action.substring(7));
		}
		else if (action.startsWith("team.up:"))
		{
			roster.moveTeamMember(action.substring(8), -1);
		}
		else if (action.startsWith("team.down:"))
		{
			roster.moveTeamMember(action.substring(10), +1);
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
