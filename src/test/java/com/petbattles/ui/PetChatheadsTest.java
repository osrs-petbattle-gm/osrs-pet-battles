package com.petbattles.ui;

import org.junit.Test;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/**
 * Variant-aware chathead resolution against the real bundled assets: a variant prefers its own
 * {@code <species>__<variant>.png} and falls back to the base {@code <species>.png}.
 */
public class PetChatheadsTest
{
	private final PetChatheads chatheads = new PetChatheads();

	@Test
	public void baseChatheadsLoad()
	{
		assertNotNull("beaver base chathead", chatheads.chathead("beaver"));
		assertNotNull("gull base chathead (newly added)", chatheads.chathead("gull"));
	}

	@Test
	public void variantChatheadsLoad()
	{
		assertNotNull("beaver fox variant", chatheads.chathead("beaver", "fox"));
		assertNotNull("cat hellcat variant", chatheads.chathead("cat", "hellcat"));
		assertNotNull("kalphite second_form variant", chatheads.chathead("kalphite_princess", "second_form"));
	}

	@Test
	public void unknownVariantFallsBackToBase()
	{
		// A variant with no bundled asset returns the base chathead (same cached instance).
		assertSame(chatheads.chathead("beaver"), chatheads.chathead("beaver", "does_not_exist"));
	}

	@Test
	public void stageChatheadsLoad()
	{
		// Base-form stages: cat__kitten.png / cat__overgrown.png distinct from the adult cat.png.
		assertNotNull("base kitten", chatheads.chathead("cat", null, "kitten"));
		assertNotNull("base overgrown", chatheads.chathead("cat", null, "overgrown"));
		assertNotSame("kitten differs from adult",
			chatheads.chathead("cat"), chatheads.chathead("cat", null, "kitten"));
		// Colour + stage: cat__black__kitten.png, and the hellcat's hell-kitten / overgrown art.
		assertNotNull("black kitten", chatheads.chathead("cat", "black", "kitten"));
		assertNotNull("hell-kitten", chatheads.chathead("cat", "hellcat", "kitten"));
		assertNotNull("overgrown hellcat", chatheads.chathead("cat", "hellcat", "overgrown"));
		// Split wily/lazy colour variants each have their own asset.
		assertNotNull("wily black", chatheads.chathead("cat", "wily_black"));
		assertNotNull("lazy grey_and_blue", chatheads.chathead("cat", "lazy_grey_and_blue"));
	}

	@Test
	public void missingStageFallsBackToVariantAdultNotWrongColour()
	{
		// A variant with no art for this stage falls back to its own adult chathead (same colour),
		// never to the base-form stage image of another colour.
		assertSame(chatheads.chathead("cat", "black"),
			chatheads.chathead("cat", "black", "no_such_stage"));
	}
}
