package com.petbattles.pvp;

import java.util.ArrayList;
import java.util.List;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * The team a player brings to an agreed battle. Sent once, immediately after the challenge is
 * accepted; the battle only starts once each side holds the other's.
 */
public class PetBattleRoster extends PartyMemberMessage
{
	private long target;
	private List<WirePet> pets = new ArrayList<>();

	public PetBattleRoster()
	{
	}

	public PetBattleRoster(long target, List<WirePet> pets)
	{
		this.target = target;
		this.pets = new ArrayList<>(pets);
	}

	public long getTarget()
	{
		return target;
	}

	public List<WirePet> getPets()
	{
		return pets == null ? new ArrayList<>() : pets;
	}
}
