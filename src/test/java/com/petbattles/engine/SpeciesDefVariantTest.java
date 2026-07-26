package com.petbattles.engine;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Variant resolution: "variant wins" over growth stages, and everything a variant does not name
 * falls through to the base form. Built via Gson so the JSON-shaped private fields populate.
 */
public class SpeciesDefVariantTest
{
	private static final String JSON =
		"{"
			+ "\"id\":\"x\",\"name\":\"Base\",\"itemId\":100,\"altItemIds\":[101],"
			+ "\"npcIds\":[1],\"types\":[\"FIRE\"],"
			+ "\"base\":{\"hp\":50,\"atk\":50,\"def\":50,\"spd\":50},"
			+ "\"learnset\":[{\"level\":1,\"move\":\"tackle\"},{\"level\":10,\"move\":\"ember\"}],"
			+ "\"growthStages\":[{\"level\":1,\"name\":\"BaseYoung\",\"itemId\":100},"
			+ "{\"level\":10,\"name\":\"BaseOld\",\"itemId\":110}],"
			+ "\"variants\":["
			// flat sprite+name override, plus tier-2 type/base and an orientation override
			+ "{\"id\":\"blue\",\"name\":\"Blue\",\"itemId\":200,\"npcIds\":[2],"
			+ "\"unlockItemIds\":[201],\"spriteFacesLeft\":false,\"types\":[\"ICE\"],"
			+ "\"base\":{\"hp\":60,\"atk\":60,\"def\":60,\"spd\":60}},"
			// variant with its own growth-stage art
			+ "{\"id\":\"staged\",\"itemId\":300,"
			+ "\"growthStages\":[{\"level\":1,\"name\":\"StagedYoung\",\"itemId\":300},"
			+ "{\"level\":10,\"name\":\"StagedOld\",\"itemId\":310}]},"
			// type-only override: no sprite/name, so those fall through to the base
			+ "{\"id\":\"typeonly\",\"types\":[\"MAGIC\"]}"
			+ "]}";

	private final SpeciesDef s = new Gson().fromJson(JSON, SpeciesDef.class);

	@Test
	public void nullVariantMatchesLegacyResolution()
	{
		assertNull(s.variant(null));
		assertNull(s.variant("does-not-exist"));
		assertEquals(s.nameAt(1), s.nameFor(null, 1));
		assertEquals("BaseYoung", s.nameFor(null, 1));
		assertEquals("BaseOld", s.nameFor(null, 10));
		assertEquals(s.itemIdAt(10), s.itemIdFor(null, 10));
		assertEquals(110, s.itemIdFor(null, 10));
		assertEquals(s.movesKnownAt(10), s.movesKnownFor(null, 10));
	}

	@Test
	public void flatVariantWinsOverBaseGrowthStages()
	{
		// Blue has no stage art, so its flat name/sprite apply at every level and the base
		// growth stages are bypassed while the variant is active.
		assertEquals("Blue", s.nameFor("blue", 1));
		assertEquals("Blue", s.nameFor("blue", 10));
		assertEquals(200, s.itemIdFor("blue", 1));
		assertEquals(200, s.itemIdFor("blue", 10));
	}

	@Test
	public void variantMayDeclareItsOwnGrowthStages()
	{
		assertEquals("StagedYoung", s.nameFor("staged", 1));
		assertEquals("StagedOld", s.nameFor("staged", 10));
		assertEquals(300, s.itemIdFor("staged", 1));
		assertEquals(310, s.itemIdFor("staged", 10));
	}

	@Test
	public void unnamedOverridesFallThroughToBase()
	{
		// typeonly overrides only types, so name/sprite come from the base (incl. base growth).
		assertEquals("BaseOld", s.nameFor("typeonly", 10));
		assertEquals(110, s.itemIdFor("typeonly", 10));
	}

	@Test
	public void typesAndBaseFollowTheVariant()
	{
		assertEquals(PetType.FIRE, s.typesFor(null).get(0));
		assertEquals(PetType.ICE, s.typesFor("blue").get(0));
		assertEquals(PetType.MAGIC, s.typesFor("typeonly").get(0));
		// staged declares no types, so it inherits the base
		assertEquals(PetType.FIRE, s.typesFor("staged").get(0));

		assertEquals(60, s.baseFor("blue").getHp());
		assertEquals(50, s.baseFor("staged").getHp());
		assertEquals(50, s.baseFor(null).getHp());
	}

	@Test
	public void spriteOrientationOverride()
	{
		assertTrue(s.spriteFacesLeftFor(null));
		assertFalse(s.spriteFacesLeftFor("blue"));
		assertTrue(s.spriteFacesLeftFor("staged"));
	}

	@Test
	public void getAllItemIdsFoldsInVariantOwnershipSignals()
	{
		// base item + alternates + each variant's item and unlock ids — so a form id moved out of
		// altItemIds into a variant still counts as an unlock. Growth-stage art ids are NOT owned.
		assertTrue(s.getAllItemIds().contains(100));
		assertTrue(s.getAllItemIds().contains(101));
		assertTrue("blue variant item", s.getAllItemIds().contains(200));
		assertTrue("blue unlock item", s.getAllItemIds().contains(201));
		assertTrue("staged variant item", s.getAllItemIds().contains(300));
		assertFalse("stage art is not an ownership id", s.getAllItemIds().contains(310));
		assertFalse("base stage art is not an ownership id", s.getAllItemIds().contains(110));
	}
}
