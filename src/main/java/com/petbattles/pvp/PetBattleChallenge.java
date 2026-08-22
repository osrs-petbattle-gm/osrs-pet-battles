package com.petbattles.pvp;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * "I'd like to battle you." Sent to one party member, carrying the challenger's half of the RNG
 * seed; the accepter's {@link PetBattleReply} carries the other half, so neither side alone decides
 * how the dice fall.
 *
 * <p>Class names are the wire tags for party messages and the tag space is shared by every plugin
 * on the party server, hence the {@code PetBattle} prefix on all five of these.
 */
public class PetBattleChallenge extends PartyMemberMessage
{
	private long target;
	private long seed;
	private int protocol;

	public PetBattleChallenge()
	{
	}

	public PetBattleChallenge(long target, long seed)
	{
		this.target = target;
		this.seed = seed;
		this.protocol = PvpProtocol.VERSION;
	}

	/** Party member id this challenge is addressed to; everyone else ignores it. */
	public long getTarget()
	{
		return target;
	}

	/** The challenger's half of the battle seed. */
	public long getSeed()
	{
		return seed;
	}

	public int getProtocol()
	{
		return protocol;
	}
}
