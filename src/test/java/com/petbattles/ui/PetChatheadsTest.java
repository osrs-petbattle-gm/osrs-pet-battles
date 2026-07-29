package com.petbattles.ui;

import org.junit.Test;
import static org.junit.Assert.assertNotNull;
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
}
