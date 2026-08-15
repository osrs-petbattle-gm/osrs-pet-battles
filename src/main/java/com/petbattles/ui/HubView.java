package com.petbattles.ui;

import com.petbattles.battle.BattleSession;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.ItemEffect;
import com.petbattles.engine.Leveling;
import com.petbattles.engine.MoveDef;
import com.petbattles.engine.PetInstance;
import com.petbattles.engine.PetType;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.engine.TrainerDef;
import com.petbattles.item.EquipItemDef;
import com.petbattles.item.Item;
import com.petbattles.persist.RosterManager;
import com.petbattles.quest.QuestDef;
import com.petbattles.quest.QuestManager;
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
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.ImageUtil;

/**
 * In-client control hub. Collapsed it's a small launcher chip; it expands to one of several
 * panes — Team (reorder / remove / add / rest), Trainers (searchable chathead card list with
 * Locate / Battle), Quests (quest log with expandable objectives), Rest, or Challenge ("Battle
 * &lt;trainer&gt;" with the trainer's chathead and party). Walking up to a trainer or a quest NPC
 * never takes the screen: the collapsed chip flashes instead, and clicking it opens what is
 * flashing (see {@link #currentAlert()}). Only Rest still auto-opens (at a bank with an injured
 * pet). Hidden entirely during a battle, which locks the swap/rest controls while a fight runs.
 *
 * <p>Each pane's body is laid out twice per frame: once under an empty clip to measure
 * its height (so the frame can be filled at exactly the right size), then again to
 * paint. Buttons are collected in view-local space and hit-tested by the caller.
 *
 * <p>Rendering-agnostic: this owns all pane state and drawing but knows nothing about how it is
 * shown. Two adapters consume it — {@link HubOverlay} (a floating RuneLite overlay) and
 * {@link HubPanel} (a Swing component docked in the side panel). A {@code docked} view fills its
 * host width, never collapses to a chip, and neither auto-opens nor flashes for world context.
 */
public class HubView
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
		TRAINERS,
		STORE,
		PET,
		EQUIP,
		DEV,
		DEVPETS
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

	/** One entry in the menu's icon row: the click action, its hover tooltip, and whether it's live. */
	private static final class MenuEntry
	{
		final String action;
		final String label;
		final boolean enabled;

		MenuEntry(String action, String label, boolean enabled)
		{
			this.action = action;
			this.label = label;
			this.enabled = enabled;
		}
	}

	/**
	 * A "there's something right here" prompt: what the collapsed chip flashes for, where clicking
	 * it leads, and an identity ({@code key}) so acknowledging one prompt doesn't silence the next.
	 */
	private static final class Alert
	{
		final Pane pane;
		final String action;
		final String label;
		final String key;

		Alert(Pane pane, String action, String label, String key)
		{
			this.pane = pane;
			this.action = action;
			this.label = label;
			this.key = key;
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

	// Body width in px. Mutable so a docked panel can match its component width each paint; the
	// floating overlay keeps a fixed value.
	private int width;
	// Docked (side-panel) mode vs floating overlay. Docked never collapses to a chip and does not
	// auto-open world-context panes; it defaults to the Menu instead.
	private final boolean docked;
	// Collapsed launcher size, matched to an OSRS interface tab stone (~33px).
	private static final int CHIP = 33;
	private static final int ADD_VISIBLE = 4;
	private static final int TRAINERS_VISIBLE = 3;
	private static final int PAD = 8;
	// Team pane slot geometry (shared by the layout and the drag drop-index calculation).
	private static final int TEAM_SLOT_W = 46;
	private static final int TEAM_SLOT_H = 40;
	private static final int TEAM_SLOT_GAP = 6;

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
	private static final Color COIN = new Color(255, 210, 90);

	private final PetDatabase db;
	private final RosterManager roster;
	private final QuestManager questManager;
	private final Sprites sprites;
	private final Portraits portraits;
	private final BattleSession session;
	private final BooleanSupplier atBank;
	private final Supplier<Set<String>> nearTrainers;
	private final TooltipManager tooltipManager;
	private final BufferedImage chipIcon;
	// Item icons loaded lazily from /com/petbattles/items/<id>.png; a null value caches a miss.
	private final Map<String, BufferedImage> itemIconCache = new HashMap<>();
	// Menu nav icons (OSRS clan motif symbols) from /com/petbattles/icons/menu/<name>.png.
	private final Map<String, BufferedImage> menuIconCache = new HashMap<>();

	private final List<Button> buttons = new ArrayList<>();
	private volatile Point hoverPoint;
	// The menu icon label to show as a cursor tooltip this frame (null = none). Written and read
	// only on the render thread; added to the TooltipManager once per frame in render().
	private String hoverTooltip;
	// Team drag-to-reorder: the species being dragged and the cursor's overlay-local position.
	// Written from the AWT thread (input handler), read on the render thread; both volatile.
	private volatile String draggingSpecies;
	private volatile Point dragPoint;

	// Pane the user has explicitly opened; null means "follow context / collapsed". Read from the
	// AWT thread by the wheel handler (isScrollablePaneOpen), written on the client thread.
	private volatile Pane pinned;
	// Context we last auto-derived, and whether the user dismissed it (so it doesn't
	// immediately re-pop). Cleared when the context changes and re-triggers.
	private Pane lastContext;
	private boolean dismissed;
	// The chip's flashing prompt: the key of the alert last seen, and whether the player has opened
	// its pane. Acknowledging stops the pulse (the chip stays put) until a different alert comes up,
	// so standing next to a trainer you've already looked at isn't a permanent blinking light.
	private String alertKey;
	private boolean alertAcknowledged;
	private int addOffset;
	private int challengePage;
	// Trainers pane: which difficulty filter is active (null = all) and the scroll offset. The
	// separate "Random" filter is orthogonal to difficulty — when on it shows only random-event
	// NPCs (which have no fixed location) and difficulty is ignored.
	private TrainerDef.Difficulty trainerFilter;
	private boolean randomFilter;
	private int trainersOffset;
	// Shared name-search buffer for the Trainers and Roster panes (only one is ever visible at a
	// time). Written on the render thread (render + marshalled key events); searchFocused is read
	// from the AWT thread by the key listener, so it is volatile.
	private String trainerSearch = "";
	private volatile boolean searchFocused;
	// Quests pane: the quest id whose detail box is expanded (null = none).
	private String expandedQuest;
	// Store list scroll offset.
	private int storeOffset;
	// Dev pet-unlock list scroll offset.
	private int devPetsOffset;
	// PET pane: the species whose detail (moves / held item / dev) is shown.
	private String petSpecies;
	// EQUIP pane: the held item being assigned to a team pet.
	private String equipItemId;
	// Dev pane: a pending destructive action awaiting a second "confirm" click, or null.
	private String pendingConfirm;

	public HubView(PetDatabase db, RosterManager roster, QuestManager questManager, Sprites sprites,
		Portraits portraits, BattleSession session, BooleanSupplier atBank,
		Supplier<Set<String>> nearTrainers, TooltipManager tooltipManager, int width, boolean docked)
	{
		this.db = db;
		this.roster = roster;
		this.questManager = questManager;
		this.sprites = sprites;
		this.portraits = portraits;
		this.session = session;
		this.atBank = atBank;
		this.nearTrainers = nearTrainers;
		this.tooltipManager = tooltipManager;
		this.chipIcon = ImageUtil.loadImageResource(getClass(), "/com/petbattles/icons/panel_icon.png");
		this.width = width;
		this.docked = docked;
	}

	public synchronized List<Button> getButtons()
	{
		return new ArrayList<>(buttons);
	}

	/** Set the body width (the docked panel calls this each paint to match its component width). */
	public void setWidth(int width)
	{
		this.width = width;
	}

	/** The hover-text computed during the last render (or null); the docked panel shows it natively. */
	public String getHoverTooltip()
	{
		return hoverTooltip;
	}

	public void setHoverPoint(Point localPoint)
	{
		this.hoverPoint = localPoint;
	}

	// --- team drag-to-reorder (driven from the input handler on the AWT thread) ---

	public void beginTeamDrag(String speciesId, Point localPoint)
	{
		this.draggingSpecies = speciesId;
		this.dragPoint = localPoint;
	}

	public void updateDragPoint(Point localPoint)
	{
		if (draggingSpecies != null)
		{
			this.dragPoint = localPoint;
		}
	}

	public void endTeamDrag()
	{
		this.draggingSpecies = null;
		this.dragPoint = null;
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
		storeOffset = 0;
		devPetsOffset = 0;
		expandedQuest = null;
		trainerSearch = "";
		searchFocused = false;
		pendingConfirm = null;
		draggingSpecies = null;
		dragPoint = null;
	}

	/** Open the per-pet detail pane for a species (moves / held item / team / dev). */
	public void openPet(String speciesId)
	{
		openPane(Pane.PET);
		petSpecies = speciesId;
	}

	/** Open the Quests pane with one quest's story already expanded (where the chip's flash leads). */
	public void openQuest(String questId)
	{
		openPane(Pane.QUESTS);
		expandedQuest = questId;
	}

	/** Open the equip chooser for a held item (pick which team pet wears it). */
	public void openEquip(String itemId)
	{
		openPane(Pane.EQUIP);
		equipItemId = itemId;
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
			devPetsOffset = 0;
		}
	}

	public void backspaceSearch()
	{
		if (!trainerSearch.isEmpty())
		{
			trainerSearch = trainerSearch.substring(0, trainerSearch.length() - 1);
			trainersOffset = 0;
			devPetsOffset = 0;
		}
	}

	public void clearSearch()
	{
		trainerSearch = "";
		trainersOffset = 0;
		devPetsOffset = 0;
	}

	/**
	 * Mouse-wheel scroll routed here from the input handler: nudges whichever open pane has a
	 * scrollable list (Trainers cards, the Store list, or the Team "add a pet" picker).
	 */
	public void scroll(int rotation)
	{
		if (pinned == Pane.TRAINERS)
		{
			trainersPage(rotation);
		}
		else if (pinned == Pane.STORE)
		{
			storeOffset = Math.max(0, storeOffset + rotation);
		}
		else if (pinned == Pane.DEVPETS)
		{
			devPetsOffset = Math.max(0, devPetsOffset + rotation);
		}
		else if (pinned == Pane.TEAM)
		{
			addPage(rotation);
		}
	}

	/** Whether an open pane has a scrollable list, so the wheel handler knows to claim the event. */
	public boolean isScrollablePaneOpen()
	{
		return pinned == Pane.TRAINERS || pinned == Pane.TEAM
			|| pinned == Pane.STORE || pinned == Pane.DEVPETS;
	}

	public void toggleQuest(String questId)
	{
		expandedQuest = questId.equals(expandedQuest) ? null : questId;
	}

	public void trainersPage(int delta)
	{
		trainersOffset = Math.max(0, trainersOffset + delta);
	}

	public void storePage(int delta)
	{
		storeOffset = Math.max(0, storeOffset + delta);
	}

	public void devPetsPage(int delta)
	{
		devPetsOffset = Math.max(0, devPetsOffset + delta);
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

	public Dimension render(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

		// Battle lock: no team edits or rest while a fight runs. The floating overlay vanishes
		// entirely; the docked panel can't disappear, so it shows a passive note instead.
		if (session.isActive())
		{
			searchFocused = false;
			draggingSpecies = null;
			dragPoint = null;
			clearButtons();
			return docked ? drawBattleNote(g) : null;
		}

		// The docked panel is a deliberate place the player looks at, so it never auto-opens a
		// world-context pane (nor flashes for one); only the floating overlay does.
		Pane context = docked ? null : currentContext();
		if (context != lastContext)
		{
			lastContext = context;
			dismissed = false;
		}

		Alert alert = docked ? null : currentAlert();
		String key = alert != null ? alert.key : null;
		if (!Objects.equals(key, alertKey))
		{
			alertKey = key;
			alertAcknowledged = false;
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
			pane = docked ? Pane.MENU : Pane.COLLAPSED;
		}
		// Looking at what's flashing counts as reading it.
		if (alert != null && pane == alert.pane)
		{
			alertAcknowledged = true;
		}

		// The search box lives on the Trainers and dev pet-unlock panes; drop focus if we've left them.
		if (pane != Pane.TRAINERS && pane != Pane.DEVPETS)
		{
			searchFocused = false;
		}

		List<Button> out = new ArrayList<>();
		// Reset before drawing; menuBody sets it while hovering. It's added to the TooltipManager
		// once here rather than inside menuBody, which the frame's measure pass runs a second time.
		hoverTooltip = null;
		Dimension size = pane == Pane.COLLAPSED
			? drawChip(g, out, alertAcknowledged ? null : alert)
			: drawPane(g, out, pane);
		// The floating overlay pushes hover text to the in-game TooltipManager; the docked panel
		// exposes it via getHoverTooltip() for a native Swing tooltip instead.
		if (hoverTooltip != null && !docked)
		{
			tooltipManager.add(new Tooltip(hoverTooltip));
		}

		synchronized (this)
		{
			buttons.clear();
			buttons.addAll(out);
		}
		return size;
	}

	/**
	 * The pane context wants open right now, or null. Only Rest (at a bank with an injured pet) —
	 * walking up to a trainer or quest NPC flashes the chip instead of taking the screen, see
	 * {@link #currentAlert()}.
	 */
	private Pane currentContext()
	{
		if (roster.isLoaded() && atBank.getAsBoolean() && roster.anyPetInjured())
		{
			return Pane.REST;
		}
		return null;
	}

	/**
	 * What the collapsed chip should flash for right now, or null. A quest whose current chapter is
	 * waiting on an NPC standing here outranks a plain trainer challenge, since the quest view is
	 * where that scene plays out. Unlike Rest this never opens itself — it only pulses the chip, so
	 * it is safe to raise while banking too (the Draynor guard stands at a bank).
	 */
	private Alert currentAlert()
	{
		if (!roster.isLoaded())
		{
			return null;
		}
		Set<String> near = nearTrainers.get();
		if (near.isEmpty())
		{
			return null;
		}
		Alert quest = questAlert(near);
		if (quest != null)
		{
			return quest;
		}
		List<TrainerDef> nearby = nearbyTrainers();
		if (nearby.isEmpty())
		{
			return null;
		}
		StringBuilder key = new StringBuilder("trainer");
		for (TrainerDef trainer : nearby)
		{
			key.append(':').append(trainer.getId());
		}
		String label = nearby.size() == 1
			? "Challenge " + nearby.get(0).getName()
			: nearby.size() + " trainers nearby";
		return new Alert(Pane.CHALLENGE, "open:challenge", label, key.toString());
	}

	/**
	 * The alert for a quest with something to do right where the player is standing: its current
	 * chapter's gate NPC (whose conversation / Challenge is now live) or, for the hunt, an unbeaten
	 * suspect. Chapters with no gate NPC are playable anywhere, so there is nothing here to point at.
	 */
	private Alert questAlert(Set<String> near)
	{
		for (QuestDef quest : db.allQuests())
		{
			if (!questManager.isAvailable(quest))
			{
				continue;
			}
			QuestDef.Chapter chapter = questManager.currentChapter(quest.getId());
			if (chapter == null)
			{
				continue;
			}
			boolean hit = chapter.getGateTrainer() != null && near.contains(chapter.getGateTrainer());
			if (!hit && chapter.isHunt())
			{
				for (String member : chapter.getBattlePool())
				{
					if (near.contains(member) && !questManager.isHuntMemberBeaten(quest, member))
					{
						hit = true;
						break;
					}
				}
			}
			if (hit)
			{
				String title = chapter.getTitle() != null ? chapter.getTitle() : quest.getTitle();
				return new Alert(Pane.QUESTS, "open:quest:" + quest.getId(), title,
					"quest:" + quest.getId() + "#" + chapter.getStep());
			}
		}
		return null;
	}

	// --- frame orchestration ---

	/**
	 * The collapsed launcher. With an {@code alert} it pulses — a gold wash and edge driven off the
	 * wall clock — and its click opens what is flashing rather than the menu.
	 */
	private Dimension drawChip(Graphics2D g, List<Button> out, Alert alert)
	{
		g.setColor(PANEL_BG);
		g.fillRoundRect(0, 0, CHIP, CHIP, 8, 8);
		// One sin per frame: the overlay redraws constantly, so the pulse costs nothing to animate.
		float pulse = alert == null ? 0f
			: (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 260.0));
		if (alert != null)
		{
			g.setColor(new Color(COIN.getRed(), COIN.getGreen(), COIN.getBlue(), (int) (25 + 55 * pulse)));
			g.fillRoundRect(0, 0, CHIP, CHIP, 8, 8);
		}
		g.setColor(alert == null ? PANEL_EDGE : blend(PANEL_EDGE, COIN, pulse));
		g.setStroke(new BasicStroke(2));
		g.drawRoundRect(0, 0, CHIP, CHIP, 8, 8);
		if (chipIcon != null)
		{
			int pad = 4;
			g.drawImage(chipIcon, pad, pad, CHIP - 2 * pad, CHIP - 2 * pad, null);
		}
		Rectangle rect = new Rectangle(0, 0, CHIP, CHIP);
		if (alert != null)
		{
			Point hp = hoverPoint;
			if (hp != null && rect.contains(hp))
			{
				hoverTooltip = alert.label;
			}
		}
		out.add(new Button(rect, alert != null ? alert.action : "chip"));
		return new Dimension(CHIP, CHIP);
	}

	/** Linear mix of two colours, {@code t} = 0 gives {@code a} and 1 gives {@code b}. */
	private static Color blend(Color a, Color b, float t)
	{
		return new Color(
			(int) (a.getRed() + (b.getRed() - a.getRed()) * t),
			(int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
			(int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}

	/** Docked-only placeholder shown while a battle runs (the overlay just hides instead). */
	private Dimension drawBattleNote(Graphics2D g)
	{
		int height = 40;
		g.setColor(PANEL_BG);
		g.fillRoundRect(0, 0, width, height, 10, 10);
		g.setColor(PANEL_EDGE);
		g.setStroke(new BasicStroke(2));
		g.drawRoundRect(0, 0, width, height, 10, 10);
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(MUTED);
		String line = "Battle in progress…";
		int tw = g.getFontMetrics().stringWidth(line);
		g.drawString(line, (width - tw) / 2, 25);
		return new Dimension(width, height);
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
		return new Dimension(width, height);
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
			case STORE:
				return storeBody(g, out);
			case PET:
				return petBody(g, out);
			case EQUIP:
				return equipBody(g, out);
			case DEV:
				return devBody(g, out);
			case DEVPETS:
				return devPetsBody(g, out);
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
			case STORE:
				return "Store";
			case EQUIP:
			{
				EquipItemDef it = equipItemId == null ? null : db.equipItem(equipItemId);
				return it == null ? "Equip" : "Equip " + it.getName();
			}
			case DEV:
				return "Dev tools";
			case DEVPETS:
				return "Unlock pets";
			case PET:
			{
				SpeciesDef s = petSpecies == null ? null : db.species(petSpecies);
				return s == null ? "Pet" : s.getName();
			}
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

		// Nav as a compact row of icon buttons (saves the vertical space the old text list ate).
		// Actions are unchanged, so the input handler needs no edits. Context-dependent entries
		// (Rest when hurt, Challenge when a trainer is near) append like the old text menu did.
		List<MenuEntry> entries = new ArrayList<>();
		entries.add(new MenuEntry("open:team", "Manage team", true));
		entries.add(new MenuEntry("open:trainers", "Trainers", true));
		entries.add(new MenuEntry("open:store", "Store", true));
		entries.add(new MenuEntry("open:quests", "Quests", true));
		entries.add(new MenuEntry("open:items", "Items", true));
		if (roster.anyPetInjured())
		{
			// Rests in one click like the Team pane's button; the Rest pane still auto-opens on its
			// own when you reach a bank hurt.
			boolean canRest = atBank.getAsBoolean();
			entries.add(new MenuEntry("rest", canRest ? "Rest pets" : "Rest pets (visit a bank)", canRest));
		}
		if (!nearTrainers.get().isEmpty())
		{
			entries.add(new MenuEntry("open:challenge", "Challenge nearby trainer", true));
		}
		if (roster.isDevSelectEnabled())
		{
			entries.add(new MenuEntry("open:dev", "Dev tools", true));
		}

		int n = entries.size();
		int gap = 4;
		int size = Math.min(32, (width - 2 * PAD - (n - 1) * gap) / n);
		int rowW = n * size + (n - 1) * gap;
		int x = (width - rowW) / 2;
		Point hp = hoverPoint;
		for (MenuEntry e : entries)
		{
			Rectangle r = new Rectangle(x, y, size, size);
			drawButtonBg(g, r, e.enabled);
			drawMenuGlyph(g, r, e.action);
			if (e.enabled)
			{
				out.add(new Button(r, e.action));
			}
			else
			{
				// Dim the whole slot when the action isn't available (e.g. Rest away from a bank).
				g.setColor(new Color(20, 24, 28, 150));
				g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
			}
			// Native hover tooltip near the cursor (no overlay space), like an OSRS tab. Recorded
			// here and added once in render(), so the frame's measure pass can't double it.
			if (hp != null && r.contains(hp))
			{
				hoverTooltip = e.label;
			}
			x += size + gap;
		}
		return y + size + 2;
	}

	/**
	 * A menu icon glyph, drawn as a vector so no image assets are needed. Deliberately simple and
	 * legible at ~40px rather than pixel-faithful to OSRS.
	 */
	private void drawMenuGlyph(Graphics2D g, Rectangle r, String action)
	{
		BufferedImage icon = menuIcon(action);
		if (icon != null)
		{
			int box = r.width - 8;
			drawFit(g, icon, r.x + 4, r.y + 4, box, box);
			return;
		}
		// Vector fallback if the icon resource is missing.
		int cx = r.x + r.width / 2;
		int cy = r.y + r.height / 2;
		g.setColor(TEXT);
		Stroke os = g.getStroke();
		switch (action)
		{
			case "open:team":  // paw print
				g.fillOval(cx - 7, cy - 5, 4, 4);
				g.fillOval(cx - 2, cy - 7, 4, 4);
				g.fillOval(cx + 3, cy - 5, 4, 4);
				g.fillOval(cx - 5, cy - 1, 10, 9);
				break;
			case "open:trainers":  // person
				g.fillOval(cx - 4, cy - 9, 8, 8);
				g.fillArc(cx - 7, cy, 14, 14, 0, 180);
				break;
			case "open:quests":  // star
				drawStar(g, cx, cy, 8, 3.4);
				break;
			case "open:items":  // backpack
				g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.drawArc(cx - 4, cy - 9, 8, 8, 0, 180);
				g.fillRoundRect(cx - 7, cy - 4, 14, 12, 4, 4);
				break;
			case "rest":  // "Zz" (sleep)
				g.setFont(FontManager.getRunescapeFont());
				g.drawString("z", cx - 6, cy + 6);
				g.setFont(FontManager.getRunescapeBoldFont());
				g.drawString("Z", cx + 1, cy);
				break;
			case "open:challenge":  // crossed swords
				g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.drawLine(cx - 7, cy + 7, cx + 7, cy - 7);
				g.drawLine(cx - 7, cy - 7, cx + 7, cy + 7);
				g.drawLine(cx - 9, cy + 5, cx - 5, cy + 9);
				g.drawLine(cx + 5, cy + 9, cx + 9, cy + 5);
				break;
			case "open:store":  // wizard hat (OSRS clan-settings motif)
				// Brim, then the cone, then a small punched-out star so it reads as "wizard".
				g.fillOval(cx - 9, cy + 4, 18, 4);
				g.fillPolygon(new int[]{cx, cx - 7, cx + 7}, new int[]{cy - 9, cy + 5, cy + 5}, 3);
				g.setColor(PANEL_BG);
				drawStar(g, cx, cy, 2.6, 1.1);
				g.setColor(TEXT);
				break;
			case "open:dev":  // wrench (settings)
				g.setFont(FontManager.getRunescapeBoldFont());
				g.drawString("{}", cx - 7, cy + 5);
				break;
			default:
				break;
		}
		g.setStroke(os);
	}

	private void drawStar(Graphics2D g, int cx, int cy, double outer, double inner)
	{
		int[] xs = new int[10];
		int[] ys = new int[10];
		for (int i = 0; i < 10; i++)
		{
			double ang = -Math.PI / 2 + i * Math.PI / 5;
			double rad = (i % 2 == 0) ? outer : inner;
			xs[i] = cx + (int) Math.round(Math.cos(ang) * rad);
			ys[i] = cy + (int) Math.round(Math.sin(ang) * rad);
		}
		g.fillPolygon(xs, ys, 10);
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
			y += 20;
		}
		else
		{
			y = teamSlots(g, out, y, team, canEdit);
		}

		// Rest button only when there's something to rest (nothing shown once all pets are rested).
		if (roster.anyPetInjured())
		{
			y += 4;
			y = fullButton(g, out, y, canEdit ? "Rest pets" : "Rest pets (visit a bank)", "rest", canEdit);
		}

		// Add-a-pet picker: owned pets not already on the team, paged. Its header row (with the
		// paging arrows aligned to it) sits clear of the slots above.
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
			iconButton(g, out, new Rectangle(width - 42, y + 2, 14, 14), Icon.UP, "team.page:-1", addOffset > 0);
			iconButton(g, out, new Rectangle(width - 24, y + 2, 14, 14), Icon.DOWN, "team.page:1", addOffset < maxOffset);
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
				y = addPetRow(g, out, y, available.get(i));
			}
		}
		return y;
	}

	/**
	 * The battle team as a horizontal row of pet slots: item icon, level beneath, and a remove
	 * cross in the corner. Slots can be dragged onto one another to reorder the battle positions;
	 * hovering a slot tooltips the pet's name. The pet being dragged renders a floating copy that
	 * follows the cursor.
	 */
	private int teamSlots(Graphics2D g, List<Button> out, int y, List<String> team, boolean canEdit)
	{
		Point hp = hoverPoint;
		String dragging = draggingSpecies;
		Point dp = dragPoint;
		int pitch = TEAM_SLOT_W + TEAM_SLOT_GAP;
		int n = team.size();
		int advance = TEAM_SLOT_H + 19;

		// While dragging, the other pets shift to open a gap at the drop position — a live preview
		// of the new order — and the dragged pet floats under the cursor.
		if (dragging != null && dp != null && team.contains(dragging))
		{
			List<String> remaining = new ArrayList<>(team);
			remaining.remove(dragging);
			int insertIndex = teamDropIndex(dp.x);
			int r = 0;
			for (int col = 0; col < n; col++)
			{
				Rectangle slot = new Rectangle(8 + col * pitch, y, TEAM_SLOT_W, TEAM_SLOT_H);
				if (col == insertIndex)
				{
					drawDropPlaceholder(g, slot);
				}
				else if (r < remaining.size())
				{
					drawPetSlotVisual(g, slot, remaining.get(r++), false);
				}
			}
			SpeciesDef ds = db.species(dragging);
			if (ds != null)
			{
				PetInstance dpet = roster.getPet(dragging);
				drawFit(g, sprites.itemImage(ds.itemIdAt(dpet != null ? dpet.getLevel() : 1)),
					dp.x - 18, dp.y - 18, 36, 36);
			}
			return y + advance;
		}

		for (int i = 0; i < n; i++)
		{
			String speciesId = team.get(i);
			if (db.species(speciesId) == null)
			{
				continue;
			}
			Rectangle slot = new Rectangle(8 + i * pitch, y, TEAM_SLOT_W, TEAM_SLOT_H);
			boolean hover = hp != null && slot.contains(hp);
			drawPetSlotVisual(g, slot, speciesId, hover);

			// Remove badge just outside the top-right corner; added before the slot button so it
			// wins the hit-test over the drag.
			Rectangle xr = null;
			if (canEdit)
			{
				int d = 12;
				xr = new Rectangle(slot.x + TEAM_SLOT_W - d / 2, slot.y - d / 2, d, d);
				drawRemoveCross(g, xr);
				out.add(new Button(xr, "team.remove:" + speciesId));
			}
			out.add(new Button(slot, "team.slot:" + speciesId));
			if (xr != null && hp != null && xr.contains(hp))
			{
				hoverTooltip = "Remove from team";
			}
			else if (hover)
			{
				PetInstance pet = roster.getPet(speciesId);
				SpeciesDef species = db.species(speciesId);
				int level = pet != null ? pet.getLevel() : 1;
				hoverTooltip = pet != null ? species.nameFor(pet.getActiveVariantId(), level) : species.getName();
			}
		}
		return y + advance;
	}

	/** Draw a single team slot's frame, pet icon and level (no buttons). */
	private void drawPetSlotVisual(Graphics2D g, Rectangle slot, String speciesId, boolean hover)
	{
		SpeciesDef species = db.species(speciesId);
		if (species == null)
		{
			return;
		}
		PetInstance pet = roster.getPet(speciesId);
		int level = pet != null ? pet.getLevel() : 1;
		boolean fainted = pet != null && pet.isFainted();
		boolean hurt = pet != null && !fainted && pet.getCurrentHp() != null;

		g.setColor(new Color(0, 0, 0, 90));
		g.fillRoundRect(slot.x, slot.y, slot.width, slot.height, 5, 5);
		g.setColor(hover ? new Color(220, 200, 120) : fainted ? HP_RED : hurt ? HP_YELLOW : BUTTON_EDGE);
		g.setStroke(new BasicStroke(hover ? 2 : 1));
		g.drawRoundRect(slot.x, slot.y, slot.width, slot.height, 5, 5);
		drawFit(g, sprites.itemImage(species.itemIdAt(level)), slot.x + 3, slot.y + 3, slot.width - 6, slot.height - 6);

		g.setFont(FontManager.getRunescapeFont());
		g.setColor(fainted ? HP_RED : hurt ? HP_YELLOW : TEXT);
		String lv = fainted ? "KO" : "Lv " + level;
		g.drawString(lv, slot.x + (slot.width - g.getFontMetrics().stringWidth(lv)) / 2, slot.y + slot.height + 15);
	}

	/** Draw the dashed "drop here" gap the dragged pet will fill. */
	private void drawDropPlaceholder(Graphics2D g, Rectangle slot)
	{
		g.setColor(new Color(220, 200, 120, 45));
		g.fillRoundRect(slot.x, slot.y, slot.width, slot.height, 5, 5);
		g.setColor(new Color(220, 200, 120));
		g.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{4, 3}, 0));
		g.drawRoundRect(slot.x, slot.y, slot.width, slot.height, 5, 5);
	}

	/** The battle position a drag would drop into, from the cursor's overlay-local x. */
	public int teamDropIndex(int localX)
	{
		int n = roster.getTeam().size();
		if (n <= 1)
		{
			return 0;
		}
		int pitch = TEAM_SLOT_W + TEAM_SLOT_GAP;
		return Math.max(0, Math.min((localX - 8) / pitch, n - 1));
	}

	private int addPetRow(Graphics2D g, List<Button> out, int y, SpeciesDef species)
	{
		PetInstance pet = roster.getPet(species.getId());
		int level = pet != null ? pet.getLevel() : 1;
		String name = pet != null ? species.nameFor(pet.getActiveVariantId(), level) : species.getName();
		Rectangle r = new Rectangle(8, y, width - 16, 24);
		drawButtonBg(g, r, true);
		drawFit(g, sprites.itemImage(species.itemIdAt(level)), r.x + 3, r.y + 2, 20, 20);
		g.setFont(FontManager.getRunescapeFont());
		String lv = "Lv " + level;
		int lvW = g.getFontMetrics().stringWidth(lv);
		g.setColor(MUTED);
		g.drawString(lv, r.x + r.width - lvW - 8, r.y + 16);
		g.setColor(Color.WHITE);
		g.drawString(clip(g, name, r.width - 28 - lvW - 14), r.x + 28, r.y + 16);
		out.add(new Button(r, "team.add:" + species.getId()));
		Point hp = hoverPoint;
		if (hp != null && r.contains(hp))
		{
			hoverTooltip = "Click to add to team";
		}
		return y + 26;
	}

	private void drawRemoveCross(Graphics2D g, Rectangle r)
	{
		g.setColor(new Color(200, 40, 36, 240));
		g.fillOval(r.x, r.y, r.width, r.height);
		g.setColor(new Color(35, 8, 8, 220));
		g.setStroke(new BasicStroke(1));
		g.drawOval(r.x, r.y, r.width, r.height);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		int cx = r.x + r.width / 2;
		int cy = r.y + r.height / 2;
		int s = r.width / 4;
		g.drawLine(cx - s, cy - s, cx + s, cy + s);
		g.drawLine(cx - s, cy + s, cx + s, cy - s);
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
		g.drawString(clip(g, line, width - 20), 10, y + 4);
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
			iconButton(g, out, new Rectangle(width - 74, 3, 14, 14), Icon.LEFT, "chal.page:-1", true);
			iconButton(g, out, new Rectangle(width - 58, 3, 14, 14), Icon.RIGHT, "chal.page:1", true);
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
		g.drawString(clip(g, trainer.getName(), width - textX - 8), textX, y + 18);
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
			g.drawString(clip(g, pname, width - 12 - rowIcon - 8 - lvW - 10), 12 + rowIcon + 8, y + 17);
			g.setColor(MUTED);
			g.drawString(lv, width - 12 - lvW, y + 17);
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
		// Data-described story quests: a row that expands to the current chapter's conversation.
		for (QuestDef quest : db.allQuests())
		{
			if (!questManager.isAvailable(quest))
			{
				continue;
			}
			boolean complete = questManager.isComplete(quest.getId());
			boolean expanded = quest.getId().equals(expandedQuest);
			y = questRow(g, out, quest.getId(), quest.getTitle(), complete, y);
			if (expanded)
			{
				y = storyBody(g, quest, complete, y);
			}
		}
		return y;
	}

	/**
	 * One quest-log row: title on the left, status tag on the right; the whole row toggles its
	 * detail/story box. Returns the y below the row.
	 */
	private int questRow(Graphics2D g, List<Button> out, String questId, String title, boolean complete, int y)
	{
		Rectangle row = new Rectangle(8, y, width - 16, 26);
		drawButtonBg(g, row, true);
		out.add(new Button(row, "quest:" + questId));
		g.setFont(FontManager.getRunescapeFont());
		String status = complete ? "Complete" : "In progress";
		int statusW = g.getFontMetrics().stringWidth(status);
		int textBaseline = y + 17;
		g.setColor(Color.WHITE);
		g.drawString(clip(g, title, row.width - statusW - 30), row.x + 10, textBaseline);
		g.setColor(complete ? new Color(120, 200, 110) : HP_YELLOW);
		g.drawString(status, row.x + row.width - statusW - 10, textBaseline);
		return y + 30;
	}

	/** A plain wrapped-text detail box (used by the legacy quest hint). Returns the y below it. */
	private int questDetailBox(Graphics2D g, String detail, int y)
	{
		g.setFont(FontManager.getRunescapeFont());
		List<String> lines = wrapText(g, detail, width - 32);
		int lineH = 15;
		int boxH = lines.size() * lineH + 12;
		g.setColor(new Color(0, 0, 0, 90));
		g.fillRoundRect(8, y, width - 16, boxH, 6, 6);
		g.setColor(TEXT);
		int ty = y + 17;
		for (String line : lines)
		{
			g.drawString(line, 15, ty);
			ty += lineH;
		}
		return y + boxH + 8;
	}

	/**
	 * The expanded story view for a data quest: a read-only log of where the player has got to — the
	 * chapter title and its indirect {@code objective} (point, don't name the place), plus the hunt's
	 * tally when one is running. The talking and the Challenge that follows it belong to the quest
	 * dialog frame ({@link com.petbattles.quest.QuestDialogSession}), which is the only surface the
	 * player converses on. A completed quest just reads as done.
	 */
	private int storyBody(Graphics2D g, QuestDef quest, boolean complete, int y)
	{
		QuestDef.Chapter chapter = complete ? null : questManager.currentChapter(quest.getId());
		if (chapter == null)
		{
			return questDetailBox(g, "Quest completed.", y);
		}
		String objective = chapter.getObjective() != null ? chapter.getObjective()
			: "Seek out your next lead.";
		y = objectiveBox(g, chapter.getTitle(), objective, y);
		return chapter.isHunt() ? huntProgress(g, quest, chapter, y) : y;
	}

	/** The indirect objective box: chapter title + a muted-yellow "where to look" hint, no chathead. */
	private int objectiveBox(Graphics2D g, String title, String objective, int y)
	{
		g.setFont(FontManager.getRunescapeFont());
		List<String> lines = wrapText(g, objective, width - 32);
		int lineH = 15;
		int boxH = 22 + lines.size() * lineH + 10;
		g.setColor(new Color(0, 0, 0, 90));
		g.fillRoundRect(8, y, width - 16, boxH, 6, 6);
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(Color.WHITE);
		g.drawString(clip(g, title, width - 32), 15, y + 16);
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(HP_YELLOW);
		int ty = y + 34;
		for (String line : lines)
		{
			g.drawString(line, 15, ty);
			ty += lineH;
		}
		return y + boxH + 8;
	}

	/**
	 * The hunt's tally in the log: how many suspects are down, and who is still to be found. Every
	 * Challenge lives in the quest dialog frame, in front of the suspect, so this stays read-only.
	 */
	private int huntProgress(Graphics2D g, QuestDef quest, QuestDef.Chapter chapter, int y)
	{
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(HP_YELLOW);
		g.drawString("Suspects defeated: " + questManager.countHuntBeaten(quest, chapter)
			+ "/" + chapter.getBattlesRequired(), 12, y + 12);
		y += 18;
		for (String memberId : chapter.getBattlePool())
		{
			if (questManager.isHuntMemberBeaten(quest, memberId))
			{
				continue;
			}
			TrainerDef bt = db.trainer(memberId);
			g.setColor(MUTED);
			g.drawString(clip(g, "Seek out " + (bt != null ? bt.getName() : memberId), width - 24),
				12, y + 12);
			y += 16;
		}
		return y;
	}

	/**
	 * Rewards laid out like a compact collection log: an "Obtained" tally and lifetime battle count,
	 * then a grid of item slots (obtained bright, locked darkened). Clicking a slot prints the item's
	 * examine text to the chatbox, like a RuneScape item examine.
	 */
	private int itemsBody(Graphics2D g, List<Button> out)
	{
		int y = 26;
		if (!roster.isLoaded())
		{
			return loginHint(g, y);
		}
		Item[] all = Item.values();
		int obtained = 0;
		for (Item item : all)
		{
			if (roster.ownsItem(item))
			{
				obtained++;
			}
		}

		g.setFont(FontManager.getRunescapeFont());
		g.setColor(TEXT);
		g.drawString("Obtained: " + obtained + "/" + all.length, 10, y + 11);
		// Wallet (gold) on the first row, lifetime battle count (muted) tucked beneath it.
		String coins = "Coins: " + roster.getCoins();
		g.setColor(COIN);
		g.drawString(coins, width - 10 - g.getFontMetrics().stringWidth(coins), y + 11);
		g.setColor(MUTED);
		String battles = "Battles: " + roster.getTotalBattles();
		g.drawString(battles, width - 10 - g.getFontMetrics().stringWidth(battles), y + 24);
		y += 31;
		g.setColor(new Color(255, 255, 255, 24));
		g.fillRect(8, y, width - 16, 1);
		y += 9;

		int cols = 5;
		int slot = 34;
		int gap = 4;
		int gridW = cols * slot + (cols - 1) * gap;
		int startX = (width - gridW) / 2;
		Point hp = hoverPoint;
		for (int i = 0; i < all.length; i++)
		{
			Item item = all[i];
			Rectangle r = new Rectangle(startX + (i % cols) * (slot + gap), y + (i / cols) * (slot + gap), slot, slot);
			boolean owned = roster.ownsItem(item);
			g.setColor(new Color(0, 0, 0, 90));
			g.fillRoundRect(r.x, r.y, slot, slot, 5, 5);
			BufferedImage icon = itemIcon(item.getId());
			if (icon != null)
			{
				drawFit(g, icon, r.x + 4, r.y + 4, slot - 8, slot - 8);
			}
			if (!owned)
			{
				// Darken like an unobtained collection-log slot.
				g.setColor(new Color(12, 15, 17, 175));
				g.fillRoundRect(r.x, r.y, slot, slot, 5, 5);
			}
			boolean hover = hp != null && r.contains(hp);
			g.setColor(!owned ? BUTTON_DISABLED_EDGE : hover ? new Color(220, 200, 120) : BUTTON_EDGE);
			g.setStroke(new BasicStroke(hover ? 2 : 1));
			g.drawRoundRect(r.x, r.y, slot, slot, 5, 5);
			out.add(new Button(r, "item.examine:" + item.getId()));
		}
		int rows = (all.length + cols - 1) / cols;
		y += rows * (slot + gap) + 6;

		// Held items you own, and a tap-through to equip them on a team pet. This is the discoverable
		// home for anything bought in the Store: buy here, then equip here.
		y = heldItemsSection(g, out, y);
		y = cosmeticsSection(g, out, y);
		return y;
	}

	/** The owned cosmetics as equip rows (icon, name, slot, who wears them). */
	private int cosmeticsSection(Graphics2D g, List<Button> out, int y)
	{
		List<EquipItemDef> owned = roster.ownedCosmetics();
		if (owned.isEmpty())
		{
			return y;
		}
		g.setColor(new Color(255, 255, 255, 24));
		g.fillRect(8, y, width - 16, 1);
		y += 9;
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(new Color(200, 190, 160));
		g.drawString("Cosmetics", 10, y + 11);
		y += 18;
		for (EquipItemDef item : owned)
		{
			y = cosmeticItemRow(g, out, y, item);
		}
		return y;
	}

	private int cosmeticItemRow(Graphics2D g, List<Button> out, int y, EquipItemDef item)
	{
		int h = 32;
		Rectangle row = new Rectangle(8, y, width - 16, h);
		drawButtonBg(g, row, true);

		int icon = 24;
		BufferedImage img = item.getSprite() == null ? null : itemIcon(item.getSprite());
		if (img != null)
		{
			drawFit(g, img, row.x + 5, row.y + (h - icon) / 2, icon, icon);
		}
		int textX = row.x + 5 + icon + 6;

		List<String> wearers = new ArrayList<>();
		for (String speciesId : roster.getTeam())
		{
			PetInstance pet = roster.getPet(speciesId);
			SpeciesDef sp = db.species(speciesId);
			if (pet != null && sp != null && item.getId().equals(wornCosmetic(pet, item.getSlot())))
			{
				wearers.add(sp.getName());
			}
		}
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(Color.WHITE);
		g.drawString(clip(g, item.getName(), row.width - (textX - row.x) - 60), textX, y + 14);
		g.setColor(MUTED);
		String sub = wearers.isEmpty()
			? item.getSlot().name().toLowerCase(Locale.ROOT) + " slot"
			: "Worn by " + String.join(", ", wearers);
		g.drawString(clip(g, sub, row.width - (textX - row.x) - 60), textX, y + 27);

		g.setColor(new Color(220, 200, 120));
		g.setFont(FontManager.getRunescapeFont());
		String label = "Equip";
		g.drawString(label, row.x + row.width - g.getFontMetrics().stringWidth(label) - 8, y + 20);
		out.add(new Button(row, "equip.open:" + item.getId()));
		return y + h + 4;
	}

	/** Whether the content defines any cosmetic for this slot, i.e. whether the slot is live at all. */
	private boolean slotExistsInContent(EquipItemDef.Slot slot)
	{
		for (EquipItemDef item : db.allEquipItems())
		{
			if (item.getSlot() == slot)
			{
				return true;
			}
		}
		return false;
	}

	/** What a pet wears in an item's slot — the held id for HELD, the matching cosmetic otherwise. */
	private static String wornCosmetic(PetInstance pet, EquipItemDef.Slot slot)
	{
		switch (slot)
		{
			case HEAD:
				return pet.getHeadItemId();
			case FACE:
				return pet.getFaceItemId();
			default:
				return pet.getHeldItemId();
		}
	}

	/** The owned held-item inventory as equip rows (icon, name, effect, who wears it). */
	private int heldItemsSection(Graphics2D g, List<Button> out, int y)
	{
		g.setColor(new Color(255, 255, 255, 24));
		g.fillRect(8, y, width - 16, 1);
		y += 9;
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(new Color(200, 190, 160));
		g.drawString("Held items", 10, y + 11);
		y += 18;

		// Held items in stock plus any currently worn — a fully-equipped item keeps its row so it can
		// still be unequipped from here (returning the unit to stock).
		LinkedHashMap<String, EquipItemDef> held = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> e : roster.getItemInventory().entrySet())
		{
			EquipItemDef item = db.equipItem(e.getKey());
			if (item != null && item.getSlot() == EquipItemDef.Slot.HELD && e.getValue() > 0)
			{
				held.put(item.getId(), item);
			}
		}
		for (String speciesId : roster.getTeam())
		{
			PetInstance pet = roster.getPet(speciesId);
			if (pet != null && pet.getHeldItemId() != null)
			{
				EquipItemDef item = db.equipItem(pet.getHeldItemId());
				if (item != null && item.getSlot() == EquipItemDef.Slot.HELD)
				{
					held.putIfAbsent(item.getId(), item);
				}
			}
		}
		if (held.isEmpty())
		{
			hint(g, "None yet - buy one in the Store.", y);
			return y + 16;
		}
		for (EquipItemDef item : held.values())
		{
			y = heldItemRow(g, out, y, item);
		}
		return y;
	}

	private int heldItemRow(Graphics2D g, List<Button> out, int y, EquipItemDef item)
	{
		int h = 32;
		Rectangle row = new Rectangle(8, y, width - 16, h);
		drawButtonBg(g, row, true);

		int icon = 24;
		BufferedImage img = item.getSprite() == null ? null : itemIcon(item.getSprite());
		if (img != null)
		{
			drawFit(g, img, row.x + 5, row.y + (h - icon) / 2, icon, icon);
		}
		int textX = row.x + 5 + icon + 6;

		// Who's wearing it right now, so the player can see it's in use without opening the chooser.
		List<String> wearers = new ArrayList<>();
		for (String speciesId : roster.getTeam())
		{
			PetInstance pet = roster.getPet(speciesId);
			SpeciesDef sp = db.species(speciesId);
			if (pet != null && sp != null && item.getId().equals(pet.getHeldItemId()))
			{
				wearers.add(sp.getName());
			}
		}
		int spare = roster.itemCount(item.getId());
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(Color.WHITE);
		String name = item.getName() + (spare > 0 ? "  x" + spare : "");
		g.drawString(clip(g, name, row.width - (textX - row.x) - 60), textX, y + 14);
		g.setColor(MUTED);
		String sub = wearers.isEmpty() ? effectText(item) : "Worn by " + String.join(", ", wearers);
		g.drawString(clip(g, sub, row.width - (textX - row.x) - 60), textX, y + 27);

		// The row opens the equip chooser to equip a spare or unequip a worn one.
		g.setColor(new Color(220, 200, 120));
		g.setFont(FontManager.getRunescapeFont());
		String label = spare > 0 ? "Equip" : "Manage";
		g.drawString(label, row.x + row.width - g.getFontMetrics().stringWidth(label) - 8, y + 20);
		out.add(new Button(row, "equip.open:" + item.getId()));
		return y + h + 4;
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

	/** The bundled clan-motif menu icon for a nav action, or null (cached) if none is present. */
	private BufferedImage menuIcon(String action)
	{
		String name = action.startsWith("open:") ? action.substring(5) : action;
		if (menuIconCache.containsKey(name))
		{
			return menuIconCache.get(name);
		}
		BufferedImage img = null;
		String path = "/com/petbattles/icons/menu/" + name + ".png";
		if (getClass().getResource(path) != null)
		{
			img = ImageUtil.loadImageResource(getClass(), path);
		}
		menuIconCache.put(name, img);
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
		int fw = (width - 16 - 8) / 5;
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
			iconButton(g, out, new Rectangle(width - 42, y, 14, 14), Icon.UP, "trainers.page:-1", trainersOffset > 0);
			iconButton(g, out, new Rectangle(width - 24, y, 14, 14), Icon.DOWN, "trainers.page:1", trainersOffset < maxOffset);
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
		Rectangle box = new Rectangle(8, y, width - 16, h);
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
		int cardW = width - 16;
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
		// In person when near; remotely only once the device has calibrated against them (beaten
		// in person). So a first fight always needs proximity.
		boolean unlocked = nearTrainers.get().contains(trainer.getId())
			|| roster.canRemoteFight(trainer.getId());
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

	// --- Store pane ---------------------------------------------------------

	/**
	 * The coin sink: a wallet header and the currently-sold {@link EquipItemDef}s as buy rows (name,
	 * effect/kind, price). Buying spends coins and grants the item; unaffordable rows disable. Mirrors
	 * the old Swing store modal, now reachable identically from the overlay and the drawer.
	 */
	private int storeBody(Graphics2D g, List<Button> out)
	{
		int y = 26;
		if (!roster.isLoaded())
		{
			return loginHint(g, y);
		}
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(COIN);
		g.drawString("Coins: " + roster.getCoins(), 10, y + 11);
		y += 18;
		g.setColor(new Color(255, 255, 255, 24));
		g.fillRect(8, y, width - 16, 1);
		y += 9;

		List<EquipItemDef> sold = new ArrayList<>();
		for (EquipItemDef item : db.allEquipItems())
		{
			if (item.isSold())
			{
				sold.add(item);
			}
		}
		if (sold.isEmpty())
		{
			hint(g, "Nothing for sale right now.", y);
			return y + 16;
		}
		int visible = 4;
		int maxOffset = Math.max(0, sold.size() - visible);
		if (storeOffset > maxOffset)
		{
			storeOffset = maxOffset;
		}
		int end = Math.min(sold.size(), storeOffset + visible);
		if (sold.size() > visible)
		{
			g.setColor(MUTED);
			g.drawString((storeOffset + 1) + "-" + end + " of " + sold.size(), 10, y + 11);
			iconButton(g, out, new Rectangle(width - 42, y, 14, 14), Icon.UP, "store.page:-1", storeOffset > 0);
			iconButton(g, out, new Rectangle(width - 24, y, 14, 14), Icon.DOWN, "store.page:1", storeOffset < maxOffset);
			y += 20;
		}
		for (int i = storeOffset; i < end; i++)
		{
			y = storeRow(g, out, y, sold.get(i));
		}
		return y;
	}

	private int storeRow(Graphics2D g, List<Button> out, int y, EquipItemDef item)
	{
		int h = 40;
		Rectangle row = new Rectangle(8, y, width - 16, h);
		g.setColor(new Color(0, 0, 0, 90));
		g.fillRoundRect(row.x, row.y, row.width, row.height, 6, 6);

		int icon = 26;
		BufferedImage img = item.getSprite() == null ? null : itemIcon(item.getSprite());
		if (img != null)
		{
			drawFit(g, img, row.x + 6, row.y + (h - icon) / 2, icon, icon);
		}

		int have = roster.itemCount(item.getId());
		boolean afford = roster.getCoins() >= item.getPrice();
		int priceW = 52;
		int textX = row.x + 6 + icon + 8;
		int textRoom = row.width - priceW - 10 - (textX - row.x);
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(Color.WHITE);
		g.drawString(clip(g, item.getName() + (have > 0 ? " x" + have : ""), textRoom), textX, y + 16);
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(MUTED);
		String kind = item.isCosmetic()
			? item.getSlot().name().toLowerCase(Locale.ROOT) + " cosmetic"
			: effectText(item);
		g.drawString(clip(g, kind, textRoom), textX, y + 31);

		Rectangle buy = new Rectangle(row.x + row.width - priceW - 6, y + (h - 18) / 2, priceW, 18);
		drawButton(g, buy, item.getPrice() + " gp", afford, false);
		if (afford)
		{
			out.add(new Button(buy, "store.buy:" + item.getId()));
		}
		Point hp = hoverPoint;
		if (hp != null && buy.contains(hp) && !afford)
		{
			hoverTooltip = "Not enough coins";
		}
		return y + h + 6;
	}

	// --- Pet detail pane ----------------------------------------------------

	/**
	 * One pet's detail: portrait, level and types, an XP bar, a team Join/Leave button, its held-item
	 * chooser and its move loadout — the old Swing move / held-item modals as an inline pane, reached
	 * by tapping a Team slot.
	 */
	private int petBody(Graphics2D g, List<Button> out)
	{
		int y = 26;
		if (!roster.isLoaded())
		{
			return loginHint(g, y);
		}
		SpeciesDef species = petSpecies == null ? null : db.species(petSpecies);
		if (species == null)
		{
			hint(g, "No pet selected.", y);
			return y + 16;
		}
		if (!roster.isOwned(species.getId()))
		{
			hint(g, "Not in your collection log yet.", y);
			y += 18;
			if (roster.isDevSelectEnabled())
			{
				y = fullButton(g, out, y, "Unlock (dev)", "dev.unlock:" + species.getId(), true);
			}
			return y;
		}
		PetInstance pet = roster.getOrCreatePet(species.getId());
		int level = pet.getLevel();

		// Header: portrait, name/level, type badges.
		int pw = 40;
		Rectangle portrait = new Rectangle(10, y, pw, pw);
		g.setColor(new Color(255, 255, 255, 18));
		g.fillRoundRect(portrait.x, portrait.y, pw, pw, 6, 6);
		drawFit(g, sprites.itemImage(species.itemIdFor(pet.getActiveVariantId(), level)),
			portrait.x + 2, portrait.y + 2, pw - 4, pw - 4);
		int textX = portrait.x + pw + 10;
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(Color.WHITE);
		g.drawString(clip(g, species.nameFor(pet.getActiveVariantId(), level), width - textX - 10), textX, y + 14);
		int bx = textX;
		int by = y + 22;
		for (PetType type : species.typesFor(pet.getActiveVariantId()))
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			String t = type.getDisplayName();
			int tw = g.getFontMetrics().stringWidth(t) + 8;
			g.setColor(new Color(type.getColorRgb()));
			g.fillRoundRect(bx, by, tw, 14, 4, 4);
			g.setColor(Color.WHITE);
			g.drawString(t, bx + 4, by + 11);
			bx += tw + 4;
		}
		y += pw + 6;

		// XP bar.
		y = xpBar(g, y, pet, level);

		// Team membership toggle.
		boolean onTeam = roster.getTeam().contains(species.getId());
		boolean canEdit = roster.canEditTeam();
		boolean teamFull = roster.getTeam().size() >= RosterManager.MAX_TEAM_SIZE;
		if (onTeam)
		{
			y = fullButton(g, out, y, canEdit ? "Remove from team" : "On team (visit a bank to change)",
				"team.remove:" + species.getId(), canEdit);
		}
		else
		{
			boolean canJoin = canEdit && !teamFull;
			String label = !canEdit ? "Join team (visit a bank)" : teamFull ? "Team is full" : "Join team";
			y = fullButton(g, out, y, label, "team.add:" + species.getId(), canJoin);
		}
		y += 4;

		// Held item chooser.
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(new Color(200, 190, 160));
		g.drawString("Held item", 10, y + 11);
		y += 18;
		String heldId = pet.getHeldItemId();
		y = selectRow(g, out, y, "None", heldId == null, "pet.held.clear:" + species.getId(), true);
		// Spare held items to choose from, plus whatever this pet already wears (so it stays selectable
		// even with no spares left).
		LinkedHashMap<String, EquipItemDef> heldOwned = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> e : roster.getItemInventory().entrySet())
		{
			EquipItemDef item = db.equipItem(e.getKey());
			if (item != null && item.getSlot() == EquipItemDef.Slot.HELD && e.getValue() > 0)
			{
				heldOwned.put(item.getId(), item);
			}
		}
		if (heldId != null)
		{
			EquipItemDef worn = db.equipItem(heldId);
			if (worn != null && worn.getSlot() == EquipItemDef.Slot.HELD)
			{
				heldOwned.putIfAbsent(worn.getId(), worn);
			}
		}
		if (heldOwned.isEmpty())
		{
			hint(g, "No held items yet - buy one in the Store.", y);
			y += 16;
		}
		for (EquipItemDef item : heldOwned.values())
		{
			y = selectRow(g, out, y, item.getName() + "  [" + effectText(item) + "]",
				item.getId().equals(heldId), "pet.held:" + species.getId() + ":" + item.getId(), true);
		}
		y += 6;

		// Cosmetic choosers. Unlike held items these aren't stock-limited, so every owned cosmetic is
		// always offered — the same hat can sit on all three team pets at once.
		y = cosmeticChooser(g, out, y, species, pet, EquipItemDef.Slot.HEAD, "Head");
		y = cosmeticChooser(g, out, y, species, pet, EquipItemDef.Slot.FACE, "Face");

		// Move loadout.
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(new Color(200, 190, 160));
		List<String> equipped = pet.getEquippedMoves();
		g.drawString("Moves (" + equipped.size() + "/" + PetInstance.MAX_EQUIPPED_MOVES + ")", 10, y + 11);
		y += 18;
		for (String moveId : pet.availableMoves(species))
		{
			MoveDef move = db.move(moveId);
			if (move == null)
			{
				continue;
			}
			String label = move.getName() + "  [" + move.getType().getDisplayName()
				+ (move.getPower() > 0 ? ", " + move.getPower() + " pow" : ", status") + "]";
			y = selectRow(g, out, y, label, equipped.contains(moveId),
				"pet.move:" + species.getId() + ":" + moveId, true);
		}

		// Dev lock toggle.
		if (roster.isDevSelectEnabled() && roster.isDevUnlocked(species.getId()))
		{
			y += 6;
			y = fullButton(g, out, y, "Lock (dev)", "dev.lock:" + species.getId(), true);
		}
		return y;
	}

	/**
	 * One cosmetic slot's chooser on the pet detail page: a "None" row plus every owned cosmetic that
	 * fits this slot. Renders nothing but a hint when the player owns none for the slot yet.
	 */
	private int cosmeticChooser(Graphics2D g, List<Button> out, int y, SpeciesDef species,
		PetInstance pet, EquipItemDef.Slot slot, String label)
	{
		// A slot with nothing in the content at all gets no section: "none yet" would be a promise
		// the game can't keep. A slot that exists but is unearned still shows, as a nudge.
		if (!slotExistsInContent(slot))
		{
			return y;
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(new Color(200, 190, 160));
		g.drawString(label, 10, y + 11);
		y += 18;

		String wornId = slot == EquipItemDef.Slot.HEAD ? pet.getHeadItemId() : pet.getFaceItemId();
		List<EquipItemDef> fits = new ArrayList<>();
		for (EquipItemDef item : roster.ownedCosmetics())
		{
			if (item.getSlot() == slot)
			{
				fits.add(item);
			}
		}
		if (fits.isEmpty())
		{
			hint(g, "No " + label.toLowerCase(Locale.ROOT) + " cosmetics yet.", y);
			return y + 22;
		}
		y = selectRow(g, out, y, "None", wornId == null,
			"pet.cosmetic.clear:" + species.getId() + ":" + slot.name(), true);
		for (EquipItemDef item : fits)
		{
			y = selectRow(g, out, y, item.getName(), item.getId().equals(wornId),
				"pet.cosmetic:" + species.getId() + ":" + item.getId(), true);
		}
		return y + 6;
	}

	/** A short XP progress bar with the "x / y xp" label (or "Max level"). */
	private int xpBar(Graphics2D g, int y, PetInstance pet, int level)
	{
		Rectangle bar = new Rectangle(10, y, width - 20, 14);
		g.setColor(new Color(0, 0, 0, 120));
		g.fillRoundRect(bar.x, bar.y, bar.width, bar.height, 4, 4);
		String label;
		double frac;
		if (level >= Leveling.MAX_LEVEL)
		{
			frac = 1;
			label = "Max level";
		}
		else
		{
			long floor = Leveling.xpForLevel(level);
			long ceil = Leveling.xpForLevel(level + 1);
			long into = pet.getXp() - floor;
			long span = ceil - floor;
			frac = span <= 0 ? 0 : Math.max(0, Math.min(1, into / (double) span));
			label = into + " / " + span + " xp";
		}
		g.setColor(new Color(80, 160, 90));
		g.fillRoundRect(bar.x, bar.y, Math.max(0, (int) Math.round(bar.width * frac)), bar.height, 4, 4);
		g.setColor(BUTTON_EDGE);
		g.setStroke(new BasicStroke(1));
		g.drawRoundRect(bar.x, bar.y, bar.width, bar.height, 4, 4);
		g.setFont(FontManager.getRunescapeSmallFont());
		g.setColor(TEXT);
		FontMetrics fm = g.getFontMetrics();
		g.drawString(label, bar.x + (bar.width - fm.stringWidth(label)) / 2, bar.y + 11);
		return y + 20;
	}

	/** A full-width toggle/choice row with a check dot on the left when selected. */
	private int selectRow(Graphics2D g, List<Button> out, int y, String label, boolean selected,
		String action, boolean enabled)
	{
		Rectangle r = new Rectangle(8, y, width - 16, 22);
		drawButtonBg(g, r, enabled);
		if (selected)
		{
			g.setColor(new Color(220, 200, 120));
			g.setStroke(new BasicStroke(2));
			g.drawRoundRect(r.x, r.y, r.width, r.height, 6, 6);
		}
		int dot = 10;
		int dy = r.y + (r.height - dot) / 2;
		g.setColor(selected ? new Color(120, 200, 110) : new Color(0, 0, 0, 120));
		g.fillOval(r.x + 6, dy, dot, dot);
		g.setColor(BUTTON_EDGE);
		g.drawOval(r.x + 6, dy, dot, dot);
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(enabled ? Color.WHITE : MUTED);
		FontMetrics fm = g.getFontMetrics();
		g.drawString(clip(g, label, r.width - 28), r.x + 22, r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
		if (enabled)
		{
			out.add(new Button(r, action));
		}
		return y + 26;
	}

	// --- Equip pane (held item -> team pet) ---------------------------------

	/**
	 * Assign a held item to a team pet: an item header, then a tappable list of the team where each
	 * row equips (or, if already worn, removes) the item. Reached from the Items pane's held-item
	 * list, so a freshly-bought item can be put to use straight away.
	 */
	private int equipBody(Graphics2D g, List<Button> out)
	{
		int y = 26;
		if (!roster.isLoaded())
		{
			return loginHint(g, y);
		}
		EquipItemDef item = equipItemId == null ? null : db.equipItem(equipItemId);
		if (item == null)
		{
			hint(g, "No item selected.", y);
			return y + 16;
		}

		int pw = 34;
		Rectangle box = new Rectangle(10, y, pw, pw);
		g.setColor(new Color(255, 255, 255, 18));
		g.fillRoundRect(box.x, box.y, pw, pw, 6, 6);
		BufferedImage img = item.getSprite() == null ? null : itemIcon(item.getSprite());
		if (img != null)
		{
			drawFit(g, img, box.x + 2, box.y + 2, pw - 4, pw - 4);
		}
		int textX = box.x + pw + 10;
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(Color.WHITE);
		g.drawString(clip(g, item.getName(), width - textX - 10), textX, y + 14);
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(MUTED);
		g.drawString(clip(g, effectText(item), width - textX - 10), textX, y + 30);
		y += pw + 8;

		List<String> team = roster.getTeam();
		if (team.isEmpty())
		{
			hint(g, "Add a pet to your team first.", y);
			return y + 16;
		}
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(TEXT);
		g.drawString("Tap a team pet to equip:", 10, y + 11);
		y += 18;
		for (String speciesId : team)
		{
			y = equipPetRow(g, out, y, speciesId, item);
		}
		return y;
	}

	private int equipPetRow(Graphics2D g, List<Button> out, int y, String speciesId, EquipItemDef item)
	{
		SpeciesDef species = db.species(speciesId);
		if (species == null)
		{
			return y;
		}
		PetInstance pet = roster.getPet(speciesId);
		boolean cosmetic = item.isCosmetic();
		boolean wearing = pet != null && item.getId().equals(wornCosmetic(pet, item.getSlot()));
		// A pet can only hold an item if you actually own it. A team can carry a dev-unlocked pet with
		// dev-select later switched off; such a pet can't be equipped, so the row goes inert.
		boolean owned = roster.isOwned(speciesId);
		int h = 30;
		Rectangle row = new Rectangle(8, y, width - 16, h);
		drawButtonBg(g, row, owned);
		if (wearing)
		{
			g.setColor(new Color(220, 200, 120));
			g.setStroke(new BasicStroke(2));
			g.drawRoundRect(row.x, row.y, row.width, row.height, 6, 6);
		}

		int level = pet != null ? pet.getLevel() : 1;
		int iconId = pet != null ? species.itemIdFor(pet.getActiveVariantId(), level) : species.getItemId();
		drawFit(g, sprites.itemImage(iconId), row.x + 4, row.y + 3, 24, 24);

		int actionW = 54;
		int textX = row.x + 34;
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(owned ? Color.WHITE : MUTED);
		g.drawString(clip(g, species.getName(), row.width - (textX - row.x) - actionW), textX, y + 13);
		// What already occupies the slot this item would go into, so the swap is visible up front.
		String occupantId = pet == null ? null : wornCosmetic(pet, item.getSlot());
		EquipItemDef occupant = occupantId == null ? null : db.equipItem(occupantId);
		String cur = occupant == null ? null : occupant.getName();
		g.setColor(MUTED);
		String sub = !owned ? "Not unlocked" : wearing ? "Equipped"
			: cur != null ? (cosmetic ? "Wears " + cur : "Holds " + cur) : "Lv " + level;
		g.drawString(clip(g, sub, row.width - (textX - row.x) - actionW), textX, y + 26);

		if (owned && wearing)
		{
			g.setColor(new Color(200, 90, 80));
			g.drawString("Remove", row.x + row.width - g.getFontMetrics().stringWidth("Remove") - 8, y + 20);
			out.add(new Button(row, cosmetic
				? "pet.cosmetic.clear:" + speciesId + ":" + item.getSlot().name()
				: "pet.held.clear:" + speciesId));
		}
		// Cosmetics are a wardrobe, not stock, so they're always equippable; held items need a spare.
		else if (owned && (cosmetic || roster.itemCount(item.getId()) > 0))
		{
			g.setColor(new Color(220, 200, 120));
			g.drawString("Equip", row.x + row.width - g.getFontMetrics().stringWidth("Equip") - 8, y + 20);
			out.add(new Button(row, (cosmetic ? "pet.cosmetic:" : "pet.held:")
				+ speciesId + ":" + item.getId()));
		}
		else if (owned)
		{
			// Owned pet, but every unit of this item is already equipped elsewhere.
			g.setColor(MUTED);
			g.drawString("None left", row.x + row.width - g.getFontMetrics().stringWidth("None left") - 8, y + 20);
		}
		return y + h + 4;
	}

	// --- Dev tools pane -----------------------------------------------------

	private int devBody(Graphics2D g, List<Button> out)
	{
		int y = 26;
		if (!roster.isDevSelectEnabled())
		{
			hint(g, "Dev tools are disabled.", y);
			return y + 16;
		}
		y = fullButton(g, out, y, "Unlock pets", "open:devpets", true);
		y += 8;
		y = devAction(g, out, y, "progression", "Reset progression",
			"Reset ALL pets to level 1? Wipes every pet's XP, level and moveset (owned pets and team kept).");
		y += 4;
		y = devAction(g, out, y, "quests", "Reset quests",
			"Reset ALL quest progress and relock their rewards (e.g. remote battles)?");
		return y;
	}

	/**
	 * Dev-only pet unlocker: a name search over every species with an Unlock / Lock toggle, so a
	 * developer can build teams from pets they don't own. Reached from the Dev tools pane; the whole
	 * pane is inert without dev select, so it never appears in a production build.
	 */
	private int devPetsBody(Graphics2D g, List<Button> out)
	{
		int y = 26;
		if (!roster.isDevSelectEnabled())
		{
			hint(g, "Dev tools are disabled.", y);
			return y + 16;
		}
		y = searchBox(g, out, y);

		String query = trainerSearch.trim().toLowerCase(Locale.ROOT);
		List<SpeciesDef> list = new ArrayList<>();
		for (SpeciesDef species : db.allSpecies())
		{
			if (query.isEmpty() || species.getName().toLowerCase(Locale.ROOT).contains(query))
			{
				list.add(species);
			}
		}
		if (list.isEmpty())
		{
			hint(g, "No pets match \"" + trainerSearch + "\".", y);
			return y + 16;
		}
		int visible = 6;
		int maxOffset = Math.max(0, list.size() - visible);
		if (devPetsOffset > maxOffset)
		{
			devPetsOffset = maxOffset;
		}
		int end = Math.min(list.size(), devPetsOffset + visible);
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(MUTED);
		g.drawString((devPetsOffset + 1) + "-" + end + " of " + list.size(), 10, y + 11);
		if (list.size() > visible)
		{
			iconButton(g, out, new Rectangle(width - 42, y, 14, 14), Icon.UP, "devpets.page:-1", devPetsOffset > 0);
			iconButton(g, out, new Rectangle(width - 24, y, 14, 14), Icon.DOWN, "devpets.page:1", devPetsOffset < maxOffset);
		}
		y += 20;
		for (int i = devPetsOffset; i < end; i++)
		{
			y = devPetRow(g, out, y, list.get(i));
		}
		return y;
	}

	private int devPetRow(Graphics2D g, List<Button> out, int y, SpeciesDef species)
	{
		boolean devUnlocked = roster.isDevUnlocked(species.getId());
		boolean owned = roster.isOwned(species.getId());
		int h = 26;
		Rectangle row = new Rectangle(8, y, width - 16, h);
		drawButtonBg(g, row, true);
		drawFit(g, sprites.itemImage(species.getItemId()), row.x + 4, row.y + 3, 20, 20);

		int btnW = 52;
		int textX = row.x + 30;
		g.setFont(FontManager.getRunescapeFont());
		g.setColor(owned ? Color.WHITE : MUTED);
		g.drawString(clip(g, species.getName(), row.width - (textX - row.x) - btnW - 8), textX, y + 17);

		Rectangle btn = new Rectangle(row.x + row.width - btnW - 6, y + (h - 18) / 2, btnW, 18);
		if (devUnlocked)
		{
			drawButton(g, btn, "Lock", true, false);
			out.add(new Button(btn, "dev.lock:" + species.getId()));
		}
		else if (owned)
		{
			g.setColor(new Color(120, 200, 110));
			g.drawString("Owned", btn.x + 6, y + 17);
		}
		else
		{
			drawButton(g, btn, "Unlock", true, false);
			out.add(new Button(btn, "dev.unlock:" + species.getId()));
		}
		return y + h + 4;
	}

	/** A destructive dev button that arms an inline Yes/No confirm on first click. */
	private int devAction(Graphics2D g, List<Button> out, int y, String key, String label, String prompt)
	{
		if (key.equals(pendingConfirm))
		{
			g.setFont(FontManager.getRunescapeFont());
			g.setColor(TEXT);
			List<String> lines = wrapText(g, prompt, width - 20);
			for (String line : lines)
			{
				g.drawString(line, 10, y + 11);
				y += 14;
			}
			y += 2;
			int gap = 6;
			int bw = (width - 16 - gap) / 2;
			Rectangle yes = new Rectangle(8, y, bw, 20);
			drawButton(g, yes, "Yes, reset", true, false);
			out.add(new Button(yes, "dev.reset:" + key));
			Rectangle no = new Rectangle(8 + bw + gap, y, width - 16 - bw - gap, 20);
			drawButton(g, no, "Cancel", true, false);
			out.add(new Button(no, "dev.cancel"));
			return y + 24;
		}
		return fullButton(g, out, y, label, "dev.confirm:" + key, true);
	}

	/** Arm / clear / read the pending dev confirm (driven from {@link HubActions}). */
	public void armConfirm(String key)
	{
		pendingConfirm = key;
	}

	public void clearConfirm()
	{
		pendingConfirm = null;
	}

	/** Short description of a held item's stat effect, e.g. "+15% SPD" (or a cosmetic note). */
	private static String effectText(EquipItemDef item)
	{
		ItemEffect effect = item.getEffect();
		if (effect == null)
		{
			return item.isCosmetic() ? "cosmetic" : "no effect";
		}
		String sign = effect.getMagnitude() >= 0 ? "+" : "";
		return sign + effect.getMagnitude() + "% " + effect.getStat();
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
		g.fillRoundRect(0, 0, width, height, 10, 10);
		g.setColor(PANEL_EDGE);
		g.setStroke(new BasicStroke(2));
		g.drawRoundRect(0, 0, width, height, 10, 10);
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(new Color(200, 190, 160));
		g.drawString(clip(g, title, width - 78), 10, 15);

		// The docked panel can't collapse to a chip, so it has no close cross — only the Menu button.
		int bx = width - 8 - 16;
		if (!docked)
		{
			iconButton(g, out, new Rectangle(bx, 3, 16, 14), Icon.CLOSE, "collapse", true);
		}
		if (showMenu)
		{
			if (!docked)
			{
				bx -= 18;
			}
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
		g.drawString(clip(g, text, width - 20), 10, y + 4);
	}

	private int fullButton(Graphics2D g, List<Button> out, int y, String label, String action, boolean enabled)
	{
		Rectangle r = new Rectangle(8, y, width - 16, 22);
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
