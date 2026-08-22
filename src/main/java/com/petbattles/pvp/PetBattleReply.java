package com.petbattles.pvp;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Answer to a {@link PetBattleChallenge}. An acceptance carries the accepter's half of the RNG
 * seed; a refusal carries a short reason to show the challenger.
 */
public class PetBattleReply extends PartyMemberMessage
{
	private long target;
	private boolean accepted;
	private long seed;
	private String reason;
	private int protocol;

	public PetBattleReply()
	{
	}

	public static PetBattleReply accept(long target, long seed)
	{
		PetBattleReply reply = new PetBattleReply();
		reply.target = target;
		reply.accepted = true;
		reply.seed = seed;
		reply.protocol = PvpProtocol.VERSION;
		return reply;
	}

	public static PetBattleReply refuse(long target, String reason)
	{
		PetBattleReply reply = new PetBattleReply();
		reply.target = target;
		reply.reason = reason;
		reply.protocol = PvpProtocol.VERSION;
		return reply;
	}

	public long getTarget()
	{
		return target;
	}

	public boolean isAccepted()
	{
		return accepted;
	}

	/** The accepter's half of the battle seed; meaningless on a refusal. */
	public long getSeed()
	{
		return seed;
	}

	/** Why the challenge was refused, or null when it was accepted. */
	public String getReason()
	{
		return reason;
	}

	public int getProtocol()
	{
		return protocol;
	}
}
