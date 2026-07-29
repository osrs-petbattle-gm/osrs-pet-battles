package com.petbattles.ui;

import com.petbattles.battle.BattleSession;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.PetInstance;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.engine.TrainerDef;
import com.petbattles.persist.RosterManager;
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
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

/**
 * In-client control hub. Collapsed it's a small launcher chip; it expands to one of
 * three panes — Team (reorder / remove / add / rest), Rest, or Challenge ("Battle
 * &lt;trainer&gt;" with the trainer's chathead and party). Expansion is context-driven
 * (near a bank with an injured pet → Rest; near a battleable trainer → Challenge) with
 * the chip as the manual fallback. Hidden entirely during a battle, which locks the
 * swap/rest controls while a fight runs.
 *
 * <p>Each pane's body is laid out twice per frame: once under an empty clip to measure
 * its height (so the frame can be filled at exactly the right size), then again to
 * paint. Buttons are collected in overlay-local space and hit-tested by the sibling
 * HubInputHandler, exactly like {@link BattleOverlay}.
 */
public class HubOverlay extends Overlay
{
	public enum Pane
	{
		COLLAPSED,
		MENU,
		TEAM,
		REST,
		CHALLENGE
	}

	public static final class Button
	{
		public final Rectangle rect;
		public final String action;

		Button(Rectangle rect, String action)
		{
			this.rect = rect;
			this.action = action;
		}
	}

	/** Vector glyphs for the small square controls (arrows, close cross, menu). */
	private enum Icon
	{
		UP,
		DOWN,
		LEFT,
		RIGHT,
		CLOSE,
		MENU
	}

	private static final int WIDTH = 234;
	private static final int CHIP = 46;
	private static final int ADD_VISIBLE = 4;
	private static final int PAD = 8;

	private static final Color PANEL_BG = new Color(20, 24, 28, 235);
	private static final Color PANEL_EDGE = new Color(90, 75, 40);
	private static final Color BUTTON_BG = new Color(45, 50, 58);
	private static final Color BUTTON_HOVER = new Color(70, 78, 90);
	private static final Color BUTTON_EDGE = new Color(120, 110, 80);
	private static final Color BUTTON_DISABLED_EDGE = new Color(70, 70, 70);
	private static final Color HP_YELLOW = new Color(220, 180, 40);
	private static final Color HP_RED = new Color(200, 60, 50);
	private static final Color TEXT = new Color(230, 225, 210);
	private static final Color MUTED = new Color(150, 145, 130);

	private final PetDatabase db;
	private final RosterManager roster;
	private final Sprites sprites;
	private final Portraits portraits;
	private final BattleSession session;
	private final BooleanSupplier atBank;
	private final Supplier<Set<String>> nearTrainers;
	private final BufferedImage chipIcon;

	private final List<Button> buttons = new ArrayList<>();
	private volatile Point hoverPoint;

	// Pane the user has explicitly opened; null means "follow context / collapsed".
	private Pane pinned;
	// Context we last auto-derived, and whether the user dismissed it (so it doesn't
	// immediately re-pop). Cleared when the context changes and re-triggers.
	private Pane lastContext;
	private boolean dismissed;
	private int addOffset;
	private int challengePage;

	public HubOverlay(PetDatabase db, RosterManager roster, Sprites sprites, Portraits portraits,
		BattleSession session, BooleanSupplier atBank, Supplier<Set<String>> nearTrainers)
	{
		this.db = db;
		this.roster = roster;
		this.sprites = sprites;
		this.portraits = portraits;
		this.session = session;
		this.atBank = atBank;
		this.nearTrainers = nearTrainers;
		this.chipIcon = ImageUtil.loadImageResource(getClass(), "/com/petbattles/icons/panel_icon.png");
		setPosition(OverlayPosition.BOTTOM_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
	}

	public synchronized List<Button> getButtons()
	{
		return new ArrayList<>(buttons);
	}

	public void setHoverPoint(Point localPoint)
	{
		this.hoverPoint = localPoint;
	}

	// --- state transitions, driven from the input handler on the client thread ---

	public void openMenu()
	{
		pinned = Pane.MENU;
	}

	public void openPane(Pane pane)
	{
		pinned = pane;
		addOffset = 0;
		challengePage = 0;
	}

	public void collapse()
	{
		pinned = null;
		// Suppress auto re-open of the current context until it clears and re-fires.
		dismissed = true;
	}

	public void addPage(int delta)
	{
		addOffset = Math.max(0, addOffset + delta);
	}

	public void challengePageDelta(int delta)
	{
		challengePage += delta;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		// Battle lock: the hub is gone for the whole fight, so no team edits or rest.
		if (session.isActive())
		{
			clearButtons();
			return null;
		}

		Pane context = currentContext();
		if (context != lastContext)
		{
			lastContext = context;
			dismissed = false;
		}

		Pane pane;
		if (pinned != null)
		{
			pane = pinned;
		}
		else if (context != null && !dismissed)
		{
			pane = context;
		}
		else
		{
			pane = Pane.COLLAPSED;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

		List<Button> out = new ArrayList<>();
		Dimension size = pane == Pane.COLLAPSED ? drawChip(g, out) : drawPane(g, out, pane);

		synchronized (this)
		{
			buttons.clear();
			buttons.addAll(out);
		}
		return size;
	}

	/**
	 * The pane context wants open right now, or null. Challenge (a battleable trainer is
	 * nearby) outranks Rest (at a bank with an injured pet). The challenge pane never
	 * auto-opens while banking — it would cover the bank interface — but the manual
	 * "Challenge nearby trainer" menu button stays available.
	 */
	private Pane currentContext()
	{
		if (!atBank.getAsBoolean() && !nearTrainers.get().isEmpty())
		{
			return Pane.CHALLENGE;
		}
		if (roster.isLoaded() && atBank.getAsBoolean() && roster.anyPetInjured())
		{
			return Pane.REST;
		}
		return null;
	}

	// --- frame orchestration ---

	private Dimension drawChip(Graphics2D g, List<Button> out)
	{
		g.setColor(PANEL_BG);
		g.fillRoundRect(0, 0, CHIP, CHIP, 10, 10);
		g.setColor(PANEL_EDGE);
		g.setStroke(new BasicStroke(2));
		g.drawRoundRect(0, 0, CHIP, CHIP, 10, 10);
		if (chipIcon != null)
		{
			g.drawImage(chipIcon, (CHIP - 32) / 2, (CHIP - 32) / 2, 32, 32, null);
		}
		out.add(new Button(new Rectangle(0, 0, CHIP, CHIP), "chip"));
		return new Dimension(CHIP, CHIP);
	}

	private Dimension drawPane(Graphics2D g, List<Button> out, Pane pane)
	{
		String title = paneTitle(pane);
		boolean showMenu = pane != Pane.MENU;
		// Measure the body height first (under an empty clip so nothing ghosts), then fill
		// the frame at that exact height and paint the body for real.
		List<Button> ignore = new ArrayList<>();
		int bodyEnd = measure(g, () -> body(g, ignore, pane));
		int height = bodyEnd + PAD;
		fillFrame(g, out, title, showMenu, height);
		body(g, out, pane);
		return new Dimension(WIDTH, height);
	}

	private int body(Graphics2D g, List<Button> out, Pane pane)
	{
		switch (pane)
		{
			case MENU:
				return menuBody(g, out);
			case REST:
				return restBody(g, out);
			case CHALLENGE:
				return challengeBody(g, out);
			case TEAM:
			default:
				return teamBody(g, out);
		}
	}

	private String paneTitle(Pane pane)
	{
		switch (pane)
		{
			case MENU:
				return "Pet Battles";
			case REST:
				return "Rest pets";
			case CHALLENGE:
				return "Challenge";
			case TEAM:
			default:
				return "Team (" + roster.getTeam().size() + "/" + RosterManager.MAX_TEAM_SIZE + ")";
		}
	}

	// --- pane bodies (start at y = 26, return the y after the last content) ---

	private int menuBody(Graphics2D g, List<Button> out)
	{
		int y = 26;
		if (!roster.isLoaded())
		{
			return loginHint(g, y);
		}
		y = fullButton(g, out, y, "Manage team", "open:team", true);
		if (roster.anyPetInjured())
		{
			boolean canRest = atBank.getAsBoolean();
			y = fullButton(g, out, y, canRest ? "Rest pets" : "Rest pets (visit a bank)", "open:rest", canRest);
		}
		if (!nearTrainers.get().isEmpty())
		{
			y = fullButton(g, out, y, "Challenge nearby trainer", "open:challenge", true);
		}
		return y;
	}

	private int teamBody(Graphics2D g, List<Button> out)
	{
		int y = 26;
		if (!roster.isLoaded())
		{
			return loginHint(g, y);
		}
		List<String> team = roster.getTeam();
		boolean canEdit = roster.canEditTeam();

		if (team.isEmpty())
		{
			hint(g, "No pets on the team yet", y);
			y += 18;
		}
		for (int i = 0; i < team.size(); i++)
		{
			y = teamRow(g, out, y, team, i, canEdit);
		}

		y += 4;
		boolean injured = roster.anyPetInjured();
		boolean canRest = canEdit && injured;
		String restLabel = !injured ? "All pets rested" : !canEdit ? "Rest pets (visit a bank)" : "Rest pets";
		y = fullButton(g, out, y, restLabel, "rest", canRest);

		// Add-a-pet picker: owned pets not already on the team, paged. Its own header
		// row (with the paging arrows aligned to it) sits clear of the Rest button above.
		y += 10;
		List<SpeciesDef> available = availableToAdd(team);
		boolean teamFull = team.size() >= RosterManager.MAX_TEAM_SIZE;
		boolean canAdd = canEdit && !teamFull;
		int maxOffset = Math.max(0, available.size() - ADD_VISIBLE);
		if (addOffset > maxOffset)
		{
			addOffset = maxOffset;
		}
		boolean showList = canAdd && !available.isEmpty();
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(Color.WHITE);
		g.drawString("Add a pet", 10, y + 13);
		if (showList && available.size() > ADD_VISIBLE)
		{
			iconButton(g, out, new Rectangle(WIDTH - 42, y + 2, 14, 14), Icon.UP, "team.page:-1", addOffset > 0);
			iconButton(g, out, new Rectangle(WIDTH - 24, y + 2, 14, 14), Icon.DOWN, "team.page:1", addOffset < maxOffset);
		}
		y += 22;

		if (!canEdit)
		{
			hint(g, "Visit a bank to change your team", y);
			y += 18;
		}
		else if (teamFull)
		{
			hint(g, "Team is full - remove a pet first", y);
			y += 18;
		}
		else if (available.isEmpty())
		{
			hint(g, "No other pets unlocked", y);
			y += 18;
		}
		else
		{
			int end = Math.min(available.size(), addOffset + ADD_VISIBLE);
			for (int i = addOffset; i < end; i++)
			{
				SpeciesDef species = available.get(i);
				PetInstance pet = roster.getPet(species.getId());
				String label = (pet != null ? species.nameFor(pet.getActiveVariantId(), pet.getLevel())
					: species.getName()) + (pet != null ? "  Lv " + pet.getLevel() : "");
				Rectangle r = new Rectangle(8, y, WIDTH - 16, 20);
				drawButton(g, r, label, true, true);
				out.add(new Button(r, "team.add:" + species.getId()));
				y += 22;
			}
		}
		return y;
	}

	private int teamRow(Graphics2D g, List<Button> out, int y, List<String> team, int index, boolean canEdit)
	{
		String speciesId = team.get(index);
		SpeciesDef species = db.species(speciesId);
		if (species == null)
		{
			return y;
		}
		PetInstance pet = roster.getPet(speciesId);
		boolean fainted = pet != null && pet.isFainted();
		boolean hurt = pet != null && !fainted && pet.getCurrentHp() != null;
		String rowName = pet != null ? species.nameFor(pet.getActiveVariantId(), pet.getLevel()) : species.getName();
		String label = (index + 1) + ". " + rowName
			+ (pet != null ? "  Lv" + pet.getLevel() : "")
			+ (fainted ? "  KO" : hurt ? "  " + pet.getCurrentHp() + "hp" : "");

		g.setColor(new Color(0, 0, 0, 90));
		g.fillRoundRect(8, y, WIDTH - 16, 20, 5, 5);
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(fainted ? HP_RED : hurt ? HP_YELLOW : Color.WHITE);
		g.drawString(clip(g, label, WIDTH - 16 - 56), 12, y + 15);

		int bx = WIDTH - 8 - 16;
		iconButton(g, out, new Rectangle(bx, y + 3, 14, 14), Icon.CLOSE, "team.remove:" + speciesId, canEdit);
		bx -= 16;
		iconButton(g, out, new Rectangle(bx, y + 3, 14, 14), Icon.DOWN, "team.down:" + speciesId, index < team.size() - 1);
		bx -= 16;
		iconButton(g, out, new Rectangle(bx, y + 3, 14, 14), Icon.UP, "team.up:" + speciesId, index > 0);
		return y + 24;
	}

	private int restBody(Graphics2D g, List<Button> out)
	{
		int y = 26;
		if (!roster.isLoaded())
		{
			return loginHint(g, y);
		}
		boolean injured = roster.anyPetInjured();
		boolean atBankNow = atBank.getAsBoolean();
		boolean canRest = atBankNow && injured;
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(TEXT);
		String line = !injured ? "All your pets are fully rested."
			: !atBankNow ? "Visit a bank to rest your pets."
			: "Your pets are hurt. Rest to restore full HP.";
		g.drawString(clip(g, line, WIDTH - 20), 10, y + 4);
		y += 16;
		return fullButton(g, out, y, injured ? "Rest all pets" : "Nothing to rest", "rest", canRest);
	}

	private int challengeBody(Graphics2D g, List<Button> out)
	{
		int y = 26;
		List<TrainerDef> nearby = nearbyTrainers();
		if (nearby.isEmpty())
		{
			hint(g, "No trainer nearby.", y);
			return y + 16;
		}
		if (challengePage < 0)
		{
			challengePage = nearby.size() - 1;
		}
		if (challengePage >= nearby.size())
		{
			challengePage = 0;
		}
		TrainerDef trainer = nearby.get(challengePage);

		if (nearby.size() > 1)
		{
			iconButton(g, out, new Rectangle(WIDTH - 74, 3, 14, 14), Icon.LEFT, "chal.page:-1", true);
			iconButton(g, out, new Rectangle(WIDTH - 58, 3, 14, 14), Icon.RIGHT, "chal.page:1", true);
		}

		// Portrait: the trainer's chathead, aspect-preserved so a tall chathead isn't
		// squashed; falls back to the lead pet's icon when no chathead is bundled.
		int pw = 54;
		int ph = 64;
		Rectangle portraitRect = new Rectangle(10, y, pw, ph);
		g.setColor(new Color(255, 255, 255, 18));
		g.fillRoundRect(portraitRect.x, portraitRect.y, pw, ph, 6, 6);
		BufferedImage portrait = portraits.portrait(trainer.getId());
		if (portrait != null)
		{
			drawFit(g, portrait, portraitRect.x + 2, portraitRect.y + 2, pw - 4, ph - 4);
		}
		else if (!trainer.getParty().isEmpty())
		{
			SpeciesDef lead = db.species(trainer.getParty().get(0).getSpecies());
			if (lead != null)
			{
				drawFit(g, sprites.itemImage(lead.itemIdAt(trainer.getParty().get(0).getLevel())),
					portraitRect.x + 8, portraitRect.y + 8, pw - 16, ph - 16);
			}
		}

		// Trainer name + difficulty beside the portrait.
		int textX = portraitRect.x + pw + 10;
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(Color.WHITE);
		g.drawString(clip(g, trainer.getName(), WIDTH - textX - 8), textX, y + 18);
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(MUTED);
		int maxLevel = trainer.getParty().stream().mapToInt(TrainerDef.PartyEntry::getLevel).max().orElse(1);
		g.drawString("Lv " + maxLevel + " - " + trainer.getDifficulty(), textX, y + 34);
		int partyCount = trainer.getParty().size();
		g.drawString(partyCount + (partyCount == 1 ? " pet" : " pets"), textX, y + 50);

		// Party as readable rows below: icon, name, and level.
		y += ph + 8;
		for (TrainerDef.PartyEntry entry : trainer.getParty())
		{
			SpeciesDef species = db.species(entry.getSpecies());
			int rowIcon = 26;
			if (species != null)
			{
				drawFit(g, sprites.itemImage(species.itemIdAt(entry.getLevel())), 12, y, rowIcon, rowIcon);
			}
			g.setFont(FontManager.getRunescapeFont());
			g.setColor(Color.WHITE);
			String pname = species != null ? species.nameAt(entry.getLevel()) : entry.getSpecies();
			String lv = "Lv " + entry.getLevel();
			int lvW = g.getFontMetrics().stringWidth(lv);
			g.drawString(clip(g, pname, WIDTH - 12 - rowIcon - 8 - lvW - 10), 12 + rowIcon + 8, y + 17);
			g.setColor(MUTED);
			g.drawString(lv, WIDTH - 12 - lvW, y + 17);
			y += rowIcon + 4;
		}
		y += 2;

		boolean canFight = !roster.getTeam().isEmpty() && roster.teamCanFight();
		if (!canFight)
		{
			hint(g, roster.getTeam().isEmpty() ? "Add a pet to your team first"
				: "Team knocked out - rest at a bank", y);
			y += 16;
		}
		return fullButton(g, out, y, "Battle " + trainer.getName(), "fight:" + trainer.getId(), canFight);
	}

	// --- drawing helpers ---

	/**
	 * Run a layout pass with drawing suppressed (empty clip) to measure its height.
	 * FontMetrics stay valid under an empty clip, so text-dependent widths still work.
	 */
	private int measure(Graphics2D g, IntSupplier body)
	{
		Shape old = g.getClip();
		g.setClip(0, 0, 0, 0);
		try
		{
			return body.getAsInt();
		}
		finally
		{
			g.setClip(old);
		}
	}

	private void fillFrame(Graphics2D g, List<Button> out, String title, boolean showMenu, int height)
	{
		g.setColor(PANEL_BG);
		g.fillRoundRect(0, 0, WIDTH, height, 10, 10);
		g.setColor(PANEL_EDGE);
		g.setStroke(new BasicStroke(2));
		g.drawRoundRect(0, 0, WIDTH, height, 10, 10);
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(new Color(200, 190, 160));
		g.drawString(clip(g, title, WIDTH - 78), 10, 15);

		int bx = WIDTH - 8 - 16;
		iconButton(g, out, new Rectangle(bx, 3, 16, 14), Icon.CLOSE, "collapse", true);
		if (showMenu)
		{
			bx -= 18;
			iconButton(g, out, new Rectangle(bx, 3, 16, 14), Icon.MENU, "menu", true);
		}
	}

	private int loginHint(Graphics2D g, int y)
	{
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(MUTED);
		g.drawString("Log in to load your pets.", 10, y + 4);
		return y + 16;
	}

	private void hint(Graphics2D g, String text, int y)
	{
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(MUTED);
		g.drawString(clip(g, text, WIDTH - 20), 10, y + 4);
	}

	private int fullButton(Graphics2D g, List<Button> out, int y, String label, String action, boolean enabled)
	{
		Rectangle r = new Rectangle(8, y, WIDTH - 16, 22);
		drawButton(g, r, label, enabled, false);
		if (enabled)
		{
			out.add(new Button(r, action));
		}
		return y + 26;
	}

	/**
	 * A small square control drawn with a vector glyph. The RuneScape bitmap font has no
	 * arrow / cross / menu glyphs, so these are drawn as shapes rather than text.
	 */
	private void iconButton(Graphics2D g, List<Button> out, Rectangle r, Icon icon, String action, boolean enabled)
	{
		drawButtonBg(g, r, enabled);
		drawIcon(g, r, icon, enabled ? Color.WHITE : MUTED);
		if (enabled)
		{
			out.add(new Button(r, action));
		}
	}

	private void drawButton(Graphics2D g, Rectangle r, String label, boolean enabled, boolean leftAlign)
	{
		drawButtonBg(g, r, enabled);
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(enabled ? Color.WHITE : MUTED);
		FontMetrics fm = g.getFontMetrics();
		String text = clip(g, label, r.width - 10);
		int tx = leftAlign ? r.x + 6 : r.x + (r.width - fm.stringWidth(text)) / 2;
		g.drawString(text, tx, r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
	}

	private void drawButtonBg(Graphics2D g, Rectangle r, boolean enabled)
	{
		Point hp = hoverPoint;
		boolean hover = enabled && hp != null && r.contains(hp);
		g.setColor(hover ? BUTTON_HOVER : BUTTON_BG);
		g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
		g.setColor(enabled ? BUTTON_EDGE : BUTTON_DISABLED_EDGE);
		g.setStroke(new BasicStroke(1));
		g.drawRoundRect(r.x, r.y, r.width, r.height, 6, 6);
	}

	/**
	 * Draw an image scaled to fit within the box while preserving its aspect ratio,
	 * centred. No-op until an async item icon has loaded (width reported as -1).
	 */
	private void drawFit(Graphics2D g, Image img, int bx, int by, int bw, int bh)
	{
		if (img == null)
		{
			return;
		}
		int iw = img.getWidth(null);
		int ih = img.getHeight(null);
		if (iw <= 0 || ih <= 0)
		{
			return;
		}
		double scale = Math.min(bw / (double) iw, bh / (double) ih);
		int w = Math.max(1, (int) Math.round(iw * scale));
		int h = Math.max(1, (int) Math.round(ih * scale));
		g.drawImage(img, bx + (bw - w) / 2, by + (bh - h) / 2, w, h, null);
	}

	private void drawIcon(Graphics2D g, Rectangle r, Icon icon, Color color)
	{
		int cx = r.x + r.width / 2;
		int cy = r.y + r.height / 2;
		int s = 3;
		g.setColor(color);
		switch (icon)
		{
			case UP:
				g.fillPolygon(new int[]{cx, cx - s, cx + s}, new int[]{cy - s, cy + s, cy + s}, 3);
				break;
			case DOWN:
				g.fillPolygon(new int[]{cx, cx - s, cx + s}, new int[]{cy + s, cy - s, cy - s}, 3);
				break;
			case LEFT:
				g.fillPolygon(new int[]{cx - s, cx + s, cx + s}, new int[]{cy, cy - s, cy + s}, 3);
				break;
			case RIGHT:
				g.fillPolygon(new int[]{cx + s, cx - s, cx - s}, new int[]{cy, cy - s, cy + s}, 3);
				break;
			case CLOSE:
				g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.drawLine(cx - s, cy - s, cx + s, cy + s);
				g.drawLine(cx - s, cy + s, cx + s, cy - s);
				break;
			case MENU:
				g.setStroke(new BasicStroke(1));
				g.drawLine(cx - s, cy - s, cx + s, cy - s);
				g.drawLine(cx - s, cy, cx + s, cy);
				g.drawLine(cx - s, cy + s, cx + s, cy + s);
				break;
			default:
				break;
		}
	}

	private List<SpeciesDef> availableToAdd(List<String> team)
	{
		List<SpeciesDef> out = new ArrayList<>();
		for (SpeciesDef species : db.allSpecies())
		{
			if (!team.contains(species.getId()) && roster.isOwned(species.getId()))
			{
				out.add(species);
			}
		}
		return out;
	}

	private List<TrainerDef> nearbyTrainers()
	{
		List<TrainerDef> out = new ArrayList<>();
		for (String id : nearTrainers.get())
		{
			TrainerDef trainer = db.trainer(id);
			if (trainer != null)
			{
				out.add(trainer);
			}
		}
		out.sort(Comparator.comparing(TrainerDef::getName));
		return out;
	}

	private void clearButtons()
	{
		synchronized (this)
		{
			buttons.clear();
		}
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
