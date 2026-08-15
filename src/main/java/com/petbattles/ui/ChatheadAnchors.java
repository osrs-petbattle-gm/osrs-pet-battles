package com.petbattles.ui;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Works out where a cosmetic sits on a pet chathead.
 *
 * <p>There are 164 chatheads and no two heads are the same shape, so the placement is derived from
 * the art itself rather than hand-authored: one pass over the alpha channel finds the head, and the
 * two anchors fall out of it. This works because a chathead render <em>is</em> a head — there's no
 * body to confuse the measurements.
 *
 * <p>The trick that makes it generalise is measuring the opaque width <em>at the anchor's own
 * row</em> rather than across the whole image. Sizing sunglasses to the head's width at the eye line
 * shrinks them onto a beaver's narrow snout and widens them across a bloodhound's jowls, with no
 * per-species data. Eye <em>detection</em> is not attempted and would not survive this many art
 * styles; instead the eye line is taken as a fixed fraction of head height, which holds up because
 * OSRS chatheads are consistently framed.
 *
 * <p>Note that the bundled chatheads are all cropped tight to the art, so in practice the bounding
 * box comes out as the whole image. The scan is still done rather than assumed: it costs one pass
 * per image at load, it's what makes the per-row widths available, and art added later (or exported
 * by a different hand) is under no obligation to stay trimmed.
 *
 * <p>Expect a good-not-perfect result. Run {@code ./gradlew previewCosmetics} to see every
 * chathead/cosmetic pairing on one page, and correct whatever lands wrong by hand in
 * {@code chathead_anchors.json}, which overrides individual fields on top of the derived values —
 * see {@link #anchors(String, String, BufferedImage)} for how keys resolve.
 */
@Slf4j
public class ChatheadAnchors
{
	private static final String OVERRIDES = "/com/petbattles/data/chathead_anchors.json";

	/** Alpha at or below this counts as transparent — art edges are often faintly feathered. */
	private static final int ALPHA_FLOOR = 8;

	/** The top slice of the head averaged into the crown anchor's x. */
	private static final float CROWN_BAND = 0.15f;

	/**
	 * How far a hat's base sits below the crown, as a fraction of the <em>skull width</em> that sized
	 * it. Measured against width rather than head height on purpose: a hat's drawn height scales with
	 * the width it's given, so its overlap has to scale with the same number or the two drift apart.
	 * Against head height, a wide flat head (a beaver, a mole) gets a big hat and almost no sink, and
	 * the hat floats off the top of the skull.
	 *
	 * <p>Some sink is always wanted — a hat resting exactly on the outline reads as hovering, and on
	 * an eared pet the crown is an ear tip rather than the skull.
	 */
	private static final float CROWN_SINK = 0.16f;

	/** The row whose width sizes a head cosmetic: the upper skull, not the widest point. */
	private static final float SKULL_ROW = 0.25f;

	/** Eye line as a fraction of head height, measured down from the crown. */
	private static final float EYE_ROW = 0.40f;

	/**
	 * Head cosmetic width as a fraction of the skull width at {@link #SKULL_ROW}. Well under 1: a hat
	 * as wide as the skull is drawn correspondingly tall and towers off the top of the sprite box.
	 */
	private static final float HEAD_WIDTH = 0.62f;

	/** Face cosmetic width as a fraction of the head width at the eye line. */
	private static final float FACE_WIDTH = 0.52f;

	/**
	 * Where one cosmetic goes, in coordinates normalised to the chathead image (0..1 of its width
	 * and height) so the numbers survive the art being re-exported at another resolution and are
	 * readable when hand-tuned.
	 *
	 * <p>{@link #x} is always the horizontal centre. {@link #y} means different things per slot, in
	 * each case the edge that should touch the head: for a HEAD cosmetic it is where the
	 * <em>bottom</em> of the hat sits; for a FACE cosmetic it is the <em>centre</em> of the item.
	 */
	public static class Anchor
	{
		private final float x;
		private final float y;
		private final float width;

		public Anchor(float x, float y, float width)
		{
			this.x = x;
			this.y = y;
			this.width = width;
		}

		public float getX()
		{
			return x;
		}

		public float getY()
		{
			return y;
		}

		/** Drawn width of the cosmetic, as a fraction of the chathead image's width. */
		public float getWidth()
		{
			return width;
		}
	}

	/** Both cosmetic anchors for one chathead image. Never null, even for unreadable art. */
	public static class Anchors
	{
		private final Anchor head;
		private final Anchor face;

		Anchors(Anchor head, Anchor face)
		{
			this.head = head;
			this.face = face;
		}

		public Anchor getHead()
		{
			return head;
		}

		public Anchor getFace()
		{
			return face;
		}
	}

	/** JSON shape of a hand-tuned override. Any absent field keeps the derived value. */
	private static class AnchorOverride
	{
		private Float x;
		private Float y;
		private Float width;
	}

	private static class Override
	{
		private AnchorOverride head;
		private AnchorOverride face;
	}

	private final Map<String, Override> overrides;

	// Keyed by the chathead key the image resolved from, so each image is measured once.
	private final Map<String, Anchors> cache = new HashMap<>();

	public ChatheadAnchors(Gson gson)
	{
		this(gson, OVERRIDES);
	}

	/** Visible for testing: read the correction sheet from somewhere other than the bundled path. */
	ChatheadAnchors(Gson gson, String overridesResource)
	{
		this.overrides = loadOverrides(gson, overridesResource);
	}

	/**
	 * Anchors for a chathead, derived from its alpha channel and then patched by any hand-tuned
	 * override. Overrides are looked up by the key the image actually resolved from first (so a
	 * single variant or growth stage can be corrected on its own), then by bare species id (so one
	 * entry can cover every form of a pet). Results are cached per key — the alpha scan runs once
	 * per image, never per frame.
	 *
	 * @param key       the chathead key the image resolved from, e.g. {@code cat__black__kitten}
	 * @param speciesId the species id, used as the fallback override key
	 * @param image     the chathead art to measure
	 */
	public synchronized Anchors anchors(String key, String speciesId, BufferedImage image)
	{
		Anchors cached = cache.get(key);
		if (cached != null)
		{
			return cached;
		}
		Anchors derived = derive(image);
		Override o = overrides.get(key);
		if (o == null)
		{
			o = overrides.get(speciesId);
		}
		Anchors result = o == null ? derived : new Anchors(
			patch(derived.getHead(), o.head), patch(derived.getFace(), o.face));
		cache.put(key, result);
		return result;
	}

	/** Apply an override's present fields on top of a derived anchor. */
	private static Anchor patch(Anchor base, AnchorOverride o)
	{
		if (o == null)
		{
			return base;
		}
		return new Anchor(
			o.x == null ? base.getX() : o.x,
			o.y == null ? base.getY() : o.y,
			o.width == null ? base.getWidth() : o.width);
	}

	/**
	 * Measure a chathead: find the head's alpha bounding box, then place the crown and eye-line
	 * anchors within it. Unreadable or fully transparent art falls back to whole-image guesses so a
	 * cosmetic still draws somewhere sane rather than vanishing.
	 */
	private static Anchors derive(BufferedImage image)
	{
		int w = image == null ? 0 : image.getWidth();
		int h = image == null ? 0 : image.getHeight();
		if (w <= 0 || h <= 0)
		{
			return fallback();
		}

		int minX = w;
		int minY = h;
		int maxX = -1;
		int maxY = -1;
		// Opaque extents per row, so a cosmetic can be sized against the head's width at its own
		// height rather than against the whole bounding box.
		int[] rowMin = new int[h];
		int[] rowMax = new int[h];
		for (int y = 0; y < h; y++)
		{
			rowMin[y] = w;
			rowMax[y] = -1;
			for (int x = 0; x < w; x++)
			{
				if ((image.getRGB(x, y) >>> 24) <= ALPHA_FLOOR)
				{
					continue;
				}
				if (x < rowMin[y])
				{
					rowMin[y] = x;
				}
				rowMax[y] = x;
			}
			if (rowMax[y] < 0)
			{
				continue;
			}
			minX = Math.min(minX, rowMin[y]);
			maxX = Math.max(maxX, rowMax[y]);
			minY = Math.min(minY, y);
			maxY = y;
		}
		if (maxY < 0)
		{
			return fallback();
		}

		int headH = maxY - minY + 1;
		int headW = maxX - minX + 1;

		// Crown x: the centroid of the top band, not the bounding box centre. On an asymmetric head
		// the box centre drifts toward whichever side has the longer ear; the band centroid stays on
		// the skull, which is where a hat belongs.
		int bandBottom = Math.min(maxY, minY + Math.max(0, Math.round(headH * CROWN_BAND)));
		long sum = 0;
		long count = 0;
		for (int y = minY; y <= bandBottom; y++)
		{
			if (rowMax[y] < 0)
			{
				continue;
			}
			int rowWidth = rowMax[y] - rowMin[y] + 1;
			sum += (long) (rowMin[y] + rowMax[y]) * rowWidth;
			count += 2L * rowWidth;
		}
		float crownX = count > 0 ? sum / (float) count : minX + headW / 2f;
		float skullW = rowWidth(rowMin, rowMax, clampRow(minY + headH * SKULL_ROW, minY, maxY), headW);
		// Clamped into the head so a very wide, very flat face can't sink the hat out through the jaw.
		float crownY = Math.min(minY + skullW * CROWN_SINK, minY + headH * 0.5f);

		// Eye line: a fixed fraction down the head. Its x follows that row's own centre so heads
		// drawn at an angle keep their glasses on the face rather than off to one side.
		int eyeRow = clampRow(minY + headH * EYE_ROW, minY, maxY);
		float eyeW = rowWidth(rowMin, rowMax, eyeRow, headW);
		float eyeX = rowMax[eyeRow] >= 0
			? (rowMin[eyeRow] + rowMax[eyeRow]) / 2f
			: minX + headW / 2f;

		return new Anchors(
			new Anchor(crownX / w, crownY / h, (skullW * HEAD_WIDTH) / w),
			new Anchor(eyeX / w, eyeRow / (float) h, (eyeW * FACE_WIDTH) / w));
	}

	/** Opaque width on a row, falling back to the head's full width for a fully transparent one. */
	private static float rowWidth(int[] rowMin, int[] rowMax, int row, int headW)
	{
		return rowMax[row] >= 0 ? rowMax[row] - rowMin[row] + 1 : headW;
	}

	private static int clampRow(float row, int min, int max)
	{
		return Math.max(min, Math.min(max, Math.round(row)));
	}

	/** Centred guesses for art we couldn't measure, so a cosmetic still lands roughly right. */
	private static Anchors fallback()
	{
		return new Anchors(new Anchor(0.5f, 0.10f, 0.5f), new Anchor(0.5f, 0.40f, 0.5f));
	}

	/**
	 * Hand-tuned overrides bundled alongside the other content. Absent or unreadable means "derive
	 * everything" — the file is a correction sheet, not a requirement, so a bad edit degrades to the
	 * automatic placement instead of breaking the battle scene.
	 *
	 * <p>Entries are converted one at a time and keys starting with {@code _} are skipped, so the
	 * file can carry its own documentation and one malformed entry costs only that pet's tuning
	 * rather than every other correction in the file.
	 */
	private static Map<String, Override> loadOverrides(Gson gson, String resource)
	{
		Map<String, Override> parsed = new LinkedHashMap<>();
		try (InputStream in = ChatheadAnchors.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				return parsed;
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				Map<String, JsonElement> raw = gson.fromJson(reader,
					new TypeToken<Map<String, JsonElement>>()
					{
					}.getType());
				if (raw == null)
				{
					return parsed;
				}
				for (Map.Entry<String, JsonElement> e : raw.entrySet())
				{
					if (e.getKey().startsWith("_"))
					{
						continue;
					}
					try
					{
						Override o = gson.fromJson(e.getValue(), Override.class);
						if (o != null)
						{
							parsed.put(e.getKey(), o);
						}
					}
					catch (RuntimeException bad)
					{
						log.debug("Ignoring malformed chathead anchor override for {}", e.getKey(), bad);
					}
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Could not read chathead anchor overrides; using derived placement only", e);
		}
		return parsed;
	}
}
