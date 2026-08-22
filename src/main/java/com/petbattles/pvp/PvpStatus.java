package com.petbattles.pvp;

import java.util.List;

/**
 * Read-only view of the PvP layer for the hub to draw: who else is in the party, whether a challenge
 * is in the air, and this account's private win/loss tally. Keeps the UI package free of the party
 * API and of {@link PvpService}'s state machine.
 *
 * <p>Read on the render thread; implementations must tolerate that.
 */
public interface PvpStatus
{
	/** One other member of the party, as a challengeable name. */
	final class Peer
	{
		private final long id;
		private final String name;

		public Peer(long id, String name)
		{
			this.id = id;
			this.name = name;
		}

		public long getId()
		{
			return id;
		}

		public String getName()
		{
			return name;
		}
	}

	/** Whether the player has turned PvP on in the plugin config. */
	boolean isEnabled();

	/** Whether the player is currently in a RuneLite party (joined via the Party plugin). */
	boolean isInParty();

	/** The other members of the party, never including the local player. */
	List<Peer> getPeers();

	/** Whoever has challenged us and is waiting on an answer, or null. */
	Peer getIncoming();

	/** Whoever we have challenged and are waiting on, or null. */
	Peer getOutgoing();

	/** Whether a challenge or battle is already in flight, so no new one can be started. */
	boolean isBusy();

	/** A short line about what just happened ("Alice declined."), or null. */
	String getNote();

	int getWins();

	int getLosses();
}
