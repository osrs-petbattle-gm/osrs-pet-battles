package com.petbattles.tools;

import com.google.gson.Gson;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.item.EquipItemDef;
import com.petbattles.ui.ChatheadAnchors;
import com.petbattles.ui.ItemSprites;
import com.petbattles.ui.PetChatheads;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Build-time QA tool (never on the plugin classpath): composites every bundled chathead with every
 * cosmetic using the real {@link ChatheadAnchors} placement, and writes an HTML contact sheet.
 *
 * <p>Cosmetic placement is derived from the art rather than hand-authored, so the only way to know
 * whether a hat sits on a head is to look at it — 164 chatheads is far too many to check by
 * levelling pets in-game. This renders all of them at once so the misses can be spotted and
 * corrected in {@code chathead_anchors.json}, then re-run to confirm.
 *
 * <p>Each tile draws the derived anchors on top: a horizontal bar where a hat's base sits and a
 * cross at the eye line, both as wide as the cosmetic would be drawn. When something looks wrong,
 * those marks say whether the measurement missed or the item art is simply the wrong shape.
 *
 * <p>Run via {@code ./gradlew previewCosmetics}; writes into {@code build/cosmetics/} only.
 */
public final class CosmeticPreviewGenerator
{
	/** Chathead box size in a tile, matching the overlay's 72px sprite box. */
	private static final int BOX = 72;

	private static final Color BG = new Color(38, 34, 28);
	private static final Color HEAD_MARK = new Color(120, 220, 140, 200);
	private static final Color FACE_MARK = new Color(120, 180, 255, 200);

	private CosmeticPreviewGenerator()
	{
	}

	public static void main(String[] args) throws IOException
	{
		Path dir = Paths.get(args.length > 0 ? args[0] : "build/cosmetics");
		Files.createDirectories(dir);

		PetDatabase db = PetDatabase.load(new ContentLoader(new Gson()));
		PetChatheads chatheads = new PetChatheads();
		ChatheadAnchors anchors = new ChatheadAnchors(new Gson());
		ItemSprites itemSprites = new ItemSprites();

		List<EquipItemDef> cosmetics = new ArrayList<>();
		for (EquipItemDef item : db.allEquipItems())
		{
			if (item.isCosmetic())
			{
				cosmetics.add(item);
			}
		}

		StringBuilder rows = new StringBuilder();
		int written = 0;
		for (SpeciesDef species : db.allSpecies())
		{
			for (String variantId : forms(species))
			{
				PetChatheads.Chathead head = chatheads.resolve(species.getId(), variantId, null);
				if (head == null)
				{
					continue;
				}
				ChatheadAnchors.Anchors a = anchors.anchors(head.getKey(), species.getId(), head.getImage());
				StringBuilder tiles = new StringBuilder();
				for (EquipItemDef item : cosmetics)
				{
					BufferedImage art = itemSprites.sprite(item.getSprite());
					if (art == null)
					{
						continue;
					}
					String file = head.getKey() + "__" + item.getId() + ".png";
					ImageIO.write(render(head.getImage(), a, item, art), "png", dir.resolve(file).toFile());
					written++;
					tiles.append("<figure><img src=\"").append(file).append("\">")
						.append("<figcaption>").append(item.getName()).append("</figcaption></figure>");
				}
				if (tiles.length() > 0)
				{
					rows.append("<section><h2>").append(head.getKey()).append("</h2>")
						.append(tiles).append("</section>");
				}
			}
		}

		Path index = dir.resolve("index.html");
		Files.write(index, page(rows.toString()).getBytes(StandardCharsets.UTF_8));
		System.out.println("Wrote " + written + " cosmetic previews and "
			+ index.toAbsolutePath() + " — open it in a browser.");
	}

	/** Base form plus every metamorphosis variant, since each can have its own chathead art. */
	private static List<String> forms(SpeciesDef species)
	{
		List<String> forms = new ArrayList<>();
		forms.add(null);
		for (SpeciesDef.Variant variant : species.getVariants())
		{
			forms.add(variant.getId());
		}
		return forms;
	}

	/**
	 * One tile: the chathead fitted into a BOX-square exactly as {@code BattleOverlay} fits it, the
	 * cosmetic placed on its anchor, and the anchor marks drawn over the top.
	 */
	private static BufferedImage render(BufferedImage chathead, ChatheadAnchors.Anchors a,
		EquipItemDef item, BufferedImage art)
	{
		BufferedImage tile = new BufferedImage(BOX, BOX, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = tile.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setColor(BG);
		g.fillRect(0, 0, BOX, BOX);

		// Same aspect-preserving fit the overlay uses, so the preview matches what ships.
		int iw = chathead.getWidth();
		int ih = chathead.getHeight();
		float scale = Math.min((float) BOX / iw, (float) BOX / ih);
		int w = Math.max(1, Math.round(iw * scale));
		int h = Math.max(1, Math.round(ih * scale));
		int px = (BOX - w) / 2;
		int py = (BOX - h) / 2;
		g.drawImage(chathead, px, py, w, h, null);

		boolean isHead = item.getSlot() == EquipItemDef.Slot.HEAD;
		ChatheadAnchors.Anchor anchor = isHead ? a.getHead() : a.getFace();
		int cw = Math.max(1, Math.round(w * anchor.getWidth()));
		int ch = Math.max(1, Math.round(cw * (art.getHeight() / (float) art.getWidth())));
		int cx = px + Math.round(w * anchor.getX());
		int cy = py + Math.round(h * anchor.getY());
		g.drawImage(art, cx - cw / 2, isHead ? cy - ch : cy - ch / 2, cw, ch, null);

		// Anchor marks, so a bad result can be blamed on the measurement or on the art.
		g.setColor(isHead ? HEAD_MARK : FACE_MARK);
		g.drawLine(cx - cw / 2, cy, cx + cw / 2, cy);
		if (!isHead)
		{
			g.drawLine(cx, cy - 4, cx, cy + 4);
		}
		g.dispose();
		return tile;
	}

	private static String page(String body)
	{
		return "<!doctype html><meta charset=\"utf-8\"><title>Pet cosmetic placement</title>"
			+ "<style>"
			+ "body{background:#1b1815;color:#ddd6c8;font:13px/1.5 system-ui,sans-serif;margin:24px}"
			+ "h1{font-size:18px}h2{font-size:13px;font-weight:600;color:#c8bda4;margin:0 0 6px}"
			+ "section{margin:0 0 18px}figure{display:inline-block;margin:0 10px 0 0;text-align:center}"
			+ "img{image-rendering:pixelated;width:144px;height:144px;border:1px solid #3a342c}"
			+ "figcaption{font-size:11px;color:#9a927f;margin-top:2px}"
			+ "p{color:#9a927f;max-width:70ch}"
			+ "</style>"
			+ "<h1>Pet cosmetic placement</h1>"
			+ "<p>Derived from each chathead's alpha channel by ChatheadAnchors. The green bar marks "
			+ "where a hat's base sits; the blue cross marks the eye line. Both are drawn at the width "
			+ "the cosmetic is scaled to. Correct any misses in "
			+ "<code>chathead_anchors.json</code> and re-run.</p>"
			+ body;
	}
}
