package com.petbattles.ui;

import com.petbattles.quest.QuestDialogSession;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The quest dialog frame: a movable card above the chatbox that carries every quest conversation
 * outside of battle. It shows nothing at all until the player has a beat waiting, then a compact
 * prompt pill ("Talk to Hans"); clicking that expands it into the full dialog — chathead, line,
 * Continue or replies — and finally the chapter's Challenge. All of that is decided by
 * {@link QuestDialogSession} and drawn by {@link QuestDialogView}; this class supplies only the
 * overlay chrome and the per-frame button list that {@link QuestDialogInputHandler} hit-tests.
 *
 * <p>The session keeps the frame off the screen for as long as a battle window is up — the fight, its
 * forget-a-move prompts and the level-up summary all play out first — and then hands over any payoff
 * conversation the battle earned.
 */
public class QuestDialogOverlay extends Overlay
{
	private static final Color PANEL_BG = new Color(20, 24, 28, 235);
	private static final Color PANEL_EDGE = new Color(90, 75, 40);

	/** A clickable region, in overlay-local coordinates. */
	public static class Button
	{
		public final Rectangle rect;
		public final String action;

		Button(Rectangle rect, String action)
		{
			this.rect = rect;
			this.action = action;
		}
	}

	private final QuestDialogSession dialog;
	private final QuestDialogView view;
	private final List<Button> buttons = new ArrayList<>();

	public QuestDialogOverlay(QuestDialogSession dialog, Portraits portraits)
	{
		this.dialog = dialog;
		this.view = new QuestDialogView(portraits::portrait);
		setPosition(OverlayPosition.ABOVE_CHATBOX_RIGHT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
	}

	/** Current buttons in overlay-local space; the input handler translates using getBounds(). */
	public synchronized List<Button> getButtons()
	{
		return new ArrayList<>(buttons);
	}

	public void setHoverPoint(Point localPoint)
	{
		view.setHoverPoint(localPoint);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		QuestDialogSession.Scene scene = dialog.current();
		if (scene == null)
		{
			synchronized (this)
			{
				buttons.clear();
			}
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		List<Button> newButtons = new ArrayList<>();
		Dimension size;
		if (scene.getKind() == QuestDialogSession.Kind.PROMPT)
		{
			size = new Dimension(QuestDialogView.PROMPT_WIDTH, QuestDialogView.PROMPT_HEIGHT);
			drawPanel(g, size);
			view.drawPrompt(g, scene, (r, action) -> newButtons.add(new Button(r, action)));
		}
		else
		{
			size = new Dimension(QuestDialogView.WIDTH, view.measure(g, QuestDialogView.WIDTH, scene));
			drawPanel(g, size);
			view.draw(g, 0, 0, size.width, scene, (r, action) -> newButtons.add(new Button(r, action)),
				-1, true);
		}
		synchronized (this)
		{
			buttons.clear();
			buttons.addAll(newButtons);
		}
		return size;
	}

	private void drawPanel(Graphics2D g, Dimension size)
	{
		g.setColor(PANEL_BG);
		g.fillRoundRect(0, 0, size.width, size.height, 10, 10);
		g.setColor(PANEL_EDGE);
		g.setStroke(new BasicStroke(2));
		g.drawRoundRect(0, 0, size.width, size.height, 10, 10);
	}
}
