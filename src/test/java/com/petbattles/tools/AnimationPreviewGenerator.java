package com.petbattles.tools;

import com.google.gson.Gson;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.BattleEvent;
import com.petbattles.engine.BattleState;
import com.petbattles.engine.MoveDef;
import com.petbattles.engine.PetType;
import com.petbattles.ui.AttackAnimator;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

/**
 * Build-time tool (never on the plugin classpath): renders every move's attack animation to a
 * looping GIF by driving the real {@link AttackAnimator} through a stand-in battle scene, and
 * writes an HTML contact sheet indexing them. This lets you eyeball all the bespoke animations
 * outside the game client — no need to level a pet to each move first.
 *
 * <p>Run via {@code ./gradlew previewAnimations}; writes into {@code build/animations/} only.
 * The player pet is treated as the caster (bottom-left), so effects fly toward the enemy
 * placeholder (top-right), exactly as the overlay lays them out. Placeholder pets are plain
 * blobs — the point is the effect and the caster's motion, not the pet art.
 */
public final class AnimationPreviewGenerator
{
	// Mirrors BattleOverlay's scene geometry so motion/scale read the same as in-game.
	private static final int W = 340;
	private static final int H = 250;
	private static final Rectangle ENEMY_SPRITE = new Rectangle(W - 96, 24, 72, 72);
	private static final Rectangle PLAYER_SPRITE = new Rectangle(24, 88, 72, 72);

	private static final int FRAMES = 30;
	private static final int DELAY_CENTIS = 4; // ~40ms/frame → ~1.2s loop

	private AnimationPreviewGenerator()
	{
	}

	public static void main(String[] args) throws IOException
	{
		Path dir = Paths.get(args.length > 0 ? args[0] : "build/animations");
		Files.createDirectories(dir);
		PetDatabase db = PetDatabase.load(new ContentLoader(new Gson()));

		int written = 0;
		for (MoveDef move : db.allMoves())
		{
			writeGif(renderFrames(move), dir.resolve(move.getId() + ".gif"));
			written++;
		}
		Path index = dir.resolve("index.html");
		Files.write(index, renderIndex(db).getBytes(StandardCharsets.UTF_8));
		System.out.println("Wrote " + written + " animation GIFs and "
			+ index.toAbsolutePath() + " — open it in a browser.");
	}

	// ------------------------------------------------------------------ frame rendering

	private static List<BufferedImage> renderFrames(MoveDef move)
	{
		// The player pet casts; effects travel player → enemy.
		BattleEvent event = BattleEvent.moveUsed(BattleState.PLAYER, move, "");
		Color casterColor = new Color(move.getType().getColorRgb()).brighter();
		List<BufferedImage> frames = new ArrayList<>(FRAMES);
		for (int i = 0; i < FRAMES; i++)
		{
			float progress = FRAMES == 1 ? 0.5f : (float) i / (FRAMES - 1);
			BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

			drawScene(g);
			// Defender stays put (its transform resolves to IDENTITY for the caster's MOVE_USED),
			// caster plays its motion — both via the real AttackAnimator path.
			drawPet(g, ENEMY_SPRITE,
				AttackAnimator.spriteTransform(event, move, progress, BattleState.ENEMY, ENEMY_SPRITE, PLAYER_SPRITE),
				new Color(120, 124, 132));
			drawPet(g, PLAYER_SPRITE,
				AttackAnimator.spriteTransform(event, move, progress, BattleState.PLAYER, PLAYER_SPRITE, ENEMY_SPRITE),
				casterColor);
			AttackAnimator.drawEffects(g, event, move, progress, PLAYER_SPRITE, ENEMY_SPRITE);

			g.dispose();
			frames.add(img);
		}
		return frames;
	}

	private static void drawScene(Graphics2D g)
	{
		g.setColor(new Color(24, 27, 32));
		g.fillRect(0, 0, W, H);
		// A soft ground band, echoing the battle backdrop, so vertical motion has a reference.
		g.setColor(new Color(34, 40, 34));
		g.fillRect(0, H - 70, W, 70);
		g.setColor(new Color(44, 52, 44));
		g.drawLine(0, H - 70, W, H - 70);
	}

	private static void drawPet(Graphics2D g, Rectangle rect, AttackAnimator.Transform t, Color color)
	{
		Graphics2D gg = (Graphics2D) g.create();
		gg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, clamp01(t.alpha)));
		double cx = rect.getCenterX() + t.dx;
		double cy = rect.getCenterY() + t.dy;
		double w = rect.width * t.scale;
		double h = rect.height * t.scale;
		int x = (int) (cx - w / 2);
		int y = (int) (cy - h / 2);
		gg.setColor(color);
		gg.fillOval(x, y, (int) w, (int) h);
		gg.setColor(color.darker());
		gg.drawOval(x, y, (int) w, (int) h);
		// A pair of eyes so orientation and squash/stretch are legible.
		gg.setColor(Color.WHITE);
		int eye = Math.max(3, (int) (w * 0.12));
		gg.fillOval((int) (cx - w * 0.22), (int) (cy - h * 0.12), eye, eye);
		gg.fillOval((int) (cx + w * 0.10), (int) (cy - h * 0.12), eye, eye);
		gg.dispose();
	}

	private static float clamp01(float v)
	{
		return v < 0f ? 0f : (v > 1f ? 1f : v);
	}

	// ------------------------------------------------------------------ animated GIF

	private static void writeGif(List<BufferedImage> frames, Path out) throws IOException
	{
		ImageWriter writer = ImageIO.getImageWritersBySuffix("gif").next();
		try (ImageOutputStream ios = ImageIO.createImageOutputStream(Files.newOutputStream(out)))
		{
			writer.setOutput(ios);
			writer.prepareWriteSequence(null);
			ImageWriteParam param = writer.getDefaultWriteParam();
			for (int i = 0; i < frames.size(); i++)
			{
				BufferedImage frame = frames.get(i);
				IIOMetadata meta = writer.getDefaultImageMetadata(
					new ImageTypeSpecifier(frame), param);
				configureGifFrame(meta, i == 0);
				writer.writeToSequence(new IIOImage(frame, null, meta), param);
			}
			writer.endWriteSequence();
		}
		finally
		{
			writer.dispose();
		}
	}

	private static void configureGifFrame(IIOMetadata meta, boolean firstFrame) throws IOException
	{
		String format = meta.getNativeMetadataFormatName();
		IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);

		IIOMetadataNode gce = childNode(root, "GraphicControlExtension");
		gce.setAttribute("disposalMethod", "none");
		gce.setAttribute("userInputFlag", "FALSE");
		gce.setAttribute("transparentColorFlag", "FALSE");
		gce.setAttribute("delayTime", Integer.toString(DELAY_CENTIS));
		gce.setAttribute("transparentColorIndex", "0");

		if (firstFrame)
		{
			// NETSCAPE2.0 application extension → loop forever (0).
			IIOMetadataNode appExts = childNode(root, "ApplicationExtensions");
			IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
			app.setAttribute("applicationID", "NETSCAPE");
			app.setAttribute("authenticationCode", "2.0");
			app.setUserObject(new byte[]{0x1, 0x0, 0x0});
			appExts.appendChild(app);
		}
		meta.setFromTree(format, root);
	}

	private static IIOMetadataNode childNode(IIOMetadataNode root, String name)
	{
		for (int i = 0; i < root.getLength(); i++)
		{
			if (root.item(i).getNodeName().equalsIgnoreCase(name))
			{
				return (IIOMetadataNode) root.item(i);
			}
		}
		IIOMetadataNode node = new IIOMetadataNode(name);
		root.appendChild(node);
		return node;
	}

	// ------------------------------------------------------------------ index page

	private static String renderIndex(PetDatabase db)
	{
		StringBuilder html = new StringBuilder();
		html.append("<!doctype html><html><head><meta charset=\"utf-8\">")
			.append("<title>Pet Battles — Animation Preview</title><style>")
			.append("body{font-family:sans-serif;background:#16181c;color:#e6e1d2;margin:20px}")
			.append("h1,h2{color:#f0dc78}")
			.append(".grid{display:flex;flex-wrap:wrap;gap:12px}")
			.append(".cell{background:#22262c;border:1px solid #5a4b28;border-radius:8px;padding:8px;width:180px}")
			.append(".cell img{width:170px;height:125px;background:#000;border-radius:4px;image-rendering:pixelated}")
			.append(".name{font-weight:bold;margin:6px 0 2px}")
			.append(".badge{display:inline-block;padding:1px 7px;border-radius:4px;color:#fff;font-size:11px;margin-right:4px}")
			.append(".meta{color:#9a948a;font-size:11px}")
			.append("code{color:#8fd0ff}")
			.append("</style></head><body>");
		html.append("<h1>Pet Battles — Animation Preview</h1>");
		html.append("<p class=\"meta\">Generated from moves.json via <code>./gradlew previewAnimations</code>. ")
			.append("Caster (coloured, bottom-left) throws toward the grey defender (top-right), as in battle. ")
			.append(db.allMoves().size()).append(" moves.</p>");

		for (PetType group : PetType.values())
		{
			StringBuilder cells = new StringBuilder();
			for (MoveDef move : db.allMoves())
			{
				if (move.getType() != group)
				{
					continue;
				}
				cells.append("<div class=\"cell\">")
					.append("<img loading=\"lazy\" alt=\"").append(esc(move.getName()))
					.append("\" src=\"").append(esc(move.getId())).append(".gif\">")
					.append("<div class=\"name\">").append(esc(move.getName())).append("</div>")
					.append(badge(move.getType()))
					.append("<div class=\"meta\">anim: <code>")
					.append(esc(AttackAnimator.animationId(move))).append("</code>")
					.append(move.getPower() > 0 ? " · pow " + move.getPower() : " · status")
					.append("</div></div>");
			}
			if (cells.length() > 0)
			{
				html.append("<h2>").append(esc(group.getDisplayName())).append("</h2>")
					.append("<div class=\"grid\">").append(cells).append("</div>");
			}
		}
		return html.append("</body></html>").toString();
	}

	private static String badge(PetType type)
	{
		return "<span class=\"badge\" style=\"background:#"
			+ String.format("%06x", type.getColorRgb() & 0xFFFFFF) + "\">"
			+ esc(type.getDisplayName()) + "</span>";
	}

	private static String esc(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
