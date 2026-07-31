package com.petbattles.ui;

import com.petbattles.battle.BattleSession;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.PetInstance;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.engine.TrainerDef;
import com.petbattles.item.Item;
import com.petbattles.persist.RosterManager;
import com.petbattles.quest.Quest;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * In-client control hub. Collapsed it's a small launcher chip; it expands to one of several
 * panes — Team (reorder / remove / add / rest), Trainers (searchable chathead card list with
 * Locate / Battle), Quests (quest log with expandable objectives), Rest, or Challenge ("Battle
 * &lt;trainer&gt;" with the trainer's chathead and party). Expansion is context-driven
 * (near a bank with an injured pet → Rest; near a battleable trainer → Challenge) with
 * the chip and menu as the manual fallback. Hidden entirely during a battle, which locks the
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
		CHALLENGE,
		QUESTS,
		ITEMS,
		TRAINERS
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
	private static final int TRAINERS_VISIBLE = 3;
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
	// Item icons loaded lazily from /com/petbattles/items/<id>.png; a null value caches a miss.
	private final Map<String, BufferedImage> itemIconCache = new HashMap<>();

	private final List<Button> buttons = new ArrayList<>();
	private volatile Point hoverPoint;

	// Pane the user has explicitly opened; null means "follow context / collapsed". Read from the
	// AWT thread by the wheel handler (isScrollablePaneOpen), written on the client thread.
	private volatile Pane pinned;
	// Context we last auto-derived, and whether the user dismissed it (so it doesn't
	// immediately re-pop). Cleared when the context changes and re-triggers.
	private Pane lastContext;
	private boolean dismissed;
	private int addOffset;
	private int challengePage;
	// Trainers pane: which difficulty filter is active (null = all) and the scroll offset. The
	// separate "Random" filter is orthogonal to difficulty — when on it shows only random-event
	// NPCs (which have no fixed location) and difficulty is ignored.
	private TrainerDef.Difficulty trainerFilter;
	private boolean randomFilter;
	private int trainersOffset;
	// Trainers pane name search. Written on the client thread (render + marshalled key events);
	// searchFocused is read from the AWT thread by the key listener, so it is volatile.
	private String trainerSearch = "";
	private volatile boolean searchFocused;
	// Quests pane: the quest id whose detail box is expanded (null = none).
	private String expandedQuest;

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
		trainersOffset = 0;
		expandedQuest = null;
		trainerSearch = "";
		searchFocused = false;
	}

	// --- Trainers pane name search (fed by the sibling HubKeyListener while focused) ---

	public void focusSearch()
	{
		searchFocused = true;
	}

	public void blurSearch()
	{
		searchFocused = false;
	}

	public boolean isSearchFocused()
	{
		return searchFocused;
	}

	public void appendSearch(char c)
	{
		if (trainerSearch.length() < 24)
		{
			trainerSearch += c;
			trainersOffset = 0;
		}
	}

	public void backspaceSearch()
	{
		if (!trainerSearch.isEmpty())
		{
			trainerSearch = trainerSearch.substring(0, trainerSearch.length() - 1);
			trainersOffset = 0;
		}
	}

	public void clearSearch()
	{
		trainerSearch = "";
		trainersOffset = 0;
	}

	/**
	 * Mouse-wheel scroll routed here from the input handler: nudges whichever open pane has a
	 * scrollable list (Trainers cards, or the Team "add a pet" picker).
	 */
	public void scroll(int rotation)
	{
		if (pinned == Pane.TRAINERS)
		{
			trainersPage(rotation);
		}
		else if (pinned == Pane.TEAM)
		{
			addPage(rotation);
		}
	}

	/** Whether an open pane has a scrollable list, so the wheel handler knows to claim the event. */
	public boolean isScrollablePaneOpen()
	{
		return pinned == Pane.TRAINERS || pinned == Pane.TEAM;
	}

	public void toggleQuest(String questId)
	{
		expandedQuest = questId.equals(expandedQuest) ? null : questId;
	}

	public void trainersPage(int delta)
	{
		trainersOffset = Math.max(0, trainersOffset + delta);
	}

	/**
	 * Set the Trainers difficulty filter (null = show all), clearing the Random filter, and jump
	 * back to the first page.
	 */
	public void setTrainerFilter(TrainerDef.Difficulty difficulty)
	{
		trainerFilter = difficulty;
		randomFilter = false;
		trainersOffset = 0;
	}

	/** Show only random-event NPCs, clearing any difficulty selection. */
	public void setRandomFilter()
	{
		randomFilter = true;
		trainerFilter = null;
		trainersOffset = 0;
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
			searchFocused = false;
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

		// The search box only lives on the Trainers pane; drop focus if we've left it.
		if (pane != Pane.TRAINERS)
		{
			searchFocused = false;
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
			case QUESTS:
				return questsBody(g, out);
			case ITEMS:
				return itemsBody(g, out);
			case TRAINERS:
				return trainersBody(g, out);
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
			case QUESTS:
				return "Quests";
			case ITEMS:
				return "Items";
			case TRAINERS:
				return "Trainers";
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
		y = fullButton(g, out, y, "Trainers", "open:trainers", true);
		y = fullButton(g, out, y, "Quests", "open:quests", true);
		y = fullButton(g, out, y, "Items", "open:items", true);
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

	private int questsBody(Graphics2D g, List<Button> out)
	{
		int y = 26;
		if (!roster.isLoaded())
		{
			return loginHint(g, y);
		}
		for (Quest quest : Quest.values())
		{
			boolean complete = roster.getQuestStep(quest.getId()) >= Quest.STEP_COMPLETE;
			boolean expanded = quest.getId().equals(expandedQuest);

			// Row: title on the left, status tag on the right; the whole row toggles the detail box.
			// Taller than a plain button with generous side padding and a clear title/status gap.
			Rectangle row = new Rectangle(8, y, WIDTH - 16, 26);
			drawButtonBg(g, row, true);
			out.add(new Button(row, "quest:" + quest.getId()));
			g.setFont(FontManager.getRunescapeFont());
			String status = complete ? "Complete" : "In progress";
			int statusW = g.getFontMetrics().stringWidth(status);
			int textBaseline = y + 17;
			g.setColor(Color.WHITE);
			g.drawString(clip(g, quest.getTitle(), row.width - statusW - 30), row.x + 10, textBaseline);
			g.setColor(complete ? new Color(120, 200, 110) : HP_YELLOW);
			g.drawString(status, row.x + row.width - statusW - 10, textBaseline);
			y += 30;

			if (expanded)
			{
				// Rewards live in the Items panel now; a completed quest just reads as done here.
				String detail = complete ? "Quest completed." : quest.getHint();
				g.setFont(FontManager.getRunescapeFont());
				List<String> lines = wrapText(g, detail, WIDTH - 32);
				int lineH = 15;
				int boxH = lines.size() * lineH + 12;
				g.setColor(new Color(0, 0, 0, 90));
				g.fillRoundRect(8, y, WIDTH - 16, boxH, 6, 6);
				g.setColor(TEXT);
				int ty = y + 17;
				for (String line : lines)
				{
					g.drawString(line, 15, ty);
					ty += lineH;
				}
				y += boxH + 8;
			}
		}
		return y;
	}

	private int itemsBody(Graphics2D g, List<Button> out)
	{
		int y = 26;
		if (!roster.isLoaded())
		{
			return loginHint(g, y);
		}
		List<Item> items = ownedItems();
		if (items.isEmpty())
		{
			hint(g, "No items yet.", y);
			y += 16;
			hint(g, "Complete quests to earn rewards.", y);
			return y + 16;
		}
		for (Item item : items)
		{
			y = itemCard(g, out, y, item);
		}
		return y;
	}

	/**
	 * A held item: bundled icon on the left, name and a wrapped description on the right. Items are
	 * passive rewards, so the card carries no buttons — the card height grows to fit the text.
	 */
	private int itemCard(Graphics2D g, List<Button> out, int y, Item item)
	{
		int cardW = WIDTH - 16;
		int iconBox = 42;
		int textX = 12 + iconBox + 8;
		int textW = WIDTH - textX - 10;
		int lineH = 14;

		g.setFont(FontManager.getRunescapeFont());
		List<String> desc = wrapText(g, item.getDescription(), textW);
		int textBlockH = 8 /* name */ + 6 + desc.size() * lineH;
		int cardH = Math.max(iconBox + 12, textBlockH + 14);

		g.setColor(new Color(0, 0, 0, 90));
		g.fillRoundRect(8, y, cardW, cardH, 6, 6);

		Rectangle iconRect = new Rectangle(12, y + (cardH - iconBox) / 2, iconBox, iconBox);
		g.setColor(new Color(255, 255, 255, 18));
		g.fillRoundRect(iconRect.x, iconRect.y, iconBox, iconBox, 5, 5);
		BufferedImage icon = itemIcon(item.getId());
		if (icon != null)
		{
			drawFit(g, icon, iconRect.x + 5, iconRect.y + 5, iconBox - 10, iconBox - 10);
		}

		int ty = y + 16;
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(Color.WHITE);
		g.drawString(clip(g, item.getName(), textW), textX, ty);
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(TEXT);
		ty += 6;
		for (String line : desc)
		{
			ty += lineH;
			g.drawString(line, textX, ty);
		}
		return y + cardH + 6;
	}

	/** The items the player currently holds, derived from the roster state that granted each. */
	private List<Item> ownedItems()
	{
		List<Item> out = new ArrayList<>();
		if (roster.isRemoteBattlesUnlocked())
		{
			out.add(Item.REMOTE_BATTLE_DEVICE);
		}
		return out;
	}

	/** The bundled icon for an item id, or null (cached) if none is present. */
	private BufferedImage itemIcon(String id)
	{
		if (itemIconCache.containsKey(id))
		{
			return itemIconCache.get(id);
		}
		BufferedImage img = null;
		String path = "/com/petbattles/items/" + id + ".png";
		if (getClass().getResource(path) != null)
		{
			img = ImageUtil.loadImageResource(getClass(), path);
		}
		itemIconCache.put(id, img);
		return img;
	}

	private int trainersBody(Graphics2D g, List<Button> out)
	{
		int y = 26;
		if (!roster.isLoaded())
		{
			return loginHint(g, y);
		}
		// Category filter: All / Easy / Med / Hard / Random (the active one is highlighted).
		int fw = (WIDTH - 16 - 8) / 5;
		int fx = 8;
		filterButton(g, out, fx, y, fw, "All", "ALL", trainerFilter == null && !randomFilter);
		fx += fw + 2;
		filterButton(g, out, fx, y, fw, "Easy", "EASY",
			!randomFilter && trainerFilter == TrainerDef.Difficulty.EASY);
		fx += fw + 2;
		filterButton(g, out, fx, y, fw, "Med", "MEDIUM",
			!randomFilter && trainerFilter == TrainerDef.Difficulty.MEDIUM);
		fx += fw + 2;
		filterButton(g, out, fx, y, fw, "Hard", "HARD",
			!randomFilter && trainerFilter == TrainerDef.Difficulty.HARD);
		fx += fw + 2;
		filterButton(g, out, fx, y, fw, "Random", "RANDOM", randomFilter);
		y += 24;

		// Name search box (click to focus, then type — see HubKeyListener).
		y = searchBox(g, out, y);

		List<TrainerDef> list = filteredTrainers();
		if (list.isEmpty())
		{
			hint(g, trainerSearch.isEmpty() ? "No trainers match that filter."
				: "No trainers match \"" + trainerSearch + "\".", y);
			return y + 16;
		}
		int maxOffset = Math.max(0, list.size() - TRAINERS_VISIBLE);
		if (trainersOffset > maxOffset)
		{
			trainersOffset = maxOffset;
		}
		int end = Math.min(list.size(), trainersOffset + TRAINERS_VISIBLE);

		// Count + paging row.
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(MUTED);
		g.drawString((trainersOffset + 1) + "-" + end + " of " + list.size(), 10, y + 11);
		if (list.size() > TRAINERS_VISIBLE)
		{
			iconButton(g, out, new Rectangle(WIDTH - 42, y, 14, 14), Icon.UP, "trainers.page:-1", trainersOffset > 0);
			iconButton(g, out, new Rectangle(WIDTH - 24, y, 14, 14), Icon.DOWN, "trainers.page:1", trainersOffset < maxOffset);
		}
		y += 20;

		for (int i = trainersOffset; i < end; i++)
		{
			y = trainerCard(g, out, y, list.get(i));
		}
		return y;
	}

	private void filterButton(Graphics2D g, List<Button> out, int x, int y, int w, String label,
		String key, boolean active)
	{
		Rectangle r = new Rectangle(x, y, w, 20);
		Point hp = hoverPoint;
		boolean hover = hp != null && r.contains(hp);
		g.setColor(active || hover ? BUTTON_HOVER : BUTTON_BG);
		g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
		g.setColor(active ? new Color(220, 200, 120) : BUTTON_EDGE);
		g.setStroke(new BasicStroke(active ? 2 : 1));
		g.drawRoundRect(r.x, r.y, r.width, r.height, 6, 6);
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(active ? Color.WHITE : TEXT);
		FontMetrics fm = g.getFontMetrics();
		String t = clip(g, label, w - 4);
		g.drawString(t, r.x + (w - fm.stringWidth(t)) / 2, r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
		out.add(new Button(r, "trainers.filter:" + key));
	}

	/**
	 * The name-search input: a rounded field showing the query (or a muted placeholder), a blinking
	 * caret while focused, and a clear cross once there's text. Clicking the field submits
	 * {@code trainers.search.focus}; the sibling key listener then feeds keystrokes in.
	 */
	private int searchBox(Graphics2D g, List<Button> out, int y)
	{
		int h = 20;
		Rectangle box = new Rectangle(8, y, WIDTH - 16, h);
		g.setColor(new Color(0, 0, 0, 120));
		g.fillRoundRect(box.x, box.y, box.width, box.height, 6, 6);
		g.setColor(searchFocused ? new Color(220, 200, 120) : BUTTON_EDGE);
		g.setStroke(new BasicStroke(searchFocused ? 2 : 1));
		g.drawRoundRect(box.x, box.y, box.width, box.height, 6, 6);

		boolean empty = trainerSearch.isEmpty();
		g.setFont(FontManager.getRunescapeFont());
		FontMetrics fm = g.getFontMetrics();
		int textX = box.x + 6;
		int baseline = box.y + (h + fm.getAscent() - fm.getDescent()) / 2;
		int textRoom = box.width - 12 - (empty ? 0 : 16);
		// Placeholder only while idle-empty; once focused it's just the caret on a blank field.
		if (empty && !searchFocused)
		{
			g.setColor(MUTED);
			g.drawString(clip(g, "Search name…", textRoom), textX, baseline);
		}
		else if (!empty)
		{
			g.setColor(Color.WHITE);
			g.drawString(clip(g, trainerSearch, textRoom), textX, baseline);
		}

		if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0)
		{
			int caretX = textX + Math.min(fm.stringWidth(trainerSearch), textRoom) + 1;
			g.setColor(Color.WHITE);
			g.drawLine(caretX, box.y + 4, caretX, box.y + h - 4);
		}

		// Clear cross first so it wins the hit-test over the field button beneath it.
		if (!empty)
		{
			iconButton(g, out, new Rectangle(box.x + box.width - 18, box.y + 3, 14, 14),
				Icon.CLOSE, "trainers.search.clear", true);
		}
		out.add(new Button(box, "trainers.search.focus"));
		return y + h + 6;
	}

	private int trainerCard(Graphics2D g, List<Button> out, int y, TrainerDef trainer)
	{
		int cardW = WIDTH - 16;
		int cardH = 74;
		g.setColor(new Color(0, 0, 0, 90));
		g.fillRoundRect(8, y, cardW, cardH, 6, 6);

		int pw = 40;
		int ph = 48;
		Rectangle portraitRect = new Rectangle(12, y + 4, pw, ph);
		g.setColor(new Color(255, 255, 255, 18));
		g.fillRoundRect(portraitRect.x, portraitRect.y, pw, ph, 5, 5);
		BufferedImage portrait = portraits.portrait(trainer.getId());
		if (portrait != null)
		{
			drawFit(g, portrait, portraitRect.x + 1, portraitRect.y + 1, pw - 2, ph - 2);
		}
		else if (!trainer.getParty().isEmpty())
		{
			SpeciesDef lead = db.species(trainer.getParty().get(0).getSpecies());
			if (lead != null)
			{
				drawFit(g, sprites.itemImage(lead.itemIdAt(trainer.getParty().get(0).getLevel())),
					portraitRect.x + 6, portraitRect.y + 6, pw - 12, ph - 12);
			}
		}

		int textX = portraitRect.x + pw + 8;
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(Color.WHITE);
		g.drawString(clip(g, trainer.getName(), 8 + cardW - textX - 6), textX, y + 15);
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(MUTED);
		int maxLevel = trainer.getParty().stream().mapToInt(TrainerDef.PartyEntry::getLevel).max().orElse(1);
		int partyCount = trainer.getParty().size();
		g.drawString(clip(g, "Lv " + maxLevel + " - " + trainer.getDifficulty() + " - "
			+ partyCount + (partyCount == 1 ? " pet" : " pets"), 8 + cardW - textX - 6), textX, y + 30);

		// Buttons row: Battle (gated on being unlocked + a fit team) and Locate (if location known).
		int innerLeft = 12;
		int innerW = cardW - 8;
		int gap = 6;
		int bw = (innerW - gap) / 2;
		int btnY = y + cardH - 22;
		boolean unlocked = roster.isTrainerDefeated(trainer.getId())
			|| roster.isRemoteBattlesUnlocked()
			|| nearTrainers.get().contains(trainer.getId());
		boolean canFight = unlocked && !roster.getTeam().isEmpty() && roster.teamCanFight();
		Rectangle battle = new Rectangle(innerLeft, btnY, bw, 18);
		drawButton(g, battle, "Battle", canFight, false);
		if (canFight)
		{
			out.add(new Button(battle, "fight:" + trainer.getId()));
		}
		Rectangle locate = new Rectangle(innerLeft + bw + gap, btnY, innerW - bw - gap, 18);
		if (trainer.isRandomEvent())
		{
			// Random events teleport to the player — there's no fixed spot to mark, so the slot is a
			// non-clickable "Random" tag rather than a dead "Locate" button.
			drawButton(g, locate, "Random", false, false);
		}
		else
		{
			boolean canLocate = !trainer.getLocations().isEmpty();
			drawButton(g, locate, "Locate", canLocate, false);
			if (canLocate)
			{
				out.add(new Button(locate, "locate:" + trainer.getId()));
			}
		}
		return y + cardH + 6;
	}

	private List<TrainerDef> filteredTrainers()
	{
		String query = trainerSearch.trim().toLowerCase(Locale.ROOT);
		List<TrainerDef> out = new ArrayList<>();
		for (TrainerDef trainer : db.allTrainers())
		{
			boolean matchesCategory = randomFilter
				? trainer.isRandomEvent()
				: (trainerFilter == null || trainer.getDifficulty() == trainerFilter);
			boolean matchesName = query.isEmpty()
				|| trainer.getName().toLowerCase(Locale.ROOT).contains(query);
			if (matchesCategory && matchesName)
			{
				out.add(trainer);
			}
		}
		out.sort(Comparator.comparing(TrainerDef::getDifficulty)
			.thenComparingInt(t -> t.getParty().stream().mapToInt(TrainerDef.PartyEntry::getLevel).max().orElse(1))
			.thenComparing(TrainerDef::getName));
		return out;
	}

	/** Greedy word-wrap into lines no wider than {@code maxWidth} in the current font. */
	private List<String> wrapText(Graphics2D g, String text, int maxWidth)
	{
		FontMetrics fm = g.getFontMetrics();
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (line.length() > 0 && fm.stringWidth(candidate) > maxWidth)
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
