package com.petbattles.engine;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.List;

/**
 * Test fixtures: hand-built species and moves so engine tests need no JSON content.
 */
public final class TestPets
{
	private TestPets()
	{
	}

	public static final MoveDef TACKLE = new MoveDef("tackle", "Tackle", PetType.MELEE, 40, 100, MoveEffect.NONE, 0);
	public static final MoveDef EMBER = new MoveDef("ember", "Ember", PetType.FIRE, 40, 100, MoveEffect.BURN, 100);
	public static final MoveDef ARROW = new MoveDef("arrow", "Arrow", PetType.RANGED, 40, 100, MoveEffect.NONE, 0);
	public static final MoveDef MISS_MOVE = new MoveDef("wild_swing", "Wild Swing", PetType.MELEE, 90, 0, MoveEffect.NONE, 0);
	public static final MoveDef HEAL_MOVE = new MoveDef("regrow", "Regrow", PetType.NATURE, 0, 100, MoveEffect.HEAL, 100);
	public static final MoveDef STUN_MOVE = new MoveDef("bash", "Bash", PetType.MELEE, 0, 100, MoveEffect.STUN, 100);
	public static final MoveDef BUFF_MOVE = new MoveDef("sharpen", "Sharpen", PetType.SKILLING, 0, 100, MoveEffect.ATK_UP, 100);

	public static SpeciesDef species(String id, PetType type, int hp, int atk, int def, int spd)
	{
		// Build via Gson to populate the private fields of the JSON-shaped class
		String json = String.format(
			"{\"id\":\"%s\",\"name\":\"%s\",\"itemId\":1,\"types\":[\"%s\"]," +
				"\"base\":{\"hp\":%d,\"atk\":%d,\"def\":%d,\"spd\":%d}," +
				"\"learnset\":[{\"level\":1,\"move\":\"tackle\"},{\"level\":10,\"move\":\"ember\"}]}",
			id, id, type.name(), hp, atk, def, spd);
		return new Gson().fromJson(json, SpeciesDef.class);
	}

	public static BattlePet pet(String id, PetType type, int level, MoveDef... moves)
	{
		return new BattlePet(species(id, type, 50, 50, 50, 50), id, level, Arrays.asList(moves));
	}

	public static BattlePet fastPet(String id, PetType type, int level, MoveDef... moves)
	{
		return new BattlePet(species(id, type, 50, 50, 50, 99), id, level, Arrays.asList(moves));
	}

	public static BattlePet slowPet(String id, PetType type, int level, MoveDef... moves)
	{
		return new BattlePet(species(id, type, 50, 50, 50, 1), id, level, Arrays.asList(moves));
	}

	public static List<BattlePet> teamOf(BattlePet... pets)
	{
		return Arrays.asList(pets);
	}
}
