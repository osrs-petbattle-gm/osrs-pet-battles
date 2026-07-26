package com.petbattles.ui;

import com.petbattles.battle.BattleSession;
import com.petbattles.engine.BattleEvent;
import com.petbattles.engine.BattlePet;
import com.petbattles.engine.BattleState;
import com.petbattles.engine.MoveDef;
import com.petbattles.engine.PetType;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
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

	private static final Color SPLAT_RED = new Color(161, 22, 21);
	private static final Color SPLAT_SUPER = new Color(220, 80, 10);
	private static final Color SPLAT_RESIST = new Color(110, 45, 45);
	private static final Color SPLAT_BLUE = new Color(40, 66, 155);

	// Sprite positions shared by the pet drawing and the effect layers
	private static final Rectangle ENEMY_SPRITE = new Rectangle(WIDTH - 96, 24, 72, 72);
	private static final Rectangle PLAYER_SPRITE = new Rectangle(24, 88, 72, 72);

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
	private volatile boolean swapMenuOpen;

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

	public void setSwapMenuOpen(boolean open)
	{
		this.swapMenuOpen = open;
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

		// The end-of-battle summary replaces the battle scene entirely
		if (session.getPhase() == BattleSession.Phase.ENDED)
		{
			List<Button> endButtons = new ArrayList<>();
			drawSummary(g, state, endButtons);
			synchronized (this)
			{
				buttons.clear();
				buttons.addAll(endButtons);
			}
			return new Dimension(WIDTH, HEIGHT);
		}

		BattleEvent current = session.getPhase() == BattleSession.Phase.ANIMATING
			? session.getCurrentEvent() : null;
		MoveDef currentMove = session.getCurrentMove();
		float progress = session.getAnimationProgress();

		// A fainted pet is "settled" (drawn as a faint ghost) only once its faint line has
		// actually played — the session gates this so the collapse never fires during the
		// earlier attack/damage lines (which read the already-resolved model).
		boolean enemySettled = session.isFaintSettled(enemy);
		boolean playerSettled = session.isFaintSettled(player);

		// Orient each pet toward its opponent: the player's pet should face right, the enemy's
		// left. A left-facing sprite is mirrored on the player side; a right-facing one on the
		// enemy side (spriteFacesLeft defaults true, so most pets flip only on the player side).
		boolean enemyMirror = !enemy.getSpecies().isSpriteFacesLeft();
		boolean playerMirror = player.getSpecies().isSpriteFacesLeft();

		// Enemy: info card top-left, sprite top-right
		drawPetInfo(g, enemy, 10, 22, false);
		drawPetSprite(g, enemy, ENEMY_SPRITE,
			AttackAnimator.spriteTransform(current, currentMove, progress, BattleState.ENEMY, ENEMY_SPRITE, PLAYER_SPRITE),
			enemySettled, enemyMirror);

		// Player: sprite mid-left, info card mid-right
		drawPetSprite(g, player, PLAYER_SPRITE,
			AttackAnimator.spriteTransform(current, currentMove, progress, BattleState.PLAYER, PLAYER_SPRITE, ENEMY_SPRITE),
			playerSettled, playerMirror);
		drawPetInfo(g, player, WIDTH - 150, 92, true);

		// Attack effects and hit splats ride the current event
		if (current != null)
		{
			Rectangle attackerRect = spriteRect(current.getSide());
			Rectangle defenderRect = spriteRect(BattleState.opponent(current.getSide()));
			AttackAnimator.drawEffects(g, current, currentMove, progress, attackerRect, defenderRect);
			drawEventEffects(g, current, progress);
		}

		List<Button> newButtons = new ArrayList<>();
		if (session.getPhase() == BattleSession.Phase.ANIMATING)
		{
			// Turn is resolving: no move grid, just a wide dialog box with the current line
			drawDialogBox(g);
		}
		else
		{
			// Buttons
			int btnY = HEIGHT - 44;
			if (session.getPhase() == BattleSession.Phase.FORCED_SWITCH)
			{
				// Active pet fainted: a replacement must be chosen before play resumes
				g.setFont(FontManager.getRunescapeSmallFont());
				g.setColor(new Color(240, 210, 120));
				g.drawString("Send out your next pet!", 12, btnY - 4);
				drawSwapMenu(g, state, btnY, newButtons, true);
			}
			else if (session.getPhase() == BattleSession.Phase.LEARN_MOVE)
			{
				// A pet levelled into a new move with a full moveset: forget one or skip
				drawLearnPrompt(g, btnY, newButtons);
			}
			else if (swapMenuOpen && session.isAwaitingInput())
			{
				drawSwapMenu(g, state, btnY, newButtons, false);
			}
			else
			{
				swapMenuOpen = false;
				boolean enabled = session.isAwaitingInput();
				List<MoveDef> moves = player.getMoves();
				// Taller 2x2 move cards filling the space freed by the removed log strip; each
				// card shows power/accuracy and the type match-up against the current enemy.
				int gridTop = 162;
				int bw = (WIDTH - 24 - 8) / 2;
				int bh = 38;
				int stride = bh + 6;
				for (int i = 0; i < 4; i++)
				{
					int col = i % 2;
					int row = i / 2;
					Rectangle r = new Rectangle(12 + col * (bw + 8), gridTop + row * stride, bw, bh);
					if (i < moves.size())
					{
						drawMoveCard(g, r, moves.get(i), enabled, enemy);
						newButtons.add(new Button(r, "move:" + i));
					}
					else
					{
						drawButton(g, r, "—", false, null);
					}
				}
				Rectangle swap = new Rectangle(WIDTH - 118, 4, 54, 14);
				boolean canSwap = enabled && hasBenchTarget(state);
				drawButton(g, swap, "Swap", canSwap, null);
				if (canSwap)
				{
					newButtons.add(new Button(swap, "swapmenu"));
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
	 * Post-battle summary: the outcome headline, then a dedicated slot for every pet on the
	 * battle team — its icon, ending HP, XP gained and any level-ups / moves learned — and a
	 * Close button. Level-up fireworks play over the pet's own icon here, so the celebration
	 * always lands on the pet that actually grew rather than whoever was last on the field.
	 */
	private void drawSummary(Graphics2D g, BattleState state, List<Button> newButtons)
	{
		BattleState.Phase phase = state.getPhase();
		String headline;
		Color headlineColor;
		if (phase == BattleState.Phase.PLAYER_WON)
		{
			headline = "Victory!";
			headlineColor = HP_GREEN;
		}
		else if (phase == BattleState.Phase.FLED)
		{
			headline = "You fled the battle";
			headlineColor = HP_YELLOW;
		}
		else
		{
			headline = "Defeat...";
			headlineColor = HP_RED;
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(headlineColor);
		FontMetrics hm = g.getFontMetrics();
		g.drawString(headline, (WIDTH - hm.stringWidth(headline)) / 2, 36);

		List<BattleSession.SummaryEntry> entries = session.getSummary();
		int top = 44;
		int bottom = HEIGHT - 40;
		if (entries.isEmpty())
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			g.setColor(LOG_TEXT);
			g.drawString("No pets took part.", 16, top + 12);
		}
		else
		{
			int slotH = Math.min(58, (bottom - top) / entries.size());
			int y = top;
			for (BattleSession.SummaryEntry e : entries)
			{
				drawSummarySlot(g, e, y, slotH);
				y += slotH;
			}
		}

		Rectangle close = new Rectangle(WIDTH / 2 - 40, HEIGHT - 32, 80, 24);
		drawButton(g, close, "Close", true, null);
		newButtons.add(new Button(close, "close"));
	}

	/**
	 * One pet's dedicated summary slot: growth-stage icon on the left, then name/level, an
	 * HP bar and a status/rewards line. Pets that levelled up get a looping firework burst
	 * over their icon.
	 */
	private void drawSummarySlot(Graphics2D g, BattleSession.SummaryEntry e, int y, int slotH)
	{
		int cardH = slotH - 4;
		g.setColor(new Color(0, 0, 0, 90));
		g.fillRoundRect(8, y, WIDTH - 16, cardH, 6, 6);

		// Icon slot on the left
		int iconSize = Math.min(40, cardH - 8);
		int iconX = 14;
		int iconY = y + (cardH - iconSize) / 2;
		Rectangle iconRect = new Rectangle(iconX, iconY, iconSize, iconSize);
		g.setColor(new Color(255, 255, 255, 18));
		g.fillRoundRect(iconX, iconY, iconSize, iconSize, 4, 4);
		Image img = sprites.itemImage(e.getDisplayItemId());
		if (img != null)
		{
			// A knocked-out or benched pet's icon is dimmed to read as "out of the fight".
			float iconAlpha = e.isFainted() ? 0.3f : e.isFought() ? 1f : 0.5f;
			Composite prev = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, iconAlpha));
			g.drawImage(img, iconX, iconY, iconSize, iconSize, null);
			g.setComposite(prev);
		}

		int textX = iconX + iconSize + 8;
		int textRight = WIDTH - 14;
		int textW = textRight - textX;

		// Name + level line
		g.setFont(FontManager.getRunescapeSmallFont());
		g.setColor(Color.WHITE);
		String name = e.getName() + "  Lv" + e.getLevel() + (e.getLevelsGained() > 0 ? " ▲" : "");
		g.drawString(clip(g, name, textW - 46), textX, y + 13);

		// XP gained, right-aligned on the name line
		if (e.getXpGained() > 0)
		{
			g.setColor(new Color(150, 220, 150));
			String xp = "+" + e.getXpGained() + " XP";
			g.drawString(xp, textRight - g.getFontMetrics().stringWidth(xp), y + 13);
		}

		// HP bar
		int barY = y + 18;
		double frac = e.getMaxHp() > 0 ? (double) e.getCurrentHp() / e.getMaxHp() : 0;
		g.setColor(new Color(40, 40, 40));
		g.fillRect(textX, barY, textW, 5);
		g.setColor(e.isFainted() ? HP_RED : hpColor(frac));
		g.fillRect(textX, barY, (int) (textW * Math.max(0, frac)), 5);

		// Status / rewards line: HP text on the right, level-up + moves note on the left
		int lineY = barY + 17;
		g.setFont(FontManager.getRunescapeSmallFont());
		String hp = e.isFainted() ? "Knocked out"
			: !e.isFought() ? "Didn't battle"
			: e.getCurrentHp() + " / " + e.getMaxHp() + " HP";
		g.setColor(e.isFainted() ? HP_RED : LOG_TEXT);
		int hpW = g.getFontMetrics().stringWidth(hp);
		g.drawString(hp, textRight - hpW, lineY);

		if (e.getLevelsGained() > 0 || !e.getLearnedMoves().isEmpty())
		{
			StringBuilder note = new StringBuilder();
			if (e.getLevelsGained() > 0)
			{
				note.append("Grew ").append(e.getLevelsGained()).append(e.getLevelsGained() == 1 ? " level" : " levels");
			}
			if (!e.getLearnedMoves().isEmpty())
			{
				note.append(note.length() > 0 ? ", learned " : "Learned ").append(String.join(", ", e.getLearnedMoves()));
			}
			g.setColor(new Color(210, 200, 160));
			g.drawString(clip(g, note.toString(), textW - hpW - 6), textX, lineY);
		}

		// Looping level-up fireworks over this pet's own icon
		if (e.getLevelsGained() > 0)
		{
			float progress = (System.currentTimeMillis() % 1300L) / 1300f;
			ParticleBurst.render(g, iconRect, progress, e.getLevel(), ParticleBurst.FIREWORKS);
		}
	}

	private static boolean hasBenchTarget(BattleState state)
	{
		List<BattlePet> team = state.team(BattleState.PLAYER);
		for (int i = 0; i < team.size(); i++)
		{
			if (i != state.activeIndex(BattleState.PLAYER) && !team.get(i).isFainted())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Bench picker shown in place of the move grid: each healthy bench pet with its
	 * offensive type match-up against the current enemy. When {@code forced} (after a
	 * mid-turn faint) the pick is mandatory: rows submit a forced switch and there is no
	 * Back button.
	 */
	private void drawSwapMenu(Graphics2D g, BattleState state, int btnY, List<Button> newButtons, boolean forced)
	{
		BattlePet enemy = state.active(BattleState.ENEMY);
		List<BattlePet> team = state.team(BattleState.PLAYER);
		int active = state.activeIndex(BattleState.PLAYER);
		int rowY = btnY;
		for (int i = 0; i < team.size(); i++)
		{
			if (i == active || team.get(i).isFainted())
			{
				continue;
			}
			BattlePet bench = team.get(i);
			double best = 0;
			for (PetType type : bench.getSpecies().getTypes())
			{
				best = Math.max(best, session.getTypeChart().effectiveness(type, enemy.getSpecies().getTypes()));
			}
			String label = bench.getDisplayName() + "  Lv" + bench.getLevel()
				+ "  " + bench.getCurrentHp() + "/" + bench.getMaxHp()
				+ "  hits " + String.format("%.1fx", best);
			Color accent = best >= 2.0 ? HP_GREEN : best <= 0.5 ? HP_RED : null;
			Rectangle r = new Rectangle(12, rowY, WIDTH - 24, 18);
			drawButton(g, r, label, true, accent);
			newButtons.add(new Button(r, (forced ? "forceswitch:" : "switch:") + i));
			rowY += 22;
		}
		if (!forced)
		{
			Rectangle back = new Rectangle(WIDTH - 60, 4, 50, 14);
			drawButton(g, back, "Back", true, null);
			newButtons.add(new Button(back, "swapcancel"));
		}
	}

	/**
	 * The forget-a-move chooser shown when a pet levels into a new move with all four slots
	 * full: a header naming the new move, the four current moves as "forget" buttons, and a
	 * "Keep moves" button to decline. Mirrors the move grid's layout and hit-test idiom.
	 */
	private void drawLearnPrompt(Graphics2D g, int btnY, List<Button> newButtons)
	{
		BattleSession.LearnPrompt prompt = session.getLearnPrompt();
		if (prompt == null)
		{
			return;
		}
		MoveDef newMove = prompt.getNewMove();
		String stats = newMove.getType().getDisplayName()
			+ (newMove.isStatusMove() ? ", status" : ", " + newMove.getPower() + " pow");
		g.setFont(FontManager.getRunescapeSmallFont());
		g.setColor(new Color(240, 210, 120));
		String header = prompt.getPetName() + " learns " + newMove.getName() + " [" + stats + "] - forget:";
		g.drawString(clip(g, header, WIDTH - 96), 12, btnY - 4);

		List<MoveDef> moves = prompt.getCurrentMoves();
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
				drawButton(g, r, m.getName(), true, new Color(m.getType().getColorRgb()));
				newButtons.add(new Button(r, "learn:" + i));
			}
			else
			{
				drawButton(g, r, "—", false, null);
			}
		}
		Rectangle keep = new Rectangle(WIDTH - 84, 4, 74, 14);
		drawButton(g, keep, "Keep moves", true, null);
		newButtons.add(new Button(keep, "learnskip"));
	}

	/**
	 * Per-event effect layer: OSRS-style hit splats over the affected pet sprite.
	 */
	private void drawEventEffects(Graphics2D g, BattleEvent event, float progress)
	{
		switch (event.getType())
		{
			case DAMAGE:
			{
				Color color = event.getEffectiveness() >= 2.0 ? SPLAT_SUPER
					: event.getEffectiveness() <= 0.5 ? SPLAT_RESIST : SPLAT_RED;
				drawHitsplat(g, spriteRect(event.getSide()), String.valueOf(event.getValue()), color, progress);
				break;
			}
			case MISSED:
				// A whiff renders as the OSRS blue 0 splat on the would-be defender
				drawHitsplat(g, spriteRect(BattleState.opponent(event.getSide())), "0", SPLAT_BLUE, progress);
				break;
			case LEVEL_UP:
				// Deliberately no field fireworks here: the levelling pet may be benched, and
				// the field only shows the active pet. Level-ups are celebrated in the
				// post-battle summary, over the correct pet's slot (see drawSummarySlot).
				break;
			case HEALED:
				ParticleBurst.render(g, spriteRect(event.getSide()), progress,
					event.getValue(), ParticleBurst.SPARKLE);
				break;
			default:
				break;
		}
	}

	private static Rectangle spriteRect(int side)
	{
		return side == BattleState.PLAYER ? PLAYER_SPRITE : ENEMY_SPRITE;
	}

	/**
	 * HP-bar fill colour by remaining fraction: green &gt; 50%, yellow &gt; 20%, else red.
	 */
	private static Color hpColor(double frac)
	{
		return frac > 0.5 ? HP_GREEN : frac > 0.2 ? HP_YELLOW : HP_RED;
	}

	/**
	 * A four-lobed splat that pops in, holds, and fades over the animation progress.
	 */
	private void drawHitsplat(Graphics2D g, Rectangle target, String number, Color color, float progress)
	{
		// Pop in over the first 15%, fade out over the last 25%
		float scale = Math.min(1f, progress / 0.15f);
		float alpha = progress > 0.75f ? Math.max(0f, 1f - (progress - 0.75f) / 0.25f) : 1f;
		if (scale <= 0f || alpha <= 0f)
		{
			return;
		}
		int cx = target.x + target.width / 2;
		int cy = target.y + target.height / 2;
		int r = (int) (13 * scale);

		Color body = new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (230 * alpha));
		g.setColor(body);
		// Four overlapping lobes plus a centre give the classic splat silhouette
		g.fillOval(cx - r, cy - r / 2 - 2, r, r);
		g.fillOval(cx, cy - r / 2 - 2, r, r);
		g.fillOval(cx - r / 2 - 2, cy - r, r, r);
		g.fillOval(cx - r / 2 - 2, cy, r, r);
		g.fillOval(cx - r + 2, cy - r / 2, 2 * r - 4, r);

		g.setFont(FontManager.getRunescapeSmallFont());
		g.setColor(new Color(255, 255, 255, (int) (255 * alpha)));
		FontMetrics fm = g.getFontMetrics();
		g.drawString(number, cx - fm.stringWidth(number) / 2,
			cy + (fm.getAscent() - fm.getDescent()) / 2);
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
		g.fillRoundRect(x, y, w, showExactHp ? 66 : 48, 8, 8);

		// Level (subtext) right-aligned, then the name (title) filling the rest of the top line
		g.setFont(Fonts.SUBTEXT);
		g.setColor(new Color(200, 190, 160));
		String lv = "Lv" + pet.getLevel();
		int lvW = g.getFontMetrics().stringWidth(lv);
		g.drawString(lv, x + w - lvW - 6, y + 15);
		g.setFont(Fonts.TITLE);
		g.setColor(Color.WHITE);
		g.drawString(clip(g, pet.getDisplayName(), w - lvW - 16), x + 6, y + 15);

		// Types — chip sized from the font metrics so the text sits centred inside it (native
		// RuneScape font metrics differ from a hardcoded box).
		g.setFont(Fonts.SUBTEXT);
		FontMetrics tfm = g.getFontMetrics();
		int chipTop = y + 19;
		int chipH = tfm.getAscent() + tfm.getDescent() + 2;
		int chipBaseline = chipTop + tfm.getAscent() + 1;
		int tx = x + 6;
		for (PetType type : pet.getSpecies().getTypes())
		{
			int tw = tfm.stringWidth(type.getDisplayName()) + 8;
			g.setColor(new Color(type.getColorRgb()));
			g.fillRoundRect(tx, chipTop, tw, chipH, 4, 4);
			g.setColor(Color.WHITE);
			g.drawString(type.getDisplayName(), tx + 4, chipBaseline);
			tx += tw + 4;
		}
		// Status tag
		if (pet.getStatus() != BattlePet.Status.NONE)
		{
			g.setColor(HP_RED);
			g.drawString(pet.getStatus().name(), tx + 2, chipBaseline);
		}

		// HP bar — drawn from the session's displayed HP, which lags the resolved model so the
		// bar drains in step with the hit-splat instead of snapping the instant a turn resolves.
		int barY = chipTop + chipH + 4;
		int barW = w - 12;
		float shownHp = session.displayHp(pet);
		double frac = pet.getMaxHp() > 0 ? Math.max(0, Math.min(1, shownHp / pet.getMaxHp())) : 0;
		g.setColor(new Color(40, 40, 40));
		g.fillRect(x + 6, barY, barW, 6);
		g.setColor(hpColor(frac));
		g.fillRect(x + 6, barY, (int) (barW * frac), 6);
		if (showExactHp)
		{
			g.setFont(Fonts.SUBTEXT);
			g.setColor(Color.WHITE);
			g.drawString(Math.max(0, Math.round(shownHp)) + " / " + pet.getMaxHp(), x + 6, barY + 20);
		}
	}

	private void drawPetSprite(Graphics2D g, BattlePet pet, Rectangle rect, AttackAnimator.Transform t,
		boolean settledFaint, boolean mirror)
	{
		int size = Math.round(rect.width * t.scale);
		int x = rect.x + t.dx + (rect.width - size) / 2;
		int y = rect.y + t.dy + (rect.height - size) / 2;
		Image img = sprites.itemImage(pet.getDisplayItemId());
		if (img == null)
		{
			return;
		}
		// A settled-fainted pet lingers as a faint ghost (no opaque dim box); otherwise the
		// transform alpha drives the mid-faint collapse and normal full opacity.
		float alpha = settledFaint ? 0.2f : t.alpha;
		Composite prev = null;
		if (alpha < 1f)
		{
			prev = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, alpha)));
		}
		blitSprite(g, img, x, y, size, mirror);
		if (prev != null)
		{
			g.setComposite(prev);
		}
	}

	/**
	 * Draw a sprite scaled into a size×size box, optionally mirrored horizontally (to flip a
	 * pet's facing). Uses the dest/src form so the mirror is a simple swap of the x extents.
	 */
	private static void blitSprite(Graphics2D g, Image img, int x, int y, int size, boolean mirror)
	{
		int iw = img.getWidth(null);
		int ih = img.getHeight(null);
		if (iw <= 0 || ih <= 0)
		{
			return;
		}
		if (mirror)
		{
			g.drawImage(img, x + size, y, x, y + size, 0, 0, iw, ih, null);
		}
		else
		{
			g.drawImage(img, x, y, x + size, y + size, 0, 0, iw, ih, null);
		}
	}

	/**
	 * A move choice card: the move name, a stats line (power/accuracy, or "Status"), a
	 * type-colour accent stripe, and the offensive match-up multiplier against the current
	 * enemy (green if super-effective, red if resisted). Hit-tested like a plain button.
	 */
	private void drawMoveCard(Graphics2D g, Rectangle r, MoveDef m, boolean enabled, BattlePet enemy)
	{
		boolean hover = enabled && hoverPoint != null && r.contains(hoverPoint);
		g.setColor(hover ? BUTTON_HOVER : BUTTON_BG);
		g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
		g.setColor(enabled ? BUTTON_EDGE : new Color(70, 70, 70));
		g.setStroke(new BasicStroke(1));
		g.drawRoundRect(r.x, r.y, r.width, r.height, 6, 6);

		// Type-colour accent stripe down the left edge
		g.setColor(new Color(m.getType().getColorRgb()));
		g.fillRect(r.x + 3, r.y + 3, 4, r.height - 6);

		int textX = r.x + 12;
		Color textColor = enabled ? Color.WHITE : new Color(140, 140, 140);
		Color subColor = enabled ? new Color(205, 200, 180) : new Color(120, 120, 120);

		// Name
		g.setFont(Fonts.TITLE);
		g.setColor(textColor);
		g.drawString(clip(g, m.getName(), r.width - 18), textX, r.y + 16);

		// Stats line: power (or "Status") + accuracy
		g.setFont(Fonts.SUBTEXT);
		g.setColor(subColor);
		String stats = (m.isStatusMove() ? "Status" : m.getPower() + " pow") + "  " + m.getAccuracy() + "%";
		g.drawString(stats, textX, r.y + 33);

		// Offensive match-up multiplier against the enemy (attacks only), right-aligned
		if (!m.isStatusMove() && enemy != null)
		{
			double eff = session.getTypeChart().effectiveness(m.getType(), enemy.getSpecies().getTypes());
			String effStr = "x" + trimEffectiveness(eff);
			g.setColor(!enabled ? new Color(120, 120, 120)
				: eff >= 2.0 ? HP_GREEN : eff <= 0.5 ? HP_RED : subColor);
			FontMetrics fm = g.getFontMetrics();
			g.drawString(effStr, r.x + r.width - fm.stringWidth(effStr) - 8, r.y + 33);
		}
	}

	/**
	 * Compact effectiveness multiplier text: whole numbers drop the decimal (2, 1), fractions
	 * keep it (0.5, 0.25).
	 */
	private static String trimEffectiveness(double eff)
	{
		return eff == Math.rint(eff) ? String.valueOf((int) eff) : String.valueOf(eff);
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
