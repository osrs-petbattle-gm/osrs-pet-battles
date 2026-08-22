package com.petbattles.pvp;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * "This battle is over from my end" — a cancelled challenge, a closed client, a rejected roster or a
 * detected desync. Purely a courtesy: both the negotiation and the battle time out on their own, so
 * a peer that vanishes without sending this still gets cleaned up, just less promptly.
 */
public class PetBattleQuit extends PartyMemberMessage
{
	private long target;
	private String reason;

	public PetBattleQuit()
	{
	}

	public PetBattleQuit(long target, String reason)
	{
		this.target = target;
		this.reason = reason;
	}

	public long getTarget()
	{
		return target;
	}

	/** Short note to show the other player; untrusted text, sanitised on arrival. */
	public String getReason()
	{
		return reason;
	}
}
