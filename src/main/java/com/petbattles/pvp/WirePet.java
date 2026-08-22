package com.petbattles.pvp;

import java.util.ArrayList;
import java.util.List;

/**
 * One pet as it travels between two clients: only what the engine needs to rebuild an identical
 * battler on the far side. Everything else about the pet (its stats, sprite, types, growth stage)
 * is derived from the bundled content both clients already ship, so a peer can never invent a pet —
 * only name one, and {@link PvpRosterCodec} decides whether that naming is legal.
 */
public class WirePet
{
	private String species;
	private String variant;
	private String name;
	private int level;
	private int hp;
	private List<String> moves = new ArrayList<>();
	private String held;
	private String head;
	private String face;

	public WirePet()
	{
	}

	public WirePet(String species, String variant, String name, int level, int hp, List<String> moves,
		String held, String head, String face)
	{
		this.species = species;
		this.variant = variant;
		this.name = name;
		this.level = level;
		this.hp = hp;
		this.moves = new ArrayList<>(moves);
		this.held = held;
		this.head = head;
		this.face = face;
	}

	public String getSpecies()
	{
		return species;
	}

	/** Active metamorphosis form id, or null for the base form. */
	public String getVariant()
	{
		return variant;
	}

	/** The owner's chosen display name — untrusted text, sanitised on arrival. */
	public String getName()
	{
		return name;
	}

	public int getLevel()
	{
		return level;
	}

	/**
	 * Current HP, or 0 for "fully rested". The maximum is never sent — both clients derive it from
	 * the same content, and sending one would let the two disagree about it.
	 */
	public int getHp()
	{
		return hp;
	}

	public List<String> getMoves()
	{
		return moves == null ? new ArrayList<>() : moves;
	}

	public String getHeld()
	{
		return held;
	}

	public String getHead()
	{
		return head;
	}

	public String getFace()
	{
		return face;
	}
}
