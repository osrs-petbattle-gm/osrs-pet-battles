package com.petbattles.ui;

import com.google.gson.Gson;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.SpeciesDef;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The alpha-scan placement in {@link ChatheadAnchors}. Cosmetic placement can only really be judged
 * in-game, so these tests pin the properties that must hold for any head — the anchors stay on the
 * art, the eye line sits below the crown, widths track the head's width at their own row — rather
 * than exact pixel values, which would be re-tuned constantly.
 */
public class ChatheadAnchorsTest
{
	/** A correction sheet shaped like the bundled one: a doc key, two entries, one deliberately bad. */
	private static final String OVERRIDES = "/com/petbattles/ui/test_anchors.json";

	private ChatheadAnchors anchors()
	{
		return new ChatheadAnchors(new Gson());
	}

	/** An opaque {@code w}x{@code h} block at (x, y) on a transparent {@code size}-square canvas. */
	private static BufferedImage block(int size, int x, int y, int w, int h)
	{
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(x, y, w, h);
		g.dispose();
		return img;
	}

	@Test
	public void anchorsCentreOnACentredHead()
	{
		// A 40x40 head centred in a 100px canvas: both anchors should sit on its centre line.
		ChatheadAnchors.Anchors a = anchors().anchors("k", "s", block(100, 30, 30, 40, 40));
		assertEquals(0.5f, a.getHead().getX(), 0.02f);
		assertEquals(0.5f, a.getFace().getX(), 0.02f);
		// Face sits below the crown, and both stay inside the head's vertical extent.
		assertTrue(a.getFace().getY() > a.getHead().getY());
		assertTrue(a.getHead().getY() >= 0.30f);
		assertTrue(a.getFace().getY() <= 0.70f);
	}

	@Test
	public void anchorsFollowAHeadOffToOneSide()
	{
		// Same head shifted right: the anchors must move with it, not stay at the canvas centre.
		ChatheadAnchors.Anchors a = anchors().anchors("k", "s", block(100, 55, 30, 40, 40));
		assertEquals(0.75f, a.getHead().getX(), 0.02f);
		assertEquals(0.75f, a.getFace().getX(), 0.02f);
	}

	@Test
	public void cosmeticWidthTracksTheHeadWidthNotTheCanvas()
	{
		// The whole point of measuring per-row: a narrow head gets a narrow cosmetic.
		ChatheadAnchors wide = anchors();
		ChatheadAnchors narrow = anchors();
		float wideFace = wide.anchors("k", "s", block(100, 20, 20, 60, 60)).getFace().getWidth();
		float narrowFace = narrow.anchors("k", "s", block(100, 40, 20, 20, 60)).getFace().getWidth();
		assertTrue("narrow head should get a narrower cosmetic", narrowFace < wideFace / 2);
	}

	@Test
	public void crownAnchorIgnoresALopsidedEarBelowTheCrownBand()
	{
		// A skull with a long ear hanging off its right side. The bounding-box centre drifts right;
		// the crown-band centroid should stay over the skull, which is where a hat belongs.
		BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(30, 20, 40, 60);  // skull, centred at x = 50
		g.fillRect(70, 55, 25, 10);  // ear jutting right, well below the crown band
		g.dispose();

		ChatheadAnchors.Anchors a = anchors().anchors("k", "s", img);
		// The full bounding box spans 30..95, whose centre is 0.625 — the hat must not go there.
		assertEquals(0.5f, a.getHead().getX(), 0.03f);
	}

	@Test
	public void blankOrDegenerateArtStillYieldsUsableAnchors()
	{
		ChatheadAnchors.Anchors blank = anchors()
			.anchors("k", "s", new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB));
		assertNotNull(blank.getHead());
		assertNotNull(blank.getFace());
		assertTrue(blank.getHead().getWidth() > 0);

		ChatheadAnchors.Anchors none = anchors().anchors("k2", "s", null);
		assertNotNull(none.getHead());
		assertTrue(none.getFace().getWidth() > 0);
	}

	/**
	 * The correction sheet, which is the whole tuning loop: if overrides silently stopped applying,
	 * hand-tuning would appear to do nothing and the misses could never be fixed.
	 */
	@Test
	public void overridesReplaceOnlyTheFieldsTheyName()
	{
		ChatheadAnchors subject = new ChatheadAnchors(new Gson(), OVERRIDES);
		BufferedImage head = block(100, 30, 30, 40, 40);
		ChatheadAnchors.Anchors derived = anchors().anchors("some_species__blue", "some_species", head);
		ChatheadAnchors.Anchors tuned = subject.anchors("some_species__blue", "some_species", head);

		// The exact-key entry names all three head fields, so all three are replaced.
		assertEquals(0.11f, tuned.getHead().getX(), 0.0001f);
		assertEquals(0.22f, tuned.getHead().getY(), 0.0001f);
		assertEquals(0.33f, tuned.getHead().getWidth(), 0.0001f);
		// It says nothing about the face, and the more specific key wins outright, so the species
		// entry's face width does not leak in — the face stays fully derived.
		assertEquals(derived.getFace().getWidth(), tuned.getFace().getWidth(), 0.0001f);
	}

	@Test
	public void aSpeciesEntryCoversFormsWithNoEntryOfTheirOwn()
	{
		ChatheadAnchors subject = new ChatheadAnchors(new Gson(), OVERRIDES);
		BufferedImage head = block(100, 30, 30, 40, 40);
		ChatheadAnchors.Anchors derived = anchors().anchors("some_species__green", "some_species", head);
		ChatheadAnchors.Anchors tuned = subject.anchors("some_species__green", "some_species", head);

		// Falls back to the bare species entry, which names only the face width.
		assertEquals(0.44f, tuned.getFace().getWidth(), 0.0001f);
		assertEquals(derived.getFace().getY(), tuned.getFace().getY(), 0.0001f);
		assertEquals(derived.getHead().getY(), tuned.getHead().getY(), 0.0001f);
	}

	@Test
	public void oneMalformedEntryCostsOnlyItsOwnTuning()
	{
		ChatheadAnchors subject = new ChatheadAnchors(new Gson(), OVERRIDES);
		BufferedImage head = block(100, 30, 30, 40, 40);
		// The broken entry falls back to derived placement rather than throwing...
		ChatheadAnchors.Anchors broken = subject.anchors("broken_pet", "broken_pet", head);
		assertEquals(anchors().anchors("broken_pet", "broken_pet", head).getHead().getY(),
			broken.getHead().getY(), 0.0001f);
		// ...and the valid entries in the same file still apply.
		assertEquals(0.11f, subject.anchors("some_species__blue", "some_species", head).getHead().getX(),
			0.0001f);
	}

	/**
	 * The real bundled art, which is what actually ships. Every chathead must produce anchors that
	 * land on the image with a sensible size — a NaN or an off-image anchor would draw a hat into
	 * the middle of the battle scene.
	 */
	@Test
	public void everyBundledChatheadProducesAnchorsOnTheImage()
	{
		PetDatabase db = PetDatabase.load(new ContentLoader(new Gson()));
		PetChatheads chatheads = new PetChatheads();
		ChatheadAnchors subject = anchors();
		int checked = 0;
		for (SpeciesDef species : db.allSpecies())
		{
			// Base form plus every metamorphosis variant, since each can have its own art.
			List<String> forms = new ArrayList<>();
			forms.add(null);
			for (SpeciesDef.Variant variant : species.getVariants())
			{
				forms.add(variant.getId());
			}
			for (String variantId : forms)
			{
				PetChatheads.Chathead head = chatheads.resolve(species.getId(), variantId, null);
				if (head == null)
				{
					continue;
				}
				checked++;
				String what = head.getKey();
				ChatheadAnchors.Anchors a = subject.anchors(head.getKey(), species.getId(), head.getImage());
				assertOnImage(what + " head", a.getHead());
				assertOnImage(what + " face", a.getFace());
				assertTrue(what + ": face should sit below the crown",
					a.getFace().getY() > a.getHead().getY());
			}
		}
		assertTrue("expected the bundled chatheads to be found", checked > 0);
	}

	private static void assertOnImage(String what, ChatheadAnchors.Anchor a)
	{
		assertTrue(what + ": x off image (" + a.getX() + ")", a.getX() >= 0f && a.getX() <= 1f);
		assertTrue(what + ": y off image (" + a.getY() + ")", a.getY() >= 0f && a.getY() <= 1f);
		assertTrue(what + ": width unusable (" + a.getWidth() + ")",
			a.getWidth() > 0.02f && a.getWidth() <= 1.5f);
	}
}
