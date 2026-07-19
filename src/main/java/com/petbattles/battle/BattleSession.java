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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * Client-side battle orchestration: builds teams, paces engine events out on game
 * ticks for the overlay, collects player input, and awards XP at the end.
 * All methods are called on the client thread (game ticks and consumed mouse input).
 */
public class BattleSession
{
	public enum Phase
	{
		IDLE,
		ANIMATING,
		AWAITING_INPUT,
		ENDED
	}

	private static final int MAX_LOG_LINES = 4;

	private final PetDatabase db;
	private final RosterManager roster;
	private final PetBattlesConfig config;
	private final BattleEngine engine;
	private final Runnable onRosterChanged;

	private Phase phase = Phase.IDLE;
	private BattleState state;
	private OpponentController enemyController;
	private TrainerDef trainer;
	private Random rng;
	private final Deque<BattleEvent> pendingEvents = new ArrayDeque<>();
	private final LinkedList<String> visibleLog = new LinkedList<>();
	private int tickCounter;
	private boolean xpAwarded;
	private BattleEvent currentEvent;
	private MoveDef currentMove;
	private long eventStartMs;

	public BattleSession(PetDatabase db, RosterManager roster, PetBattlesConfig config, Runnable onRosterChanged)
	{
		this.db = db;
		this.roster = roster;
		this.config = config;
		this.engine = new BattleEngine(db.getTypeChart());
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
			enemyTeam.add(new BattlePet(species, species.getName(), entry.getLevel(),
				enemyMoves(species, entry.getLevel())));
		}

		trainer = def;
		enemyController = new AiController(def.getDifficulty(), db.getTypeChart());
		rng = new Random();
		pendingEvents.clear();
		visibleLog.clear();
		xpAwarded = false;
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
		if (moves.isEmpty())
		{
			// Fallback: level-1 learnset move so a pet is never unarmed
			for (String moveId : species.movesKnownAt(1))
			{
				MoveDef move = db.move(moveId);
				if (move != null)
				{
					moves.add(move);
					break;
				}
			}
		}
		String name = instance.getNickname() != null ? instance.getNickname() : species.getName();
		return new BattlePet(species, name, instance.getLevel(), moves, instance.getCurrentHp());
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
		BattleEvent event = pendingEvents.poll();
		if (event == null && state.isOver() && !xpAwarded)
		{
			awardXp();
			event = pendingEvents.poll();
		}
		if (event != null)
		{
			currentEvent = event;
			eventStartMs = System.currentTimeMillis();
			if (event.getType() == BattleEvent.Type.MOVE_USED)
			{
				// Remembered through the following DAMAGE/STATUS events for tinting
				currentMove = event.getMove();
			}
			pushLog(event.getText());
			return;
		}
		currentEvent = null;
		phase = state.isOver() ? Phase.ENDED : Phase.AWAITING_INPUT;
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
		if (state != null && !xpAwarded && phase != Phase.IDLE)
		{
			persistTeamHp();
		}
		phase = Phase.IDLE;
		state = null;
		trainer = null;
		pendingEvents.clear();
		visibleLog.clear();
		currentEvent = null;
		currentMove = null;
	}

	private void awardXp()
	{
		xpAwarded = true;
		// Battle damage survives the fight: heal at a bank to restore it
		persistTeamHp();
		if (state.getPhase() != BattleState.Phase.PLAYER_WON)
		{
			return;
		}
		int enemyLevel = trainer.getParty().stream()
			.mapToInt(TrainerDef.PartyEntry::getLevel).max().orElse(1);
		for (String speciesId : roster.getTeam())
		{
			PetInstance pet = roster.getPet(speciesId);
			SpeciesDef species = db.species(speciesId);
			if (pet == null || species == null)
			{
				continue;
			}
			int oldLevel = pet.getLevel();
			long xp = Leveling.battleWinXp(enemyLevel, oldLevel);
			int gained = pet.addXp(xp);
			pendingEvents.add(BattleEvent.value(BattleEvent.Type.XP_GAINED, BattleState.PLAYER, (int) xp,
				displayName(pet, species) + " gained " + xp + " XP!"));
			if (gained > 0)
			{
				int newLevel = pet.getLevel();
				pendingEvents.add(BattleEvent.value(BattleEvent.Type.LEVEL_UP, BattleState.PLAYER, newLevel,
					displayName(pet, species) + " grew to level " + newLevel + "!"));
				announceNewMoves(pet, species, oldLevel, newLevel);
			}
		}
		roster.petChanged();
		onRosterChanged.run();
	}

	/**
	 * Write each battled pet's ending HP back to its persistent instance. Full HP is
	 * stored as null ("fully rested") so untouched pets never look injured.
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
		roster.petChanged();
		onRosterChanged.run();
	}

	private void announceNewMoves(PetInstance pet, SpeciesDef species, int oldLevel, int newLevel)
	{
		for (LearnsetEntry entry : species.getLearnset())
		{
			if (entry.getLevel() > oldLevel && entry.getLevel() <= newLevel)
			{
				MoveDef move = db.move(entry.getMove());
				if (move == null)
				{
					continue;
				}
				// Auto-equip when there's room
				if (pet.getEquippedMoves().size() < PetInstance.MAX_EQUIPPED_MOVES)
				{
					pet.equipMove(entry.getMove());
				}
				pendingEvents.add(BattleEvent.of(BattleEvent.Type.MOVE_LEARNED, BattleState.PLAYER,
					displayName(pet, species) + " learned " + move.getName() + "!"));
			}
		}
	}

	private static String displayName(PetInstance pet, SpeciesDef species)
	{
		return pet.getNickname() != null ? pet.getNickname() : species.getName();
	}

	private void pushLog(String line)
	{
		visibleLog.add(line);
		while (visibleLog.size() > MAX_LOG_LINES)
		{
			visibleLog.removeFirst();
		}
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

	public List<String> getVisibleLog()
	{
		return Collections.unmodifiableList(visibleLog);
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
				return 600;
			default:
				return 400;
		}
	}
}
