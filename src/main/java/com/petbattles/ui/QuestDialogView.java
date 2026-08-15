package com.petbattles.ui;

import com.petbattles.quest.QuestDialogSession;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.runelite.client.ui.FontManager;

/**
 * Draws a {@link QuestDialogSession.Scene}: the speaker's name, their chathead framed on the left,
 * their line wrapped on the right, and the scene's buttons beneath — the post-battle quest dialog's
 * layout, lifted out so the standalone {@link QuestDialogOverlay} and the battle end screen are
 * literally the same picture.
 *
 * <p>The renderer owns no state beyond the hover point and where to find chatheads; hosts pass their
 * own {@code Graphics2D} and collect the buttons it lays out ({@code rect -> action}), then hit-test
 * those themselves. Sizes are measured before drawing ({@link #measure}) so a host can paint its
 * panel at the right height first.
 */
public class QuestDialogView
{
	/** The expanded dialog's width, matched to the battle overlay so the layouts agree. */
	public static final int WIDTH = 340;
	/** The collapsed prompt pill, sized like the rest toast plus room for a chathead. */
	public static final int PROMPT_WIDTH = 232;
	public static final int PROMPT_HEIGHT = 58;

	private static final Color PANEL_EDGE = new Color(90, 75, 40);
	private static final Color BUTTON_BG = new Color(45, 50, 58);
	private static final Color BUTTON_HOVER = new Color(70, 78, 90);
	private static final Color BUTTON_EDGE = new Color(120, 110, 80);
	private static final Color NAME = new Color(240, 210, 120);
	private static final Color TEXT = new Color(230, 225, 210);
	private static final Color MUTED = new Color(160, 155, 140);

	private static final int PAD = 12;
	private static final int NAME_H = 16;
	private static final int PORTRAIT_W = 72;
	private static final int PORTRAIT_H = 84;
	private static final int LINE_H = 16;
	private static final int BUTTON_H = 26;
	private static final int BUTTON_GAP = 6;
	private static final int HINT_H = 16;

	private final Function<String, BufferedImage> portraits;
	// Written from the AWT thread by the input handler, read on the render thread.
	private volatile Point hoverPoint;

	public QuestDialogView(Function<String, BufferedImage> portraits)
	{
		this.portraits = portraits;
	}

	public void setHoverPoint(Point localPoint)
	{
		this.hoverPoint = localPoint;
	}

	/**
	 * The height the expanded scene needs at this width, so the host can paint its panel before the
	 * content goes in. Must stay in step with {@link #draw}.
	 */
	public int measure(Graphics2D g, int width, QuestDialogSession.Scene scene)
	{
		int h = 8 + NAME_H + 4 + bodyHeight(g, width, scene) + 8;
		if (scene.getHint() != null)
		{
			h += HINT_H;
		}
		return h + scene.getChoices().size() * (BUTTON_H + BUTTON_GAP) + 6;
	}

	/**
	 * Draw the expanded scene into the box at ({@code x}, {@code y}), adding each button it lays out
	 * to {@code out}. {@code fixedButtonY} pins the button row to a host-local y (the battle overlay's
	 * fixed-height panel wants its Continue at the bottom); pass a negative value to let the buttons
	 * flow under the text. {@code closeable} adds the fold-back cross in the top-right corner.
	 */
	public void draw(Graphics2D g, int x, int y, int width, QuestDialogSession.Scene scene,
		BiConsumer<Rectangle, String> out, int fixedButtonY, boolean closeable)
	{
		boolean choice = scene.getKind() == QuestDialogSession.Kind.CHOICE;
		int textX = x + PAD;
		int textW = width - PAD * 2;

		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(NAME);
		int nameW = textW - (closeable ? 20 : 0);
		g.drawString(clip(g, choice ? "" : scene.getSpeakerName(), nameW), textX, y + 8 + 12);
		if (closeable)
		{
			Rectangle close = new Rectangle(x + width - PAD - 12, y + 8, 12, 12);
			drawClose(g, close);
			out.accept(close, QuestDialogSession.ACTION_DISMISS);
		}

		int bodyY = y + 8 + NAME_H + 4;
		BufferedImage chathead = chathead(scene);
		if (chathead != null)
		{
			g.setColor(new Color(255, 255, 255, 18));
			g.fillRoundRect(textX, bodyY, PORTRAIT_W, PORTRAIT_H, 6, 6);
			g.setColor(PANEL_EDGE);
			g.setStroke(new BasicStroke(1));
			g.drawRoundRect(textX, bodyY, PORTRAIT_W, PORTRAIT_H, 6, 6);
			drawFit(g, chathead, textX + 2, bodyY + 2, PORTRAIT_W - 4, PORTRAIT_H - 4);
			textX += PORTRAIT_W + 10;
			textW -= PORTRAIT_W + 10;
		}

		g.setFont(FontManager.getRunescapeFont());
		g.setColor(TEXT);
		int ty = bodyY + 12;
		for (String line : wrap(g, scene.getText(), textW))
		{
			g.drawString(line, textX, ty);
			ty += LINE_H;
		}

		int by = bodyY + bodyHeight(g, width, scene) + 8;
		if (scene.getHint() != null)
		{
			g.setColor(new Color(220, 180, 40));
			g.drawString(clip(g, scene.getHint(), width - PAD * 2), x + PAD, by + 11);
			by += HINT_H;
		}
		if (fixedButtonY >= 0)
		{
			by = fixedButtonY;
		}
		// A lone Continue is a narrow centred button; replies and Challenges take the full width.
		List<QuestDialogSession.Choice> choices = scene.getChoices();
		boolean narrow = choices.size() == 1
			&& QuestDialogSession.ACTION_CONTINUE.equals(choices.get(0).getAction());
		for (QuestDialogSession.Choice option : choices)
		{
			Rectangle r = narrow
				? new Rectangle(x + width / 2 - 50, by, 100, BUTTON_H - 2)
				: new Rectangle(x + PAD, by, width - PAD * 2, BUTTON_H - 2);
			drawButton(g, r, option.getLabel(), option.isEnabled());
			if (option.isEnabled())
			{
				out.accept(r, option.getAction());
			}
			by += BUTTON_H + BUTTON_GAP;
		}
	}

	/**
	 * Draw the collapsed prompt pill: a small chathead, the action ("Talk to Hans") and the chapter
	 * title beneath it. The whole card is the button.
	 */
	public void drawPrompt(Graphics2D g, QuestDialogSession.Scene scene, BiConsumer<Rectangle, String> out)
	{
		int px = 8;
		int pw = 36;
		int ph = 42;
		int textX = px;
		BufferedImage chathead = chathead(scene);
		if (chathead != null)
		{
			g.setColor(new Color(255, 255, 255, 18));
			g.fillRoundRect(px, 8, pw, ph, 6, 6);
			drawFit(g, chathead, px + 2, 10, pw - 4, ph - 4);
			textX = px + pw + 8;
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(NAME);
		g.drawString(clip(g, scene.getText(), PROMPT_WIDTH - textX - 10), textX, 26);
		if (scene.getTitle() != null)
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			g.setColor(MUTED);
			g.drawString(clip(g, scene.getTitle(), PROMPT_WIDTH - textX - 10), textX, 42);
		}
		out.accept(new Rectangle(0, 0, PROMPT_WIDTH, PROMPT_HEIGHT), QuestDialogSession.ACTION_OPEN);
	}

	/** The chathead for this scene, or null for narration, the player, and reply menus. */
	private BufferedImage chathead(QuestDialogSession.Scene scene)
	{
		String speaker = scene.getSpeaker();
		if (scene.getKind() == QuestDialogSession.Kind.CHOICE || speaker == null
			|| "player".equals(speaker))
		{
			// The player has no chathead (RuneLite exposes no clean way to render the local player's),
			// so their lines drop the picture frame and keep just the name row — an empty frame reads
			// as a missing asset, no frame reads as narration.
			return null;
		}
		return portraits.apply(speaker);
	}

	/** The text/chathead band's height: the taller of the framed chathead and the wrapped line. */
	private int bodyHeight(Graphics2D g, int width, QuestDialogSession.Scene scene)
	{
		if (scene.getKind() == QuestDialogSession.Kind.CHOICE)
		{
			return 0;
		}
		BufferedImage chathead = chathead(scene);
		int textW = width - PAD * 2 - (chathead != null ? PORTRAIT_W + 10 : 0);
		g.setFont(FontManager.getRunescapeFont());
		int textH = wrap(g, scene.getText(), textW).size() * LINE_H + 4;
		return Math.max(chathead != null ? PORTRAIT_H : 0, textH);
	}

	private void drawButton(Graphics2D g, Rectangle r, String label, boolean enabled)
	{
		Point hover = hoverPoint;
		boolean hovered = enabled && hover != null && r.contains(hover);
		g.setColor(hovered ? BUTTON_HOVER : BUTTON_BG);
		g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
		g.setColor(enabled ? BUTTON_EDGE : new Color(70, 70, 70));
		g.setStroke(new BasicStroke(1));
		g.drawRoundRect(r.x, r.y, r.width, r.height, 6, 6);
		g.setFont(FontManager.getRunescapeSmallFont());
		g.setColor(enabled ? Color.WHITE : new Color(140, 140, 140));
		FontMetrics fm = g.getFontMetrics();
		String text = clip(g, label, r.width - 14);
		g.drawString(text, r.x + (r.width - fm.stringWidth(text)) / 2,
			r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
	}

	/** The fold-back cross: drawn as strokes so it doesn't depend on the game font having a glyph. */
	private void drawClose(Graphics2D g, Rectangle r)
	{
		Point hover = hoverPoint;
		g.setColor(hover != null && r.contains(hover) ? Color.WHITE : MUTED);
		g.setStroke(new BasicStroke(2));
		g.drawLine(r.x + 2, r.y + 2, r.x + r.width - 2, r.y + r.height - 2);
		g.drawLine(r.x + r.width - 2, r.y + 2, r.x + 2, r.y + r.height - 2);
	}

	/** Draw an image scaled to fit the box while preserving aspect ratio, centred. */
	private static void drawFit(Graphics2D g, BufferedImage img, int bx, int by, int bw, int bh)
	{
		if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0)
		{
			return;
		}
		double scale = Math.min(bw / (double) img.getWidth(), bh / (double) img.getHeight());
		int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
		int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
		g.drawImage(img, bx + (bw - w) / 2, by + (bh - h) / 2, w, h, null);
	}

	private static List<String> wrap(Graphics2D g, String text, int maxWidth)
	{
		List<String> lines = new ArrayList<>();
		if (text == null || text.isEmpty())
		{
			return lines;
		}
		FontMetrics fm = g.getFontMetrics();
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

	private static String clip(Graphics2D g, String text, int maxWidth)
	{
		FontMetrics fm = g.getFontMetrics();
		if (text == null || fm.stringWidth(text) <= maxWidth)
		{
			return text == null ? "" : text;
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
