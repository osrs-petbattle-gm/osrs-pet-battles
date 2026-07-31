package com.petbattles.battle;

import com.petbattles.PetBattlesConfig;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.BattleAction;
import com.petbattles.engine.BattleEngine;
import com.petbattles.engine.BattleEvent;
import com.petbattles.engine.BattlePet;
import com.petbattles.engine.BattleState;
import com.petbattles.engine.LearnsetEntry;
import com.petbattles.engine.Leveling;
import com.petbattles.engine.MoveDef;
import com.petbattles.engine.PetInstance;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.engine.TrainerDef;
import com.petbattles.engine.TypeChart;
import com.petbattles.engine.controller.AiController;
import com.petbattles.engine.controller.OpponentController;
import com.petbattles.persist.RosterManager;
import com.petbattles.quest.Quest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;

/**
 * Client-side battle orchestration: builds teams, paces engine events out on game
 * ticks for the overlay, collects player input, and awards XP at the end.
 * All methods are called on the client thread (game ticks and consumed mouse input).
 */
@Slf4j
public class BattleSession
{
	public enum Phase
	{
		IDLE,
		ANIMATING,
		AWAITING_INPUT,
		// Player's active pet fainted mid-turn; they must pick a replacement to continue
		FORCED_SWITCH,
		// The enemy just sent in a replacement after a KO; the player is offered one optional,
		// cost-free swap before their next turn (declining keeps the current pet).
		FREE_SWITCH,
		// A pet levelled into a new move with a full moveset; player must forget one or skip
		LEARN_MOVE,
		ENDED
	}

	// The DAMAGE/HEALED HP bar drains over this window of the event's 0->1 progress, so the
	// hit-splat pops first (0 -> HP_DRAIN_START) and the bar then animates to the new value.
	private static final float HP_DRAIN_START = 0.15f;
	private static final float HP_DRAIN_END = 0.55f;

	/**
	 * One row of the post-battle summary: a pet that fought, its ending state and rewards.
	 */
	public static final class SummaryEntry
	{
		private final String name;
		private final int displayItemId;
		private final boolean fought;
		private final int level;
		private final int currentHp;
		private final int maxHp;
		private final boolean fainted;
		private final long xpGained;
		private final int levelsGained;
		private final List<String> learnedMoves;

		SummaryEntry(String name, int displayItemId, boolean fought, int level, int currentHp, int maxHp,
			boolean fainted, long xpGained, int levelsGained, List<String> learnedMoves)
		{
			this.name = name;
			this.displayItemId = displayItemId;
			this.fought = fought;
			this.level = level;
			this.currentHp = currentHp;
			this.maxHp = maxHp;
			this.fainted = fainted;
			this.xpGained = xpGained;
			this.levelsGained = levelsGained;
			this.learnedMoves = learnedMoves;
		}

		public String getName()
		{
			return name;
		}

		/** Item id of the pet's icon at its current growth stage, for the summary slot. */
		public int getDisplayItemId()
		{
			return displayItemId;
		}

		/** Whether this pet actually took the field (and so earned XP), vs. sat benched. */
		public boolean isFought()
		{
			return fought;
		}

		public int getLevel()
		{
			return level;
		}

		public int getCurrentHp()
		{
			return currentHp;
		}

		public int getMaxHp()
		{
			return maxHp;
		}

		public boolean isFainted()
		{
			return fainted;
		}

		public long getXpGained()
		{
			return xpGained;
		}

		public int getLevelsGained()
		{
			return levelsGained;
		}

		public List<String> getLearnedMoves()
		{
			return learnedMoves;
		}
	}

	private final Client client;
	private final PetDatabase db;
	private final RosterManager roster;
	private final PetBattlesConfig config;
	private final BattleEngine engine;
	private final BattleSoundManager sound;
	private final Runnable onRosterChanged;

	/** Trainer whose defeat completes {@link Quest#WHERES_THE_REMOTE} and unlocks remote battles. */
	private static final String TRAINER_PROFESSOR = "professor_oddenstein";

	private Phase phase = Phase.IDLE;
	private BattleState state;
	private OpponentController enemyController;
	private TrainerDef trainer;
	private Random rng;
	private final Deque<BattleEvent> pendingEvents = new ArrayDeque<>();
	// Per-pet results for the end-of-battle summary screen, built when the battle ends.
	private final List<SummaryEntry> summary = new ArrayList<>();
	// XP/levels/moves accrued per participating pet across the battle (keyed by species id).
	private final Map<String, Progress> progress = new HashMap<>();
	// Full-moveset learns awaiting the player's forget-or-skip choice, oldest first.
	private final Deque<PendingLearn> pendingLearns = new ArrayDeque<>();
	private int tickCounter;
	private boolean finalized;
	// Set true while an enemy replacement is surfaced after a KO; if the player still has a
	// benched pet to switch to, the queue drains into FREE_SWITCH to offer one cost-free swap.
	private boolean freeSwitchOffer;
	// A one-off NPC dialog to show on the end screen (e.g. Professor Oddenstein's quest reward):
	// the speaker's trainer id (for the chathead) and the speech text; null = no dialog pending.
	private String questDialogTrainerId;
	private String questDialogText;
	private BattleEvent currentEvent;
	private MoveDef currentMove;
	private long eventStartMs;

	// --- presentation state: what the overlay draws, lagged behind the resolved model ---
	// Displayed HP per pet (by identity): the value currently at rest on screen. Damage/heal
	// events animate this toward the model value while their line is shown (see displayHp).
	private final Map<BattlePet, Float> shownHp = new IdentityHashMap<>();
	// Pets whose FAINTED line has already been surfaced, so the overlay may settle them to a
	// faint ghost. A pet is NOT settled here until its faint line plays — that keeps the
	// collapse from firing during the attacker's earlier MOVE_USED/DAMAGE lines.
	private final Set<BattlePet> faintShown = Collections.newSetFromMap(new IdentityHashMap<>());
	// The pet whose HP the current event is animating (or null), with its from/to endpoints.
	private BattlePet hpAnimPet;
	private float hpAnimFrom;
	private float hpAnimTo;
	// Player pets that have taken the field against the CURRENT enemy — the KO's XP is split
	// evenly among them (shared experience). Reset each time a new enemy is sent out.
	// LinkedHashSet gives identity semantics (BattlePet has no equals override) + stable order.
	private final Set<BattlePet> enemyParticipants = new LinkedHashSet<>();

	public BattleSession(Client client, PetDatabase db, RosterManager roster, PetBattlesConfig config,
		Runnable onRosterChanged)
	{
		this.client = client;
		this.db = db;
		this.roster = roster;
		this.config = config;
		this.engine = new BattleEngine(db.getTypeChart());
		this.sound = new BattleSoundManager(client, config);
		this.onRosterChanged = onRosterChanged;
	}

	/**
	 * Start a battle against the given trainer using the player's current team.
	 * Returns false (with no state change) if a battle is running or the team is empty.
	 */
	public boolean startTrainerBattle(String trainerId)
	{
		if (phase != Phase.IDLE && phase != Phase.ENDED)
		{
			return false;
		}
		TrainerDef def = db.trainer(trainerId);
		if (def == null || roster.getTeam().isEmpty())
		{
			return false;
		}

		List<BattlePet> playerTeam = new ArrayList<>();
		for (String speciesId : roster.getTeam())
		{
			BattlePet pet = buildPlayerPet(speciesId);
			if (pet != null)
			{
				playerTeam.add(pet);
			}
		}
		if (playerTeam.isEmpty())
		{
			return false;
		}

		List<BattlePet> enemyTeam = new ArrayList<>();
		for (TrainerDef.PartyEntry entry : def.getParty())
		{
			SpeciesDef species = db.species(entry.getSpecies());
			enemyTeam.add(new BattlePet(species, species.nameAt(entry.getLevel()), entry.getLevel(),
				enemyMoves(species, entry.getLevel())));
		}

		trainer = def;
		enemyController = new AiController(def.getDifficulty(), db.getTypeChart());
		rng = new Random();
		pendingEvents.clear();
		summary.clear();
		pendingLearns.clear();
		progress.clear();
		shownHp.clear();
		faintShown.clear();
		enemyParticipants.clear();
		hpAnimPet = null;
		// Seed each pet's displayed HP with its starting HP so the first hit animates from full.
		for (BattlePet bp : playerTeam)
		{
			shownHp.put(bp, (float) bp.getCurrentHp());
		}
		for (BattlePet bp : enemyTeam)
		{
			shownHp.put(bp, (float) bp.getCurrentHp());
		}
		// Snapshot starting levels so the summary can show levels gained across the fight.
		for (BattlePet bp : playerTeam)
		{
			PetInstance inst = roster.getPet(bp.getSpecies().getId());
			progress.put(bp.getSpecies().getId(), new Progress(inst != null ? inst.getLevel() : bp.getLevel()));
		}
		finalized = false;
		tickCounter = 0;

		List<BattleEvent> events = new ArrayList<>();
		events.add(BattleEvent.of(BattleEvent.Type.PET_SENT_OUT, -1,
			def.getName() + " wants to battle!"));
		state = engine.start(playerTeam, enemyTeam, events);
		pendingEvents.addAll(events);
		beginAnimating();
		return true;
	}

	private BattlePet buildPlayerPet(String speciesId)
	{
		SpeciesDef species = db.species(speciesId);
		PetInstance instance = roster.getOrCreatePet(speciesId);
		if (species == null || instance == null || instance.isFainted())
		{
			// Fainted pets are benched until rested at a bank
			return null;
		}
		List<MoveDef> moves = new ArrayList<>();
		for (String moveId : instance.getEquippedMoves())
		{
			MoveDef move = db.move(moveId);
			if (move != null)
			{
				moves.add(move);
			}
		}
		String variantId = instance.getActiveVariantId();
		if (moves.isEmpty())
		{
			// Fallback: level-1 learnset move so a pet is never unarmed
			for (String moveId : species.movesKnownFor(variantId, 1))
			{
				MoveDef move = db.move(moveId);
				if (move != null)
				{
					moves.add(move);
					break;
				}
			}
		}
		String name = instance.getNickname() != null ? instance.getNickname()
			: species.nameFor(variantId, instance.getLevel());
		return new BattlePet(species, name, instance.getLevel(), moves, instance.getCurrentHp(), variantId);
	}

	private List<MoveDef> enemyMoves(SpeciesDef species, int level)
	{
		List<MoveDef> moves = new ArrayList<>();
		for (String moveId : species.movesKnownAt(level))
		{
			MoveDef move = db.move(moveId);
			if (move != null)
			{
				moves.add(move);
			}
			if (moves.size() >= PetInstance.MAX_EQUIPPED_MOVES)
			{
				break;
			}
		}
		return moves;
	}

	/**
	 * Advance pacing by one game tick. In manual-advance mode (the default) this only
	 * surfaces the first line after a submit; every further line waits for advance().
	 */
	public void tick()
	{
		if (phase != Phase.ANIMATING)
		{
			return;
		}
		if (isManualAdvance())
		{
			if (currentEvent == null)
			{
				advanceEvent();
			}
			return;
		}
		if (++tickCounter < config.battleSpeed())
		{
			return;
		}
		// Per-event dwell: hold impactful events (level-ups, flying projectiles)
		// until their animation has fully played out
		if (getAnimationProgress() < 1f)
		{
			tickCounter = config.battleSpeed();
			return;
		}
		tickCounter = 0;
		advanceEvent();
	}

	/**
	 * Player clicked / pressed Space to show the next battle line (manual-advance mode).
	 */
	public void advance()
	{
		if (phase != Phase.ANIMATING || !isManualAdvance())
		{
			return;
		}
		advanceEvent();
	}

	public boolean isManualAdvance()
	{
		return !config.autoAdvanceBattleText();
	}

	/**
	 * Enter ANIMATING after queuing events. In manual mode the first line shows
	 * immediately instead of waiting a game tick.
	 */
	private void beginAnimating()
	{
		phase = Phase.ANIMATING;
		tickCounter = 0;
		currentEvent = null;
		// Cleared each batch; re-set only if this batch surfaces an enemy on-faint replacement.
		freeSwitchOffer = false;
		if (isManualAdvance())
		{
			advanceEvent();
		}
	}

	/**
	 * Show the next event, or hand over control / finish once the queue drains.
	 */
	private void advanceEvent()
	{
		// Leaving the previous line: rest its HP animation at the value it reached.
		commitHpAnimation();
		BattleEvent event = pendingEvents.poll();
		if (event == null && state.isOver() && !finalized)
		{
			finalizeBattle();
			event = pendingEvents.poll();
		}
		if (event != null)
		{
			if (event.getType() == BattleEvent.Type.FAINTED && event.getSide() == BattleState.ENEMY)
			{
				// An enemy just went down: reward the active pet now and queue its level-up /
				// move-learning lines to play right after this faint line.
				awardFaintXp();
			}
			currentEvent = event;
			eventStartMs = System.currentTimeMillis();
			if (event.getType() == BattleEvent.Type.MOVE_USED)
			{
				// Remembered through the following DAMAGE/STATUS events for tinting
				currentMove = event.getMove();
			}
			// One cue per surfaced line: the move's sound on MOVE_USED, a whiff on MISSED.
			sound.play(event, currentMove);
			if (event.isDeferredSwitch())
			{
				// Apply the enemy's on-faint swap now, so the fainted pet was shown through
				// its faint animation and the replacement appears exactly on this line
				state.setActive(event.getSide(), event.getValue());
				// The enemy just sent in a fresh pet after a KO — flag an optional free swap for
				// the player, honoured at the drain below if they still have a benched pet.
				if (event.getSide() == BattleState.ENEMY)
				{
					freeSwitchOffer = true;
				}
			}
			// Shared-XP bookkeeping: a fresh enemy resets the participant pool; whoever the
			// player has on the field (now, and as they swap) joins the pool for this enemy.
			if (event.getType() == BattleEvent.Type.PET_SENT_OUT && event.getSide() == BattleState.ENEMY)
			{
				enemyParticipants.clear();
			}
			BattlePet activePlayer = state.active(BattleState.PLAYER);
			if (activePlayer != null)
			{
				enemyParticipants.add(activePlayer);
			}
			// Damage/heal lines animate the affected pet's HP bar as they play.
			beginHpAnimation(event);
			// The faint only "reveals" (settles to a ghost) once its own line is shown, never
			// during the earlier attack/damage lines that read the already-resolved model.
			if (event.getType() == BattleEvent.Type.FAINTED)
			{
				BattlePet fainter = state.active(event.getSide());
				if (fainter != null)
				{
					faintShown.add(fainter);
				}
			}
			if (PetBattlesConfig.devBattleTrace())
			{
				log.debug("[battle] event {} side={} phase={} queue={} : {}",
					event.getType(), event.getSide(), phase, pendingEvents.size(), event.getText());
			}
			return;
		}
		currentEvent = null;
		// A full-moveset learn pauses everything until the player forgets a move or skips.
		if (!pendingLearns.isEmpty())
		{
			phase = Phase.LEARN_MOVE;
			return;
		}
		if (state.isOver())
		{
			enterEnded();
			return;
		}
		if (state.awaitingForcedSwitch())
		{
			phase = Phase.FORCED_SWITCH;
			return;
		}
		// The enemy sent in a replacement this batch and the player still has a bench pet: offer
		// one cost-free swap before their next turn. Otherwise it's a normal command turn.
		if (freeSwitchOffer && hasBenchTarget())
		{
			freeSwitchOffer = false;
			phase = Phase.FREE_SWITCH;
			return;
		}
		phase = Phase.AWAITING_INPUT;
	}

	/**
	 * Whether the player has a benched pet (not active, not fainted) they could switch to.
	 */
	private boolean hasBenchTarget()
	{
		List<BattlePet> team = state.team(BattleState.PLAYER);
		int active = state.activeIndex(BattleState.PLAYER);
		for (int i = 0; i < team.size(); i++)
		{
			if (i != active && !team.get(i).isFainted())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Player picked a move (index into the active pet's move list).
	 */
	public void submitMove(int moveIndex)
	{
		if (phase != Phase.AWAITING_INPUT)
		{
			return;
		}
		BattlePet active = state.active(BattleState.PLAYER);
		if (moveIndex < 0 || moveIndex >= active.getMoves().size())
		{
			return;
		}
		BattleAction enemyAction = enemyController.chooseAction(state, BattleState.ENEMY, rng);
		pendingEvents.addAll(engine.resolveTurn(state, BattleAction.move(moveIndex), enemyAction, rng));
		beginAnimating();
	}

	/**
	 * Player swaps the active pet for the bench pet at the given team index.
	 * Consumes the turn; the enemy still acts.
	 */
	public void submitSwitch(int teamIndex)
	{
		if (phase != Phase.AWAITING_INPUT)
		{
			return;
		}
		BattleAction enemyAction = enemyController.chooseAction(state, BattleState.ENEMY, rng);
		pendingEvents.addAll(engine.resolveTurn(state, BattleAction.switchTo(teamIndex), enemyAction, rng));
		beginAnimating();
	}

	/**
	 * Player picked a replacement after their active pet fainted mid-turn. Unlike a
	 * voluntary switch this doesn't run a full turn — the incoming pet just takes over,
	 * and the next real turn is the player's to command.
	 */
	public void submitForcedSwitch(int teamIndex)
	{
		if (phase != Phase.FORCED_SWITCH)
		{
			return;
		}
		pendingEvents.addAll(engine.resolveForcedSwitch(state, teamIndex));
		beginAnimating();
	}

	/**
	 * Player accepted the post-KO free swap: the incoming pet takes over at no turn cost, then
	 * play resumes on the player's next command turn. Ignored unless a free swap is being offered.
	 */
	public void submitFreeSwitch(int teamIndex)
	{
		if (phase != Phase.FREE_SWITCH)
		{
			return;
		}
		pendingEvents.addAll(engine.resolveOptionalSwitch(state, teamIndex));
		beginAnimating();
	}

	/**
	 * Player declined the post-KO free swap and keeps their current pet: straight to their turn.
	 */
	public void declineFreeSwitch()
	{
		if (phase != Phase.FREE_SWITCH)
		{
			return;
		}
		freeSwitchOffer = false;
		phase = Phase.AWAITING_INPUT;
	}

	public void submitFlee()
	{
		if (phase != Phase.AWAITING_INPUT)
		{
			return;
		}
		BattleAction enemyAction = enemyController.chooseAction(state, BattleState.ENEMY, rng);
		pendingEvents.addAll(engine.resolveTurn(state, BattleAction.flee(), enemyAction, rng));
		beginAnimating();
	}

	/**
	 * Dismiss the finished battle (or forfeit-close a stuck one from the panel).
	 */
	public void close()
	{
		// Forfeit-closing mid-battle still persists the damage taken so far
		if (state != null && !finalized && phase != Phase.IDLE)
		{
			persistTeamHp();
			roster.petChanged();
			onRosterChanged.run();
		}
		phase = Phase.IDLE;
		state = null;
		trainer = null;
		pendingEvents.clear();
		summary.clear();
		pendingLearns.clear();
		progress.clear();
		shownHp.clear();
		faintShown.clear();
		enemyParticipants.clear();
		hpAnimPet = null;
		currentEvent = null;
		currentMove = null;
		dismissQuestDialog();
	}

	/**
	 * Award XP for the enemy pet that just fainted, split evenly among every player pet that
	 * took the field against it (shared experience), and queue each earner's XP / level-up /
	 * move-learned lines to play right after the faint line. Full-moveset learns are pushed onto
	 * {@link #pendingLearns} for an in-battle forget-or-skip prompt. Called per enemy faint, so
	 * rewards land per-faint rather than in a lump.
	 */
	private void awardFaintXp()
	{
		BattlePet fallen = state.active(BattleState.ENEMY);
		if (fallen == null)
		{
			return;
		}
		// The pets that were out against this enemy share its reward; fall back to the active
		// pet if (defensively) the pool is somehow empty.
		List<BattlePet> earners = new ArrayList<>(enemyParticipants);
		if (earners.isEmpty())
		{
			BattlePet active = state.active(BattleState.PLAYER);
			if (active != null)
			{
				earners.add(active);
			}
		}
		// Repeat wins against an already-beaten trainer award reduced XP (recorded at battle end,
		// so every faint in a first-clear battle still pays the full first-win rate).
		boolean firstWin = !roster.isTrainerDefeated(trainer.getId());
		// Dev XP boost multiplies rewards so abilities/growth stages are quick to reach in testing
		int mult = Math.max(1, PetBattlesConfig.devXpMultiplier());
		// Split evenly across everyone who was on the field, so a shared KO dilutes each share.
		int share = Math.max(1, earners.size());

		List<BattleEvent> inject = new ArrayList<>();
		boolean anyAward = false;
		for (BattlePet earner : earners)
		{
			String speciesId = earner.getSpecies().getId();
			PetInstance pet = roster.getPet(speciesId);
			SpeciesDef species = db.species(speciesId);
			if (pet == null || species == null || pet.getLevel() >= Leveling.MAX_LEVEL)
			{
				continue;
			}
			int oldLevel = pet.getLevel();
			Progress p = progress.computeIfAbsent(speciesId, k -> new Progress(oldLevel));
			long xp = Math.max(1,
				Leveling.battleWinXp(fallen.getLevel(), pet.getLevel(), firstWin) * mult / share);
			// Cap the per-battle jump relative to the level this pet entered the battle at, so a
			// single win can't rocket a low-level pet up the compressed early curve (feedback #3).
			xp = Leveling.capBattleXp(pet.getXp(), xp, p.startLevel);
			if (xp <= 0)
			{
				// Already hit the per-battle level cap on an earlier faint this fight.
				continue;
			}
			int gained = pet.addXp(xp);
			p.xp += xp;
			anyAward = true;
			if (PetBattlesConfig.devBattleTrace())
			{
				log.debug("[battle] {} shares KO of {} (Lv{}): +{} xp (1/{}), level {}->{}",
					displayName(pet, species), fallen.getDisplayName(), fallen.getLevel(), xp, share,
					oldLevel, pet.getLevel());
			}
			inject.add(BattleEvent.value(BattleEvent.Type.XP_GAINED, BattleState.PLAYER, (int) xp,
				displayName(pet, species) + " gained " + xp + " XP!"));
			if (gained > 0)
			{
				int newLevel = pet.getLevel();
				// Grow the on-field battler too, so its level/HP/stats/sprite match the roster
				// for the rest of this fight (the info card was showing the start-of-battle level).
				int hpGain = earner.growTo(newLevel);
				if (hpGain > 0)
				{
					// Keep the displayed HP in step with the level-up heal so the bar doesn't lag.
					shownHp.merge(earner, (float) hpGain, Float::sum);
				}
				inject.add(BattleEvent.value(BattleEvent.Type.LEVEL_UP, BattleState.PLAYER, newLevel,
					displayName(pet, species) + " grew to level " + newLevel + "!"));
				learnMovesForLevelUp(pet, species, oldLevel, newLevel, p, inject);
			}
		}
		if (anyAward)
		{
			roster.petChanged();
		}
		// Push in front of the queue, preserving order, so they follow the faint line just shown.
		for (int i = inject.size() - 1; i >= 0; i--)
		{
			pendingEvents.addFirst(inject.get(i));
		}
	}

	/**
	 * Equip every newly reached learnset move that fits; queue the rest ({@link PendingLearn})
	 * for the player to resolve. Auto-equipped moves get a MOVE_LEARNED line and land on the
	 * summary; deferred ones are announced only once the player chooses.
	 */
	private void learnMovesForLevelUp(PetInstance pet, SpeciesDef species, int oldLevel, int newLevel,
		Progress p, List<BattleEvent> inject)
	{
		for (LearnsetEntry entry : species.getLearnset())
		{
			if (entry.getLevel() <= oldLevel || entry.getLevel() > newLevel)
			{
				continue;
			}
			MoveDef move = db.move(entry.getMove());
			if (move == null || pet.getEquippedMoves().contains(entry.getMove()))
			{
				continue;
			}
			if (pet.getEquippedMoves().size() < PetInstance.MAX_EQUIPPED_MOVES)
			{
				pet.equipMove(entry.getMove());
				p.learned.add(move.getName());
				inject.add(BattleEvent.of(BattleEvent.Type.MOVE_LEARNED, BattleState.PLAYER,
					displayName(pet, species) + " learned " + move.getName() + "!"));
				if (PetBattlesConfig.devBattleTrace())
				{
					log.debug("[battle] {} auto-learned {} (free slot)", displayName(pet, species), move.getName());
				}
			}
			else
			{
				pendingLearns.add(new PendingLearn(pet, species, move));
				if (PetBattlesConfig.devBattleTrace())
				{
					log.debug("[battle] {} defers {} (full moveset) -> pendingLearns", displayName(pet, species), move.getName());
				}
			}
		}
		// Make any auto-equipped move usable for the rest of THIS battle, not just the next one.
		syncBattleMoves(pet);
	}

	/**
	 * Re-sync a player pet's in-battle moveset from its (just-updated) persistent instance, so a
	 * move learned or forgotten mid-battle takes effect immediately for the current fight.
	 */
	private void syncBattleMoves(PetInstance pet)
	{
		if (state == null)
		{
			return;
		}
		for (BattlePet bp : state.team(BattleState.PLAYER))
		{
			if (!bp.getSpecies().getId().equals(pet.getSpeciesId()))
			{
				continue;
			}
			List<MoveDef> moves = new ArrayList<>();
			for (String moveId : pet.getEquippedMoves())
			{
				MoveDef m = db.move(moveId);
				if (m != null)
				{
					moves.add(m);
				}
			}
			bp.setMoves(moves);
			if (PetBattlesConfig.devBattleTrace())
			{
				log.debug("[battle] resynced {} in-battle moves -> {}", bp.getDisplayName(), pet.getEquippedMoves());
			}
			return;
		}
	}

	/**
	 * The player picked a move to forget (index into the current moveset) or skipped
	 * ({@code moveIndex < 0}) the pending learn. Resolves one queued learn, then either shows
	 * the next one or resumes the battle.
	 */
	public void submitLearnChoice(int moveIndex)
	{
		if (phase != Phase.LEARN_MOVE)
		{
			return;
		}
		PendingLearn pl = pendingLearns.poll();
		if (pl != null)
		{
			List<String> equipped = pl.pet.getEquippedMoves();
			if (!equipped.contains(pl.newMove.getId()))
			{
				if (equipped.size() < PetInstance.MAX_EQUIPPED_MOVES)
				{
					// A slot opened up (an earlier skip/forget in this batch): just learn it.
					pl.pet.equipMove(pl.newMove.getId());
					recordLearned(pl);
				}
				else if (moveIndex >= 0 && moveIndex < equipped.size())
				{
					String forgotten = equipped.get(moveIndex);
					pl.pet.unequipMove(forgotten);
					pl.pet.equipMove(pl.newMove.getId());
					recordLearned(pl);
					if (PetBattlesConfig.devBattleTrace())
					{
						log.debug("[battle] {} forgot {} to learn {}", displayName(pl.pet, pl.species),
							forgotten, pl.newMove.getName());
					}
				}
				// moveIndex < 0 => player declined; learn nothing.
			}
			// Reflect the swap in the active battler's moveset for the rest of this fight.
			syncBattleMoves(pl.pet);
			roster.petChanged();
			onRosterChanged.run();
		}
		if (!pendingLearns.isEmpty())
		{
			return; // stay in LEARN_MOVE for the next queued move
		}
		if (state.isOver())
		{
			enterEnded();
		}
		else
		{
			phase = state.awaitingForcedSwitch() ? Phase.FORCED_SWITCH : Phase.AWAITING_INPUT;
		}
	}

	private void recordLearned(PendingLearn pl)
	{
		Progress p = progress.get(pl.pet.getSpeciesId());
		if (p != null)
		{
			p.learned.add(pl.newMove.getName());
		}
	}

	/**
	 * Finish the battle once the last event drains: persist HP, record the trainer win, save.
	 * XP was already awarded per faint, so nothing is rewarded here.
	 */
	private void finalizeBattle()
	{
		finalized = true;
		// Battle damage survives the fight: heal at a bank to restore it
		persistTeamHp();
		if (state.getPhase() == BattleState.Phase.PLAYER_WON)
		{
			roster.recordTrainerDefeated(trainer.getId());
			grantQuestRewards();
		}
		roster.petChanged();
		onRosterChanged.run();
	}

	/**
	 * One-off quest rewards tied to beating a specific trainer. Beating Professor Oddenstein on
	 * Draynor Manor's top floor completes "Where's the remote?" — he hands over the Remote Battle
	 * Device, unlocking remote battles. Queues an end-screen dialog (with his chathead) and logs a
	 * plugin system line (never player chat), per AGENTS chat rules.
	 */
	private void grantQuestRewards()
	{
		if (TRAINER_PROFESSOR.equals(trainer.getId())
			&& roster.advanceQuest(Quest.WHERES_THE_REMOTE.getId(), Quest.STEP_COMPLETE))
		{
			questDialogTrainerId = trainer.getId();
			questDialogText = "Marvellous battling! I built this Remote Battle Device to challenge "
				+ "trainers all across the realm from my tower... but I keep losing! I'm retiring from "
				+ "pet battles — you take it. Now you can battle any trainer remotely.";
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"<col=ff7700>Pet Battles:</col> You received the <col=ffffff>Remote Battle Device</col>! "
					+ "You can now battle trainers remotely from the panel.", null);
		}
	}

	/**
	 * The one-off end-screen NPC dialog to show (speaker chathead + speech), or null. Shown on the
	 * ENDED screen before the battle summary; cleared by {@link #dismissQuestDialog()}.
	 */
	public String getQuestDialogText()
	{
		return questDialogText;
	}

	/** Trainer id of the {@link #getQuestDialogText() quest dialog} speaker (for the chathead). */
	public String getQuestDialogTrainerId()
	{
		return questDialogTrainerId;
	}

	/** Dismiss the quest dialog so the end screen proceeds to the battle summary. */
	public void dismissQuestDialog()
	{
		questDialogTrainerId = null;
		questDialogText = null;
	}

	/**
	 * Build the post-battle summary (every pet on the battle team gets a slot) and show it.
	 * Called once all queued move-learn prompts are resolved, so learned moves are reflected.
	 */
	private void enterEnded()
	{
		summary.clear();
		List<BattlePet> battled = state.team(BattleState.PLAYER);
		for (int i = 0; i < battled.size(); i++)
		{
			BattlePet bp = battled.get(i);
			PetInstance pet = roster.getPet(bp.getSpecies().getId());
			SpeciesDef species = db.species(bp.getSpecies().getId());
			if (pet == null || species == null)
			{
				continue;
			}
			boolean fought = state.hasFought(BattleState.PLAYER, i);
			Progress p = progress.get(bp.getSpecies().getId());
			long xp = p != null ? p.xp : 0;
			int startLevel = p != null ? p.startLevel : pet.getLevel();
			List<String> learned = p != null ? p.learned : new ArrayList<>();
			summary.add(new SummaryEntry(displayName(pet, species),
				species.itemIdFor(pet.getActiveVariantId(), pet.getLevel()), fought,
				pet.getLevel(), bp.getCurrentHp(), bp.getMaxHp(), bp.isFainted(), xp,
				pet.getLevel() - startLevel, learned));
		}
		phase = Phase.ENDED;
	}

	/**
	 * Write each battled pet's ending HP back to its persistent instance. Full HP is
	 * stored as null ("fully rested") so untouched pets never look injured.
	 * Callers are responsible for the save/refresh (petChanged + onRosterChanged).
	 */
	private void persistTeamHp()
	{
		for (BattlePet battlePet : state.team(BattleState.PLAYER))
		{
			PetInstance instance = roster.getPet(battlePet.getSpecies().getId());
			if (instance == null)
			{
				continue;
			}
			instance.setCurrentHp(battlePet.getCurrentHp() >= battlePet.getMaxHp()
				? null : battlePet.getCurrentHp());
		}
	}

	private static String displayName(PetInstance pet, SpeciesDef species)
	{
		return pet.getNickname() != null ? pet.getNickname()
			: species.nameFor(pet.getActiveVariantId(), pet.getLevel());
	}

	// --- accessors for the overlay ---

	public Phase getPhase()
	{
		return phase;
	}

	public boolean isActive()
	{
		return phase != Phase.IDLE;
	}

	public boolean isAwaitingInput()
	{
		return phase == Phase.AWAITING_INPUT;
	}

	/**
	 * The move-learning choice currently awaiting the player during {@link Phase#LEARN_MOVE},
	 * or null. Reads the pet's live moveset so it always reflects earlier choices in the batch.
	 */
	public LearnPrompt getLearnPrompt()
	{
		PendingLearn pl = pendingLearns.peek();
		if (pl == null)
		{
			return null;
		}
		List<MoveDef> current = new ArrayList<>();
		for (String moveId : pl.pet.getEquippedMoves())
		{
			MoveDef m = db.move(moveId);
			if (m != null)
			{
				current.add(m);
			}
		}
		return new LearnPrompt(displayName(pl.pet, pl.species), pl.newMove, current);
	}

	public BattleState getState()
	{
		return state;
	}

	public TrainerDef getTrainer()
	{
		return trainer;
	}

	/**
	 * Type chart for UI match-up hints (e.g. the swap menu).
	 */
	public TypeChart getTypeChart()
	{
		return engine.getTypeChart();
	}

	/**
	 * Per-pet results for the end-of-battle summary screen (only pets that fought).
	 */
	public List<SummaryEntry> getSummary()
	{
		return Collections.unmodifiableList(summary);
	}

	/**
	 * The event currently on display (drives the dialog box and animations), or null.
	 */
	public BattleEvent getCurrentEvent()
	{
		return currentEvent;
	}

	/**
	 * The move behind the most recent MOVE_USED event, for effect tinting.
	 */
	public MoveDef getCurrentMove()
	{
		return currentMove;
	}

	/**
	 * 0.0 → 1.0 progress of the current event's animation, smooth against wall-clock
	 * time so overlay motion isn't stepped by game ticks.
	 */
	public float getAnimationProgress()
	{
		if (currentEvent == null)
		{
			return 1f;
		}
		long elapsed = System.currentTimeMillis() - eventStartMs;
		return Math.min(1f, elapsed / (float) animationDurationMs(currentEvent.getType()));
	}

	/**
	 * Per-event-type animation dwell so impactful moments get time to read.
	 */
	private static int animationDurationMs(BattleEvent.Type type)
	{
		switch (type)
		{
			case MOVE_USED:
				return 500;
			case DAMAGE:
				return 650;
			case MISSED:
				return 500;
			case LEVEL_UP:
				return 1500;
			case HEALED:
				return 700;
			case FAINTED:
				return 900;
			default:
				return 400;
		}
	}

	/**
	 * Rest the in-flight HP animation at its target, so the bar stays put once its line ends.
	 */
	private void commitHpAnimation()
	{
		if (hpAnimPet != null)
		{
			shownHp.put(hpAnimPet, hpAnimTo);
			hpAnimPet = null;
		}
	}

	/**
	 * If {@code event} changes a pet's HP (damage, status chip, heal), start animating that
	 * pet's displayed HP from where it rests now by the event's OWN delta. Crucially the target
	 * is derived from {@code event.getValue()}, not the live model: the engine resolves the whole
	 * turn up front, so the model already holds the end-of-turn HP. Using the per-event delta lets
	 * each of a pet's several changes in one turn (e.g. a hit then its burn/poison tick) animate
	 * its own step in order. {@code shownHp} tracks the displayed value, so deltas accumulate.
	 */
	private void beginHpAnimation(BattleEvent event)
	{
		BattlePet affected;
		int delta;
		switch (event.getType())
		{
			case DAMAGE:
			case STATUS_TICK:
				affected = event.getSide() >= 0 ? state.active(event.getSide()) : null;
				delta = -event.getValue();
				break;
			case HEALED:
				affected = event.getSide() >= 0 ? state.active(event.getSide()) : null;
				delta = event.getValue();
				break;
			default:
				affected = null;
				delta = 0;
				break;
		}
		if (affected == null)
		{
			return;
		}
		hpAnimPet = affected;
		Float rest = shownHp.get(affected);
		hpAnimFrom = rest != null ? rest : affected.getCurrentHp();
		hpAnimTo = Math.max(0f, Math.min(affected.getMaxHp(), hpAnimFrom + delta));
		if (PetBattlesConfig.devBattleTrace())
		{
			log.debug("[battle] hp {} {} -> {} (d={})", affected.getDisplayName(), hpAnimFrom, hpAnimTo, delta);
		}
	}

	/**
	 * Displayed HP for a pet's bar: the value currently on screen, which lags the resolved
	 * model. While this pet's damage/heal line is showing, it interpolates over the drain
	 * window (so the hit-splat lands first); otherwise it's the value the last line rested on.
	 */
	public float displayHp(BattlePet pet)
	{
		if (pet == null)
		{
			return 0f;
		}
		if (pet == hpAnimPet && currentEvent != null)
		{
			float p = getAnimationProgress();
			float t = p <= HP_DRAIN_START ? 0f
				: p >= HP_DRAIN_END ? 1f
				: (p - HP_DRAIN_START) / (HP_DRAIN_END - HP_DRAIN_START);
			return hpAnimFrom + (hpAnimTo - hpAnimFrom) * t;
		}
		Float rest = shownHp.get(pet);
		return rest != null ? rest : pet.getCurrentHp();
	}

	/**
	 * Whether a fainted pet should be drawn as a settled ghost: its faint line has played and
	 * is no longer the current event (while it IS current, the collapse animation runs instead).
	 */
	public boolean isFaintSettled(BattlePet pet)
	{
		if (pet == null || !faintShown.contains(pet))
		{
			return false;
		}
		return currentEvent == null
			|| currentEvent.getType() != BattleEvent.Type.FAINTED
			|| state.active(currentEvent.getSide()) != pet;
	}

	/**
	 * A move a pet reached with a full moveset, awaiting the player's forget-or-skip choice.
	 */
	private static final class PendingLearn
	{
		private final PetInstance pet;
		private final SpeciesDef species;
		private final MoveDef newMove;

		PendingLearn(PetInstance pet, SpeciesDef species, MoveDef newMove)
		{
			this.pet = pet;
			this.species = species;
			this.newMove = newMove;
		}
	}

	/**
	 * Running per-pet rewards across a battle, used to build the summary once it ends.
	 */
	private static final class Progress
	{
		private final int startLevel;
		private long xp;
		private final List<String> learned = new ArrayList<>();

		Progress(int startLevel)
		{
			this.startLevel = startLevel;
		}
	}

	/**
	 * Read-only view of the current move-learn choice for the overlay: the pet's display name,
	 * the move it wants to learn, and its four current moves (one of which may be forgotten).
	 */
	public static final class LearnPrompt
	{
		private final String petName;
		private final MoveDef newMove;
		private final List<MoveDef> currentMoves;

		LearnPrompt(String petName, MoveDef newMove, List<MoveDef> currentMoves)
		{
			this.petName = petName;
			this.newMove = newMove;
			this.currentMoves = currentMoves;
		}

		public String getPetName()
		{
			return petName;
		}

		public MoveDef getNewMove()
		{
			return newMove;
		}

		public List<MoveDef> getCurrentMoves()
		{
			return currentMoves;
		}
	}
}
