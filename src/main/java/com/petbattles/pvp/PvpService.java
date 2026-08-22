package com.petbattles.pvp;

import com.petbattles.PetBattlesConfig;
import com.petbattles.battle.BattleSession;
import com.petbattles.battle.PvpTurnLink;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.BattleAction;
import com.petbattles.engine.BattlePet;
import com.petbattles.engine.BattleState;
import com.petbattles.persist.RosterManager;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.events.UserPart;

/**
 * Player-vs-player battles over RuneLite's own party system.
 *
 * <p>There is no server of ours anywhere in this: two players who have deliberately joined the same
 * party exchange a challenge, then their teams, then one action per turn, and <em>both</em> clients
 * run the identical {@code BattleEngine} over an identically seeded RNG. Neither side is the
 * authority, so nothing either of them says has to be taken on trust — see {@link PvpRosterCodec}
 * for what a peer's team is checked against, and {@link PvpProtocol#checksum} for how the two
 * simulations prove each turn that they still agree.
 *
 * <p><b>Threading.</b> Party messages arrive on the websocket's own thread, so every {@code @Subscribe}
 * here does nothing but hop to the client thread; all the state below is touched there and only
 * there. The exception is the {@link PvpStatus} half, read from the render thread — its fields are
 * volatile and it only ever reads.
 */
@Slf4j
public class PvpService implements PvpStatus, PvpTurnLink
{
	/** Where a challenge (and then a battle) has got to. */
	private enum Stage
	{
		IDLE,
		/** We challenged someone and are waiting for their yes or no. */
		OUTGOING,
		/** Someone challenged us and we haven't answered. */
		INCOMING,
		/** Both sides agreed; waiting for the peer's team to arrive. */
		EXCHANGING,
		/** The battle is running; {@link BattleSession} drives it from here. */
		BATTLING
	}

	/** Ticks a status note ("… declined.") stays on the PvP pane before fading. */
	private static final int NOTE_TICKS = 60;

	private final Client client;
	private final ClientThread clientThread;
	private final PartyService partyService;
	private final PetDatabase db;
	private final RosterManager roster;
	private final PetBattlesConfig config;
	private final BattleSession session;
	private final Runnable onChanged;
	private final SecureRandom seedSource = new SecureRandom();

	// Written on the client thread; the volatiles are the fields the hub reads while drawing (a
	// docked panel paints on the EDT), and a long field needs it to be read whole in the first place.
	private volatile Stage stage = Stage.IDLE;
	// The member we are negotiating or fighting with, and their name at the time we started (the
	// party list can change underneath us mid-battle).
	private volatile long peerId;
	private volatile String peerName;
	private long challengerSeed;
	private long accepterSeed;
	private boolean localIsChallenger;
	// The peer's team, held until our own has been sent and both sides are ready to start.
	private List<WirePet> peerRoster;
	private int stageTicks;
	private volatile String note;
	private int noteTicks;
	// Party membership as of the last game tick, for the hub to draw from off the client thread.
	private volatile List<Peer> peers = Collections.emptyList();

	public PvpService(Client client, ClientThread clientThread, PartyService partyService,
		PetDatabase db, RosterManager roster, PetBattlesConfig config, BattleSession session,
		Runnable onChanged)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.partyService = partyService;
		this.db = db;
		this.roster = roster;
		this.config = config;
		this.session = session;
		this.onChanged = onChanged;
	}

	/** Register every message type this plugin puts on the party. Call from {@code startUp()}. */
	public static void registerMessages(WSClient wsClient)
	{
		wsClient.registerMessage(PetBattleChallenge.class);
		wsClient.registerMessage(PetBattleReply.class);
		wsClient.registerMessage(PetBattleRoster.class);
		wsClient.registerMessage(PetBattleTurn.class);
		wsClient.registerMessage(PetBattleQuit.class);
	}

	/** The mirror of {@link #registerMessages}. Call from {@code shutDown()}. */
	public static void unregisterMessages(WSClient wsClient)
	{
		wsClient.unregisterMessage(PetBattleChallenge.class);
		wsClient.unregisterMessage(PetBattleReply.class);
		wsClient.unregisterMessage(PetBattleRoster.class);
		wsClient.unregisterMessage(PetBattleTurn.class);
		wsClient.unregisterMessage(PetBattleQuit.class);
	}

	// --- hub actions (client thread) ----------------------------------------------------------

	/**
	 * Route a {@code pvp.*} button from the hub. The two surfaces reach this differently — the
	 * overlay is already on the client thread, the docked panel marshals — so this only ever runs
	 * there.
	 */
	public void dispatch(String action)
	{
		if (action.startsWith("challenge:"))
		{
			try
			{
				challenge(Long.parseLong(action.substring("challenge:".length())));
			}
			catch (NumberFormatException e)
			{
				log.debug("[pvp] malformed challenge action {}", action);
			}
		}
		else if ("accept".equals(action))
		{
			acceptIncoming();
		}
		else if ("decline".equals(action))
		{
			declineIncoming();
		}
		else if ("cancel".equals(action))
		{
			cancelOutgoing();
		}
	}

	/** Offer a battle to another party member. */
	private void challenge(long memberId)
	{
		if (!ready() || stage != Stage.IDLE || session.isActive())
		{
			return;
		}
		PartyMember member = partyService.getMemberById(memberId);
		if (member == null || memberId == localId())
		{
			return;
		}
		if (!hasTeam())
		{
			setNote("Your team can't fight — add a pet, or rest at a bank.");
			return;
		}
		peerId = memberId;
		peerName = nameOf(member);
		localIsChallenger = true;
		challengerSeed = seedSource.nextLong();
		accepterSeed = 0;
		peerRoster = null;
		stage = Stage.OUTGOING;
		stageTicks = 0;
		setNote("Challenge sent to " + peerName + "…");
		partyService.send(new PetBattleChallenge(peerId, challengerSeed));
	}

	/** Accept the challenge currently on the table. */
	private void acceptIncoming()
	{
		if (!ready() || stage != Stage.INCOMING || session.isActive())
		{
			return;
		}
		if (!hasTeam())
		{
			setNote("Your team can't fight — add a pet, or rest at a bank.");
			return;
		}
		accepterSeed = seedSource.nextLong();
		stage = Stage.EXCHANGING;
		stageTicks = 0;
		setNote("Battling " + peerName + "…");
		partyService.send(PetBattleReply.accept(peerId, accepterSeed));
		sendOwnRoster();
		startIfReady();
	}

	/** Turn down the challenge currently on the table. */
	private void declineIncoming()
	{
		if (stage != Stage.INCOMING)
		{
			return;
		}
		partyService.send(PetBattleReply.refuse(peerId, "declined"));
		setNote("You declined " + peerName + "'s challenge.");
		reset();
	}

	/** Withdraw a challenge we sent that hasn't been answered. */
	private void cancelOutgoing()
	{
		if (stage != Stage.OUTGOING && stage != Stage.EXCHANGING)
		{
			return;
		}
		partyService.send(new PetBattleQuit(peerId, "cancelled"));
		setNote("Challenge cancelled.");
		reset();
	}

	// --- incoming party messages (websocket thread -> client thread) ---------------------------

	@Subscribe
	public void onPetBattleChallenge(PetBattleChallenge event)
	{
		clientThread.invoke(() -> handleChallenge(event));
	}

	@Subscribe
	public void onPetBattleReply(PetBattleReply event)
	{
		clientThread.invoke(() -> handleReply(event));
	}

	@Subscribe
	public void onPetBattleRoster(PetBattleRoster event)
	{
		clientThread.invoke(() -> handleRoster(event));
	}

	@Subscribe
	public void onPetBattleTurn(PetBattleTurn event)
	{
		clientThread.invoke(() -> handleTurn(event));
	}

	@Subscribe
	public void onPetBattleQuit(PetBattleQuit event)
	{
		clientThread.invoke(() -> handleQuit(event));
	}

	@Subscribe
	public void onUserPart(UserPart event)
	{
		clientThread.invoke(() ->
		{
			if (stage != Stage.IDLE && event.getMemberId() == peerId)
			{
				endBattleOrNegotiation(peerName + " left the party.");
			}
		});
	}

	private void handleChallenge(PetBattleChallenge event)
	{
		if (!addressedToUs(event.getTarget(), event.getMemberId()) || !ready())
		{
			return;
		}
		if (event.getProtocol() != PvpProtocol.VERSION)
		{
			partyService.send(PetBattleReply.refuse(event.getMemberId(), "different plugin version"));
			return;
		}
		if (stage != Stage.IDLE || session.isActive())
		{
			partyService.send(PetBattleReply.refuse(event.getMemberId(), "busy"));
			return;
		}
		PartyMember member = partyService.getMemberById(event.getMemberId());
		peerId = event.getMemberId();
		peerName = member == null ? "A party member" : nameOf(member);
		localIsChallenger = false;
		challengerSeed = event.getSeed();
		accepterSeed = 0;
		peerRoster = null;
		stage = Stage.INCOMING;
		stageTicks = 0;
		setNote(peerName + " has challenged you to a pet battle!");
		// The hub may well be collapsed or on another pane, so the chatbox carries it too.
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			"<col=ff7700>Pet Battles:</col> " + peerName + " has challenged you to a pet battle!"
				+ " Open the PvP tab to accept.", null);
		changed();
	}

	private void handleReply(PetBattleReply event)
	{
		if (!addressedToUs(event.getTarget(), event.getMemberId()) || stage != Stage.OUTGOING
			|| event.getMemberId() != peerId)
		{
			return;
		}
		if (!event.isAccepted() || event.getProtocol() != PvpProtocol.VERSION)
		{
			String reason = PvpRosterCodec.sanitise(event.getReason());
			setNote(peerName + " declined" + (reason == null ? "." : " (" + reason + ")."));
			reset();
			return;
		}
		accepterSeed = event.getSeed();
		stage = Stage.EXCHANGING;
		stageTicks = 0;
		setNote("Battling " + peerName + "…");
		sendOwnRoster();
		startIfReady();
	}

	private void handleRoster(PetBattleRoster event)
	{
		if (!addressedToUs(event.getTarget(), event.getMemberId()) || event.getMemberId() != peerId)
		{
			return;
		}
		if (stage != Stage.EXCHANGING)
		{
			// A roster for a battle we aren't setting up: the peer is out of step, so say so rather
			// than let them sit waiting.
			partyService.send(new PetBattleQuit(event.getMemberId(), "not expecting a battle"));
			return;
		}
		peerRoster = event.getPets();
		startIfReady();
	}

	private void handleTurn(PetBattleTurn event)
	{
		if (!addressedToUs(event.getTarget(), event.getMemberId()) || stage != Stage.BATTLING
			|| event.getMemberId() != peerId)
		{
			return;
		}
		stageTicks = 0;
		BattleAction action = toAction(event);
		if (action == null)
		{
			endBattleOrNegotiation("The opponent sent an action this battle doesn't allow.");
			return;
		}
		session.onOpponentAction(event.getTurn(), action, event.getChecksum());
	}

	private void handleQuit(PetBattleQuit event)
	{
		if (!addressedToUs(event.getTarget(), event.getMemberId()) || stage == Stage.IDLE
			|| event.getMemberId() != peerId)
		{
			return;
		}
		String reason = PvpRosterCodec.sanitise(event.getReason());
		endBattleOrNegotiation(peerName + " ended the battle"
			+ (reason == null ? "." : " (" + reason + ")."));
	}

	private static BattleAction toAction(PetBattleTurn event)
	{
		switch (event.getKind())
		{
			case PetBattleTurn.KIND_MOVE:
				return event.getIndex() < 0 ? null : BattleAction.move(event.getIndex());
			case PetBattleTurn.KIND_SWITCH:
				return event.getIndex() < 0 ? null : BattleAction.switchTo(event.getIndex());
			case PetBattleTurn.KIND_FLEE:
				return BattleAction.flee();
			default:
				return null;
		}
	}

	// --- battle setup / teardown ---------------------------------------------------------------

	private void sendOwnRoster()
	{
		partyService.send(new PetBattleRoster(peerId, PvpRosterCodec.encodeTeam(roster, db)));
	}

	/**
	 * Start the fight once the peer's team is in hand. Both clients reach this independently and
	 * build mirrored states from the same seed, so neither waits on the other to "go".
	 */
	private void startIfReady()
	{
		if (stage != Stage.EXCHANGING || peerRoster == null)
		{
			return;
		}
		List<BattlePet> enemyTeam = PvpRosterCodec.decodeTeam(peerRoster, db);
		peerRoster = null;
		if (enemyTeam == null)
		{
			log.debug("[pvp] refusing {}: roster failed validation", peerName);
			partyService.send(new PetBattleQuit(peerId, "roster rejected"));
			setNote(peerName + "'s team didn't check out — battle cancelled.");
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"<col=ff7700>Pet Battles:</col> " + peerName + "'s team isn't a legal one, so the"
					+ " battle was cancelled.", null);
			reset();
			return;
		}
		long seed = PvpProtocol.mixSeed(challengerSeed, accepterSeed);
		if (!session.startPvpBattle(enemyTeam, peerName, seed, localIsChallenger, this))
		{
			partyService.send(new PetBattleQuit(peerId, "couldn't start"));
			setNote("Couldn't start the battle — check your team.");
			reset();
			return;
		}
		stage = Stage.BATTLING;
		stageTicks = 0;
		note = null;
		changed();
	}

	/**
	 * Give up on whatever is in flight and tell the player why. During a battle this goes through
	 * the session, which also tells the peer; before one it is just local cleanup.
	 */
	private void endBattleOrNegotiation(String reason)
	{
		if (stage == Stage.BATTLING)
		{
			session.opponentAbandoned(reason);
		}
		else
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"<col=ff7700>Pet Battles:</col> " + reason, null);
		}
		setNote(reason);
		reset();
	}

	private void reset()
	{
		stage = Stage.IDLE;
		peerId = 0;
		peerName = null;
		peerRoster = null;
		challengerSeed = 0;
		accepterSeed = 0;
		localIsChallenger = false;
		stageTicks = 0;
		changed();
	}

	/**
	 * Called each game tick: times out a challenge nobody answered, and drops everything if the
	 * player has left the party or turned PvP off underneath a pending battle.
	 */
	public void tick()
	{
		refreshPeers();
		if (noteTicks > 0 && --noteTicks == 0)
		{
			note = null;
		}
		if (stage == Stage.IDLE)
		{
			return;
		}
		if (!config.pvpBattles())
		{
			endBattleOrNegotiation("Player battles were switched off.");
			return;
		}
		if (!partyService.isInParty())
		{
			endBattleOrNegotiation("You left the party.");
			return;
		}
		stageTicks++;
		if (stage == Stage.BATTLING)
		{
			// The session runs its own turn timeout; this only catches a battle whose window has
			// gone away without this service hearing about it.
			if (!session.isActive())
			{
				reset();
			}
			return;
		}
		if (stageTicks > PvpProtocol.NEGOTIATE_TIMEOUT_TICKS)
		{
			if (stage != Stage.INCOMING)
			{
				partyService.send(new PetBattleQuit(peerId, "timed out"));
			}
			setNote(stage == Stage.INCOMING ? "The challenge expired." : peerName + " didn't answer.");
			reset();
		}
	}

	/** Drop everything, without messaging anyone: logging out, or the plugin shutting down. */
	public void shutdown()
	{
		if (stage == Stage.BATTLING)
		{
			partyService.send(new PetBattleQuit(peerId, "left"));
		}
		stage = Stage.IDLE;
		peerId = 0;
		peerName = null;
		peerRoster = null;
		note = null;
		peers = Collections.emptyList();
	}

	// --- PvpTurnLink (client thread) -----------------------------------------------------------

	@Override
	public void sendAction(int turn, BattleAction action, long checksum)
	{
		int kind;
		int index;
		switch (action.getKind())
		{
			case SWITCH:
				kind = PetBattleTurn.KIND_SWITCH;
				index = action.getSwitchIndex();
				break;
			case FLEE:
				kind = PetBattleTurn.KIND_FLEE;
				index = -1;
				break;
			case MOVE:
			default:
				kind = PetBattleTurn.KIND_MOVE;
				index = action.getMoveIndex();
				break;
		}
		partyService.send(new PetBattleTurn(peerId, turn, kind, index, checksum));
	}

	@Override
	public long checksum(BattleState state)
	{
		return PvpProtocol.checksum(state, localIsChallenger);
	}

	@Override
	public void onBattleFinished(BattleState.Phase phase)
	{
		log.debug("[pvp] battle vs {} finished: {}", peerName, phase);
		reset();
	}

	@Override
	public void onAbandoned(String reason)
	{
		partyService.send(new PetBattleQuit(peerId, reason));
		reset();
	}

	// --- PvpStatus (render thread) -------------------------------------------------------------

	@Override
	public boolean isEnabled()
	{
		return config.pvpBattles();
	}

	@Override
	public boolean isInParty()
	{
		return partyService.isInParty();
	}

	@Override
	public List<Peer> getPeers()
	{
		return peers;
	}

	/**
	 * Refresh the party snapshot the hub draws from. Taken here, on the client thread, because
	 * {@code PartyService}'s member list is a plain list mutated on the websocket thread — walking it
	 * from the render thread would eventually throw inside an overlay paint and take the whole hub
	 * down with it.
	 */
	private void refreshPeers()
	{
		List<Peer> snapshot = new ArrayList<>();
		long local = localId();
		for (PartyMember member : partyService.getMembers())
		{
			if (member.getMemberId() != local)
			{
				snapshot.add(new Peer(member.getMemberId(), nameOf(member)));
			}
		}
		peers = Collections.unmodifiableList(snapshot);
	}

	@Override
	public Peer getIncoming()
	{
		return stage == Stage.INCOMING ? new Peer(peerId, peerName) : null;
	}

	@Override
	public Peer getOutgoing()
	{
		return stage == Stage.OUTGOING || stage == Stage.EXCHANGING ? new Peer(peerId, peerName) : null;
	}

	@Override
	public boolean isBusy()
	{
		return stage != Stage.IDLE;
	}

	@Override
	public String getNote()
	{
		return note;
	}

	@Override
	public int getWins()
	{
		return roster.getPvpWins();
	}

	@Override
	public int getLosses()
	{
		return roster.getPvpLosses();
	}

	// --- helpers -------------------------------------------------------------------------------

	/** Whether PvP is usable at all right now: turned on, in a party, and logged in with a roster. */
	private boolean ready()
	{
		return config.pvpBattles() && partyService.isInParty()
			&& partyService.getLocalMember() != null && roster.isLoaded();
	}

	/**
	 * Whether a message is for us and from someone else. Party messages are broadcast to everyone,
	 * including an echo back to the sender, so both halves of this matter.
	 */
	private boolean addressedToUs(long target, long from)
	{
		long local = localId();
		return local != 0 && target == local && from != local;
	}

	private long localId()
	{
		PartyMember local = partyService.getLocalMember();
		return local == null ? 0 : local.getMemberId();
	}

	/** A party member's name, sanitised and never null (it is unset until their client syncs). */
	private static String nameOf(PartyMember member)
	{
		String name = PvpRosterCodec.sanitise(member.getDisplayName());
		return name == null ? "Party member" : name;
	}

	/** Whether the local team could actually take the field right now. */
	private boolean hasTeam()
	{
		return !PvpRosterCodec.encodeTeam(roster, db).isEmpty();
	}

	private void setNote(String text)
	{
		note = text;
		noteTicks = NOTE_TICKS;
		changed();
	}

	private void changed()
	{
		if (onChanged != null)
		{
			onChanged.run();
		}
	}
}
