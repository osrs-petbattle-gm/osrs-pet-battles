package com.petbattles.ui;

import com.petbattles.battle.BattleSession;
import com.petbattles.persist.RosterManager;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import javax.swing.JComponent;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;

/**
 * Docked adapter around a {@link HubView}: the same hub the {@link HubOverlay} draws, embedded as a
 * Swing component in the side panel. It paints the view into its own {@code Graphics2D}, sizes itself
 * to the returned height so the surrounding {@code PluginPanel} scrolls, and routes Swing mouse / key
 * events to the view and {@link HubActions} — the panel-side counterpart of {@link HubInputHandler}
 * plus {@link HubKeyListener}. A ~30fps timer repaints while showing so the caret, hover, drag
 * preview and live coins/HP animate, mirroring the overlay's continuous render.
 *
 * <p>Everything here runs on the EDT. Roster mutations are safe ({@link RosterManager} is
 * synchronised); the client-touching callbacks inside {@link HubActions} marshal themselves onto the
 * client thread.
 */
public class HubPanel extends JComponent
{
	private final HubView view;
	private final HubActions actions;
	private final RosterManager roster;
	private final BattleSession session;
	private final Timer repaintTimer;

	// Team drag-to-reorder gesture (EDT only), and the press point for tap-vs-drag discrimination.
	private String dragSpecies;
	private Point pressPoint;
	private int lastHeight = -1;

	public HubPanel(HubView view, HubActions actions, RosterManager roster, BattleSession session)
	{
		this.view = view;
		this.actions = actions;
		this.roster = roster;
		this.session = session;

		setOpaque(true);
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setFocusable(true);
		// Enable native (Swing) tooltips; the text is refreshed each paint from the view.
		setToolTipText("");

		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				requestFocusInWindow();
				if (session.isActive() || e.getButton() != MouseEvent.BUTTON1)
				{
					return;
				}
				pressPoint = e.getPoint();
				for (HubView.Button button : view.getButtons())
				{
					if (button.rect.contains(e.getPoint()))
					{
						String action = button.action;
						if (action.startsWith("team.slot:"))
						{
							dragSpecies = action.substring("team.slot:".length());
							view.beginTeamDrag(dragSpecies, e.getPoint());
						}
						else
						{
							actions.dispatch(action);
						}
						break;
					}
				}
				repaint();
			}

			@Override
			public void mouseDragged(MouseEvent e)
			{
				if (dragSpecies != null && !session.isActive())
				{
					view.updateDragPoint(e.getPoint());
					repaint();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (dragSpecies == null)
				{
					return;
				}
				String dragged = dragSpecies;
				dragSpecies = null;
				view.endTeamDrag();
				// A press-and-release without meaningful movement is a tap: open the pet's detail
				// pane instead of reordering. Otherwise drop it at the hovered team position.
				if (pressPoint != null && e.getPoint().distance(pressPoint) < 5)
				{
					actions.dispatch("open:pet:" + dragged);
				}
				else
				{
					roster.reorderTeamToIndex(dragged, view.teamDropIndex(e.getX()));
				}
				repaint();
			}

			@Override
			public void mouseMoved(MouseEvent e)
			{
				view.setHoverPoint(session.isActive() ? null : e.getPoint());
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				view.setHoverPoint(null);
			}

			@Override
			public void mouseWheelMoved(MouseWheelEvent e)
			{
				if (!session.isActive() && view.isScrollablePaneOpen() && e.getWheelRotation() != 0)
				{
					// Page the hovered list rather than scrolling the whole panel underneath.
					view.scroll(e.getWheelRotation());
					repaint();
					e.consume();
				}
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
		addMouseWheelListener(mouse);
		addKeyListener(new SearchKeyListener());

		repaintTimer = new Timer(33, e ->
		{
			if (isShowing())
			{
				repaint();
			}
		});
	}

	/** Rebuild-on-demand hook called by the plugin's trackers; a repaint re-reads all live state. */
	public void refresh()
	{
		repaint();
	}

	@Override
	public void addNotify()
	{
		super.addNotify();
		repaintTimer.start();
	}

	@Override
	public void removeNotify()
	{
		repaintTimer.stop();
		super.removeNotify();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		int w = getWidth();
		if (w <= 0)
		{
			return;
		}
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			view.setWidth(w);
			Dimension size = view.render(g2);
			int h = size != null ? size.height : 0;
			if (h != lastHeight)
			{
				lastHeight = h;
				setPreferredSize(new Dimension(w, h));
				revalidate();
			}
		}
		finally
		{
			g2.dispose();
		}
		String tip = view.getHoverTooltip();
		if (tip == null)
		{
			tip = "";
		}
		if (!tip.equals(getToolTipText()))
		{
			setToolTipText(tip);
		}
	}

	/** Feeds typed characters into the hub's search box while it is focused (EDT-local). */
	private final class SearchKeyListener implements KeyListener
	{
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
				view.appendSearch(c);
				repaint();
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
					view.backspaceSearch();
					repaint();
					e.consume();
					break;
				case KeyEvent.VK_ESCAPE:
				case KeyEvent.VK_ENTER:
					view.blurSearch();
					repaint();
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
}
