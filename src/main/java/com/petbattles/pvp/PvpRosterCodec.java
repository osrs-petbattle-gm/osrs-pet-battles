package com.petbattles.pvp;

import com.petbattles.data.PetDatabase;
import com.petbattles.engine.BattlePet;
import com.petbattles.engine.EasterEggDef;
import com.petbattles.engine.ItemEffect;
import com.petbattles.engine.Leveling;
import com.petbattles.engine.MoveDef;
import com.petbattles.engine.PetInstance;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.item.EquipItemDef;
import com.petbattles.persist.RosterManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the local battle team into something sendable, and — the half that matters — turns a peer's
 * team back into battlers only if it is a team the peer could legitimately have.
 *
 * <p>The peer is another copy of this plugin with no server between us, so a doctored roster is the
 * obvious way to cheat. Everything a wire pet claims is checked against the content both clients
 * ship: the species must exist, the level must be in range, every move must be one that species
 * actually learns by that level (or one of its egg moves), a held item must be a real held item, and
 * HP can't exceed what the pet would have. Ownership is the one thing that can't be checked — there
 * is no way to prove a peer has the collection-log slot — so it deliberately isn't.
 */
public final class PvpRosterCodec
{
	/** Longest peer-supplied nickname kept; the rest is dropped rather than truncated mid-word. */
	private static final int MAX_NAME = 24;

	private PvpRosterCodec()
	{
	}

	/**
	 * The local battle team, ready to send. Fainted pets are left out — the same rule the trainer
	 * battle path applies — so an empty result means there is no team to fight with.
	 */
	public static List<WirePet> encodeTeam(RosterManager roster, PetDatabase db)
	{
		List<WirePet> out = new ArrayList<>();
		for (String speciesId : roster.getTeam())
		{
			SpeciesDef species = db.species(speciesId);
			PetInstance pet = roster.getPet(speciesId);
			if (species == null || pet == null || pet.isFainted())
			{
				continue;
			}
			String variantId = pet.getActiveVariantId();
			List<String> moves = new ArrayList<>(pet.getEquippedMoves());
			if (moves.isEmpty())
			{
				// Mirrors buildPlayerPet's fallback: a pet is never sent out unarmed.
				List<String> known = species.movesKnownFor(variantId, pet.getLevel());
				if (!known.isEmpty())
				{
					moves.add(known.get(0));
				}
			}
			// A pet with no move at all is still sent: the peer will refuse the whole team, which is
			// the honest outcome. Quietly dropping it would leave the two clients holding teams of
			// different sizes, which is a far worse way to find out.
			// 0 means "fully rested", exactly as a null currentHp does on the roster. Sending a
			// computed max instead would be a desync waiting to happen: the owner's client starts
			// the pet at its held-item-boosted max, so the peer has to arrive at that same number
			// rather than at whatever max was worked out here.
			int hp = pet.getCurrentHp() == null ? 0 : pet.getCurrentHp();
			String name = pet.getNickname() != null ? pet.getNickname()
				: species.nameFor(variantId, pet.getLevel());
			out.add(new WirePet(speciesId, variantId, name, pet.getLevel(), hp, moves,
				pet.getHeldItemId(), pet.getHeadItemId(), pet.getFaceItemId()));
		}
		return out;
	}

	/**
	 * Rebuild a peer's team as battlers, or return null if anything about it is illegal. A null
	 * result is a refusal to fight, not something to patch up: a roster that doesn't validate means
	 * the peer isn't running the same rules, and going ahead would desync the two simulations
	 * anyway.
	 */
	public static List<BattlePet> decodeTeam(List<WirePet> wire, PetDatabase db)
	{
		if (wire == null || wire.isEmpty() || wire.size() > RosterManager.MAX_TEAM_SIZE)
		{
			return null;
		}
		List<BattlePet> team = new ArrayList<>();
		List<String> seen = new ArrayList<>();
		for (WirePet wp : wire)
		{
			BattlePet pet = decodePet(wp, db);
			if (pet == null || seen.contains(wp.getSpecies()))
			{
				return null;
			}
			seen.add(wp.getSpecies());
			team.add(pet);
		}
		return team;
	}

	private static BattlePet decodePet(WirePet wp, PetDatabase db)
	{
		if (wp == null || wp.getSpecies() == null)
		{
			return null;
		}
		SpeciesDef species = db.species(wp.getSpecies());
		if (species == null || species.getBase() == null)
		{
			return null;
		}
		String variantId = wp.getVariant();
		if (variantId != null && species.variant(variantId) == null)
		{
			return null;
		}
		int level = wp.getLevel();
		if (level < 1 || level > Leveling.MAX_LEVEL)
		{
			return null;
		}
		List<MoveDef> moves = decodeMoves(wp, species, variantId, db);
		if (moves == null)
		{
			return null;
		}
		ItemEffect held = decodeHeld(wp.getHeld(), db);
		if (held == null && wp.getHeld() != null)
		{
			return null;
		}
		// 0 (or anything below it) is "fully rested" — the same shorthand the roster's null HP uses,
		// and the only way both clients land on the identical starting HP once a held item has moved
		// the maximum. A real value below that is ordinary: battle damage persists between fights,
		// and the constructor caps anything above the max.
		Integer startingHp = wp.getHp() <= 0 ? null : wp.getHp();
		BattlePet pet = new BattlePet(species, displayName(wp, species, variantId), level, moves,
			startingHp, variantId, held);
		pet.setCosmetics(cosmetic(wp.getHead(), EquipItemDef.Slot.HEAD, db),
			cosmetic(wp.getFace(), EquipItemDef.Slot.FACE, db));
		return pet;
	}

	/**
	 * The peer's moveset, or null if it holds a move that species can't have at that level. Anything
	 * in the learnset up to the pet's level counts, plus the species' egg moves — those are unlocked
	 * by a hidden in-world trigger rather than by levelling, so they have no level to check against.
	 */
	private static List<MoveDef> decodeMoves(WirePet wp, SpeciesDef species, String variantId, PetDatabase db)
	{
		List<String> ids = wp.getMoves();
		if (ids.isEmpty() || ids.size() > PetInstance.MAX_EQUIPPED_MOVES)
		{
			return null;
		}
		List<String> legal = new ArrayList<>(species.movesKnownFor(variantId, wp.getLevel()));
		for (EasterEggDef egg : species.getEasterEggs())
		{
			legal.add(egg.getMove());
		}
		List<MoveDef> moves = new ArrayList<>();
		List<String> seen = new ArrayList<>();
		for (String id : ids)
		{
			MoveDef move = id == null ? null : db.move(id);
			if (move == null || !legal.contains(id) || seen.contains(id))
			{
				return null;
			}
			seen.add(id);
			moves.add(move);
		}
		return moves;
	}

	/**
	 * The stat modifier for a claimed held item: null both when nothing is held and when the id is
	 * not a real held item, so callers distinguish the two by looking at the claim itself.
	 */
	private static ItemEffect decodeHeld(String itemId, PetDatabase db)
	{
		if (itemId == null)
		{
			return null;
		}
		EquipItemDef item = db.equipItem(itemId);
		return item == null || item.isCosmetic() ? null : item.getEffect();
	}

	/** A claimed cosmetic id, kept only if it names a real item in that slot. Presentation only. */
	private static String cosmetic(String itemId, EquipItemDef.Slot slot, PetDatabase db)
	{
		if (itemId == null)
		{
			return null;
		}
		EquipItemDef item = db.equipItem(itemId);
		return item != null && item.getSlot() == slot ? itemId : null;
	}

	private static String displayName(WirePet wp, SpeciesDef species, String variantId)
	{
		String sanitised = sanitise(wp.getName());
		return sanitised == null ? species.nameFor(variantId, wp.getLevel()) : sanitised;
	}

	/**
	 * Make a peer-supplied string safe to draw and to print. Battle text goes through the overlay and
	 * the chatbox, and the chatbox reads {@code <col=…>} tags, so angle brackets are stripped along
	 * with control characters; anything left over-long is dropped in favour of the pet's real name.
	 */
	public static String sanitise(String text)
	{
		if (text == null)
		{
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (char c : text.toCharArray())
		{
			if (c >= ' ' && c != '<' && c != '>' && !Character.isISOControl(c))
			{
				sb.append(c);
			}
		}
		String out = sb.toString().trim();
		return out.isEmpty() || out.length() > MAX_NAME ? null : out;
	}
}
