package com.petbattles.ui;

import com.petbattles.battle.BattleSession;
import com.petbattles.engine.BattleEvent;
import com.petbattles.engine.BattlePet;
import com.petbattles.engine.BattleState;
import com.petbattles.engine.MoveDef;
import com.petbattles.engine.PetType;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The battle scene: enemy pet top-right, player pet bottom-left, HP bars, battle log,
 * and a 2x2 grid of move buttons plus Run/Close. Buttons cache their local-space
 * rectangles each frame; BattleInputHandler hit-tests against them.
 */
public class BattleOverlay extends Overlay
{
	public static final int WIDTH = 340;
	public static final int HEIGHT = 250;

	private static final Color PANEL_BG = new Color(20, 24, 28, 235);
	private static final Color PANEL_EDGE = new Color(90, 75, 40);
	private static final Color BUTTON_BG = new Color(45, 50, 58);
	private static final Color BUTTON_HOVER = new Color(70, 78, 90);
	private static final Color BUTTON_EDGE = new Color(120, 110, 80);
	private static final Color HP_GREEN = new Color(60, 180, 75);
	private static final Color HP_YELLOW = new Color(220, 180, 40);
	private static final Color HP_RED = new Color(200, 60, 50);
	private static final Color LOG_TEXT = new Color(230, 225, 210);

	/**
	 * A clickable region, in overlay-local coordinates.
	 */
	public static class Button
	{
		public final Rectangle rect;
		public final String action; // "move:N", "flee", "close"

		Button(Rectangle rect, String action)
		{
			this.rect = rect;
			this.action = action;
		}
	}

	private final BattleSession session;
	private final Sprites sprites;
	private final List<Button> buttons = new ArrayList<>();
	private Point hoverPoint;

	public BattleOverlay(BattleSession session, Sprites sprites)
	{
		this.session = session;
		this.sprites = sprites;
		setPosition(OverlayPosition.TOP_CENTER);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
	}

	/**
	 * Current buttons in overlay-local space; the input handler translates using getBounds().
	 */
	public synchronized List<Button> getButtons()
	{
		return new ArrayList<>(buttons);
	}

	public void setHoverPoint(Point localPoint)
	{
		this.hoverPoint = localPoint;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!session.isActive())
		{
			synchronized (this)
			{
				buttons.clear();
			}
			return null;
		}
		BattleState state = session.getState();
		if (state == null)
		{
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

		// Frame
		g.setColor(PANEL_BG);
		g.fillRoundRect(0, 0, WIDTH, HEIGHT, 10, 10);
		g.setColor(PANEL_EDGE);
		g.setStroke(new BasicStroke(2));
		g.drawRoundRect(0, 0, WIDTH, HEIGHT, 10, 10);

		BattlePet enemy = state.active(BattleState.ENEMY);
		BattlePet player = state.active(BattleState.PLAYER);

		// Trainer name banner
		g.setFont(FontManager.getRunescapeSmallFont());
		g.setColor(new Color(200, 190, 160));
		String title = session.getTrainer() != null ? "vs " + session.getTrainer().getName() : "Battle";
		g.drawString(title, 10, 14);

		// Enemy: info card top-left, sprite top-right
		drawPetInfo(g, enemy, 10, 22, false);
		drawPetSprite(g, enemy, WIDTH - 96, 24, 72);

		// Player: sprite mid-left, info card mid-right
		drawPetSprite(g, player, 24, 88, 72);
		drawPetInfo(g, player, WIDTH - 150, 92, true);

		List<Button> newButtons = new ArrayList<>();
		if (session.getPhase() == BattleSession.Phase.ANIMATING)
		{
			// Turn is resolving: no move grid, just a wide dialog box with the current line
			drawDialogBox(g);
		}
		else
		{
			// Log strip
			int logY = 168;
			g.setColor(new Color(0, 0, 0, 120));
			g.fillRect(6, logY - 12, WIDTH - 12, 46);
			g.setFont(FontManager.getRunescapeSmallFont());
			g.setColor(LOG_TEXT);
			int y = logY;
			for (String line : session.getVisibleLog())
			{
				g.drawString(clip(g, line, WIDTH - 24), 12, y);
				y += 11;
			}

			// Buttons
			int btnY = HEIGHT - 44;
			if (session.getPhase() == BattleSession.Phase.ENDED)
			{
				Rectangle close = new Rectangle(WIDTH / 2 - 40, btnY + 8, 80, 24);
				drawButton(g, close, "Close", true, null);
				newButtons.add(new Button(close, "close"));
			}
			else
			{
				boolean enabled = session.isAwaitingInput();
				List<MoveDef> moves = player.getMoves();
				int bw = (WIDTH - 24 - 8) / 2;
				int bh = 18;
				for (int i = 0; i < 4; i++)
				{
					int col = i % 2;
					int row = i / 2;
					Rectangle r = new Rectangle(12 + col * (bw + 8), btnY + row * (bh + 4), bw, bh);
					if (i < moves.size())
					{
						MoveDef m = moves.get(i);
						drawButton(g, r, m.getName(), enabled, new Color(m.getType().getColorRgb()));
						newButtons.add(new Button(r, "move:" + i));
					}
					else
					{
						drawButton(g, r, "—", false, null);
					}
				}
				Rectangle flee = new Rectangle(WIDTH - 60, 4, 50, 14);
				drawButton(g, flee, "Run", enabled, null);
				newButtons.add(new Button(flee, "flee"));
			}
		}
		synchronized (this)
		{
			buttons.clear();
			buttons.addAll(newButtons);
		}

		return new Dimension(WIDTH, HEIGHT);
	}

	/**
	 * Quest-dialog style text box shown while the turn plays out. In manual-advance
	 * mode a blinking chevron invites the next click / Space press.
	 */
	private void drawDialogBox(Graphics2D g)
	{
		int boxY = 156;
		int boxH = HEIGHT - boxY - 8;
		g.setColor(new Color(0, 0, 0, 170));
		g.fillRoundRect(6, boxY, WIDTH - 12, boxH, 8, 8);
		g.setColor(PANEL_EDGE);
		g.setStroke(new BasicStroke(1));
		g.drawRoundRect(6, boxY, WIDTH - 12, boxH, 8, 8);

		BattleEvent event = session.getCurrentEvent();
		if (event != null)
		{
			g.setFont(FontManager.getRunescapeFont());
			g.setColor(LOG_TEXT);
			int textY = boxY + 22;
			for (String line : wrap(g, event.getText(), WIDTH - 40))
			{
				g.drawString(line, 16, textY);
				textY += 16;
				if (textY > boxY + boxH - 6)
				{
					break;
				}
			}
		}

		// Blinking "continue" chevron once the line's animation has played out
		if (session.isManualAdvance() && session.getAnimationProgress() >= 1f
			&& (System.currentTimeMillis() / 450) % 2 == 0)
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			g.setColor(new Color(240, 220, 120));
			g.drawString("▼", WIDTH - 26, boxY + boxH - 8);
		}
	}

	private static List<String> wrap(Graphics2D g, String text, int maxWidth)
	{
		FontMetrics fm = g.getFontMetrics();
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(candidate) > maxWidth && line.length() > 0)
			{
				lines.add(line.toString());
				line = new StringBuilder(word);
			}
			else
			{
				line = new StringBuilder(candidate);
			}
		}
		if (line.length() > 0)
		{
			lines.add(line.toString());
		}
		return lines;
	}

	private void drawPetInfo(Graphics2D g, BattlePet pet, int x, int y, boolean showExactHp)
	{
		int w = 140;
		g.setColor(new Color(0, 0, 0, 130));
		g.fillRoundRect(x, y, w, showExactHp ? 44 : 36, 8, 8);

		g.setFont(FontManager.getRunescapeSmallFont());
		g.setColor(Color.WHITE);
		g.drawString(clip(g, pet.getDisplayName(), w - 40), x + 6, y + 13);
		g.setColor(new Color(200, 190, 160));
		String lv = "Lv" + pet.getLevel();
		g.drawString(lv, x + w - g.getFontMetrics().stringWidth(lv) - 6, y + 13);

		// Types
		int tx = x + 6;
		for (PetType type : pet.getSpecies().getTypes())
		{
			g.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.PLAIN, 9f));
			FontMetrics fm = g.getFontMetrics();
			int tw = fm.stringWidth(type.getDisplayName()) + 6;
			g.setColor(new Color(type.getColorRgb()));
			g.fillRoundRect(tx, y + 17, tw, 10, 4, 4);
			g.setColor(Color.WHITE);
			g.drawString(type.getDisplayName(), tx + 3, y + 25);
			tx += tw + 3;
		}
		// Status tag
		if (pet.getStatus() != BattlePet.Status.NONE)
		{
			g.setColor(HP_RED);
			g.drawString(pet.getStatus().name(), tx + 2, y + 25);
		}

		// HP bar
		int barY = y + 30;
		int barW = w - 12;
		double frac = pet.getMaxHp() > 0 ? (double) pet.getCurrentHp() / pet.getMaxHp() : 0;
		g.setColor(new Color(40, 40, 40));
		g.fillRect(x + 6, barY, barW, 5);
		g.setColor(frac > 0.5 ? HP_GREEN : frac > 0.2 ? HP_YELLOW : HP_RED);
		g.fillRect(x + 6, barY, (int) (barW * frac), 5);
		if (showExactHp)
		{
			g.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.PLAIN, 9f));
			g.setColor(Color.WHITE);
			g.drawString(pet.getCurrentHp() + " / " + pet.getMaxHp(), x + 6, barY + 13);
		}
	}

	private void drawPetSprite(Graphics2D g, BattlePet pet, int x, int y, int size)
	{
		Image img = sprites.itemImage(pet.getSpecies().getItemId());
		if (img != null)
		{
			g.drawImage(img, x, y, size, size, null);
		}
		if (pet.isFainted())
		{
			g.setColor(new Color(0, 0, 0, 140));
			g.fillRect(x, y, size, size);
		}
	}

	private void drawButton(Graphics2D g, Rectangle r, String label, boolean enabled, Color accent)
	{
		boolean hover = enabled && hoverPoint != null && r.contains(hoverPoint);
		g.setColor(hover ? BUTTON_HOVER : BUTTON_BG);
		g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
		g.setColor(enabled ? BUTTON_EDGE : new Color(70, 70, 70));
		g.setStroke(new BasicStroke(1));
		g.drawRoundRect(r.x, r.y, r.width, r.height, 6, 6);
		if (accent != null)
		{
			g.setColor(accent);
			g.fillRect(r.x + 3, r.y + 3, 4, r.height - 6);
		}
		g.setFont(FontManager.getRunescapeSmallFont());
		g.setColor(enabled ? Color.WHITE : new Color(140, 140, 140));
		FontMetrics fm = g.getFontMetrics();
		String text = clip(g, label, r.width - 14);
		g.drawString(text, r.x + (r.width - fm.stringWidth(text)) / 2 + (accent != null ? 3 : 0),
			r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
	}

	private static String clip(Graphics2D g, String text, int maxWidth)
	{
		FontMetrics fm = g.getFontMetrics();
		if (fm.stringWidth(text) <= maxWidth)
		{
			return text;
		}
		String ellipsis = "…";
		StringBuilder sb = new StringBuilder();
		for (char c : text.toCharArray())
		{
			if (fm.stringWidth(sb.toString() + c + ellipsis) > maxWidth)
			{
				break;
			}
			sb.append(c);
		}
		return sb + ellipsis;
	}
}
