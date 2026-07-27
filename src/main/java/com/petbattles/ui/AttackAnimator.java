package com.petbattles.ui;

import com.petbattles.engine.BattleEvent;
import com.petbattles.engine.MoveDef;
import com.petbattles.engine.MoveEffect;
import com.petbattles.engine.PetType;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;

/**
 * Maps the current battle event to sprite motion and overlay effects. Every effect is pure
 * interpolation over the session's 0→1 animation progress — no state, no allocation beyond
 * loop locals and a couple of small per-frame palettes — so it's safe to call every frame.
 *
 * <p>Each move carries an animation id that encodes both how the caster moves (a lunge, a
 * cast recoil, a swell, a spin) and what the effect looks like. The pet's type colour tints
 * the effect, so a shared id like {@code "explosion"} reads as fire, nature or dragon
 * depending on who threw it.
 */
public final class AttackAnimator
{
	private static final Color WHITE = Color.WHITE;

	/**
	 * Offset/scale applied to one pet sprite this frame.
	 */
	public static final class Transform
	{
		public static final Transform IDENTITY = new Transform(0, 0, 1f, 1f);

		public final int dx;
		public final int dy;
		public final float scale;
		public final float alpha;

		private Transform(int dx, int dy, float scale)
		{
			this(dx, dy, scale, 1f);
		}

		private Transform(int dx, int dy, float scale, float alpha)
		{
			this.dx = dx;
			this.dy = dy;
			this.scale = scale;
			this.alpha = alpha;
		}
	}

	private AttackAnimator()
	{
	}

	/**
	 * Resolve a move's animation id: hand-authored value, else a category default — heal →
	 * sparkle, self-buff → rising aura, enemy debuff → wail, enemy status → cloud, attack → flash.
	 */
	public static String animationId(MoveDef move)
	{
		if (move == null)
		{
			return "flash";
		}
		if (move.getAnimation() != null && !move.getAnimation().isEmpty())
		{
			return move.getAnimation();
		}
		MoveEffect effect = move.getEffect();
		if (move.isStatusMove())
		{
			if (effect == MoveEffect.HEAL)
			{
				return "sparkle";
			}
			if (effect.isSelfBuff())
			{
				return "buff_aura";
			}
			if (effect.isEnemyDebuff())
			{
				return "wail";
			}
			if (effect.isStatus())
			{
				return "cloud";
			}
			return "shake";
		}
		return "flash";
	}

	/**
	 * Sprite transform for {@code side} given the event being animated.
	 */
	public static Transform spriteTransform(BattleEvent event, MoveDef move, float progress,
		int side, Rectangle self, Rectangle other)
	{
		if (event == null)
		{
			return Transform.IDENTITY;
		}
		switch (event.getType())
		{
			case MOVE_USED:
				if (event.getSide() != side)
				{
					return Transform.IDENTITY;
				}
				return casterMotion(animationId(move), progress, self, other);
			case DAMAGE:
			case STATUS_APPLIED:
			case STAT_CHANGED:
			case STATUS_TICK:
				if (event.getSide() != side)
				{
					return Transform.IDENTITY;
				}
				// Horizontal jitter on the afflicted pet, settling as the event ends
				return new Transform(
					(int) (4 * Math.sin(progress * 6 * Math.PI) * (1 - progress)), 0, 1f);
			case FAINTED:
				if (event.getSide() != side)
				{
					return Transform.IDENTITY;
				}
				// Collapse: the fainted pet sinks, shrinks and fades before the next line
				return new Transform(0, (int) (self.height * 0.35f * progress),
					1f - 0.30f * progress, Math.max(0f, 1f - 0.85f * progress));
			default:
				return Transform.IDENTITY;
		}
	}

	/**
	 * How the caster itself moves while its move plays: contact moves lunge in, ranged/cast
	 * moves give a small forward throw, buffs swell, whirls shimmy.
	 */
	private static Transform casterMotion(String id, float progress, Rectangle self, Rectangle other)
	{
		switch (id)
		{
			case "grow":
			case "buff_aura":
				// Swell up and settle back (War Cry / Bulk Up / Harden)
				return new Transform(0, 0, 1f + 0.30f * (float) Math.sin(Math.PI * progress));
			case "lunge":
			case "whip":
			case "slash":
			case "bite":
			case "shockwave":
			{
				// Dart in to make contact and back out
				float f = 0.30f * (float) Math.sin(Math.PI * progress);
				int dx = (int) ((other.getCenterX() - self.getCenterX()) * f);
				int dy = (int) ((other.getCenterY() - self.getCenterY()) * f);
				return new Transform(dx, dy, 1f);
			}
			case "spin":
			{
				// Rapid shimmy with a scale pulse, suggesting a whirl
				int dx = (int) (6 * Math.sin(progress * 8 * Math.PI) * (1 - progress));
				return new Transform(dx, 0, 1f + 0.12f * (float) Math.sin(Math.PI * progress));
			}
			case "sparkle":
				// Gentle rise as it channels a heal
				return new Transform(0, (int) (-4 * Math.sin(Math.PI * progress)), 1f);
			default:
			{
				// Ranged / cast: a quick forward throw that peaks early then holds for the impact
				float f = 0.10f * (float) Math.sin(Math.PI * Math.min(1f, progress * 2f));
				int dx = (int) ((other.getCenterX() - self.getCenterX()) * f);
				int dy = (int) ((other.getCenterY() - self.getCenterY()) * f);
				return new Transform(dx, dy, 1f);
			}
		}
	}

	/**
	 * Overlay-drawn effects for the MOVE_USED phase. Call after the sprites are drawn.
	 */
	public static void drawEffects(Graphics2D g, BattleEvent event, MoveDef move, float progress,
		Rectangle attacker, Rectangle defender)
	{
		if (event == null || event.getType() != BattleEvent.Type.MOVE_USED || move == null)
		{
			return;
		}
		PetType type = move.getType();
		Color tc = new Color(type.getColorRgb());
		int seed = move.getId() == null ? move.hashCode() : move.getId().hashCode();
		switch (animationId(move))
		{
			// --- projectiles (travel from caster to defender, then land) ---
			case "projectile":
				drawProjectile(g, attacker, defender, progress, tc);
				break;
			case "dart":
				drawDart(g, attacker, defender, progress, tc);
				break;
			case "bolt":
				drawBolt(g, attacker, defender, progress, tc);
				break;
			case "orb":
				drawOrb(g, attacker, defender, progress, tc);
				break;
			case "swirl":
				drawSwirl(g, attacker, defender, progress, tc);
				break;
			case "fireball":
				drawFireball(g, attacker, defender, progress, seed, tc);
				break;
			case "ice_shard":
				drawIceShard(g, attacker, defender, progress, seed, tc);
				break;
			case "spinning_log":
				drawSpinningLog(g, attacker, defender, progress);
				break;
			case "chin":
				drawChin(g, attacker, defender, progress, seed);
				break;
			// --- cone ---
			case "breath":
				drawBreath(g, attacker, defender, progress, seed, tc);
				break;
			// --- area effects centred on the defender ---
			case "explosion":
				drawExplosion(g, defender, progress, seed, tc);
				break;
			case "barrage":
				drawBarrage(g, defender, progress, seed);
				break;
			case "blizzard":
				drawBlizzard(g, defender, progress, seed);
				break;
			case "cloud":
				drawCloud(g, defender, progress, seed);
				break;
			case "roots":
				drawRoots(g, defender, progress);
				break;
			// --- contact impacts ---
			case "slash":
				drawSlash(g, defender, progress, tc);
				break;
			case "bite":
				drawBite(g, defender, progress, seed, tc);
				break;
			case "shockwave":
				drawShockwave(g, defender, progress, tc);
				break;
			case "spin":
				drawSpinSlashes(g, defender, progress, tc);
				break;
			case "whip":
				drawWhipLash(g, attacker, defender, progress, tc);
				break;
			case "flash":
				drawFlash(g, defender, progress, tc);
				break;
			// --- caster-centred / status ---
			case "drain":
				drawDrain(g, attacker, defender, progress, seed);
				break;
			case "wail":
				drawWail(g, attacker, defender, progress, tc);
				break;
			case "grow":
			case "buff_aura":
				drawBuffAura(g, attacker, progress, seed, tc);
				break;
			case "sparkle":
				ParticleBurst.render(g, attacker, progress, seed, ParticleBurst.SPARKLE);
				break;
			default:
				break;
		}
	}

	// ------------------------------------------------------------------ projectiles

	private static void drawProjectile(Graphics2D g, Rectangle a, Rectangle d, float p, Color tc)
	{
		double x = lerp(a.getCenterX(), d.getCenterX(), p);
		double y = lerp(a.getCenterY(), d.getCenterY(), p) - 20 * Math.sin(Math.PI * p);
		g.setColor(tc);
		g.fillOval((int) x - 5, (int) y - 5, 10, 10);
		g.setColor(new Color(255, 255, 255, 180));
		g.fillOval((int) x - 2, (int) y - 2, 4, 4);
	}

	private static void drawDart(Graphics2D g, Rectangle a, Rectangle d, float p, Color tc)
	{
		double t = Math.min(1.0, p * 1.25);
		double hx = lerp(a.getCenterX(), d.getCenterX(), t);
		double hy = lerp(a.getCenterY(), d.getCenterY(), t);
		double tt = Math.max(0.0, t - 0.12);
		double sx = lerp(a.getCenterX(), d.getCenterX(), tt);
		double sy = lerp(a.getCenterY(), d.getCenterY(), tt);
		Stroke os = g.getStroke();
		g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(tc);
		g.drawLine((int) sx, (int) sy, (int) hx, (int) hy);
		// arrowhead
		g.setColor(WHITE);
		g.fillOval((int) hx - 2, (int) hy - 2, 4, 4);
		g.setStroke(os);
	}

	private static void drawBolt(Graphics2D g, Rectangle a, Rectangle d, float p, Color tc)
	{
		double t = Math.min(1.0, p * 1.5);
		double hx = lerp(a.getCenterX(), d.getCenterX(), t);
		double hy = lerp(a.getCenterY(), d.getCenterY(), t);
		double tt = Math.max(0.0, t - 0.22);
		double sx = lerp(a.getCenterX(), d.getCenterX(), tt);
		double sy = lerp(a.getCenterY(), d.getCenterY(), tt);
		Stroke os = g.getStroke();
		g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(alpha(tc, 210));
		g.drawLine((int) sx, (int) sy, (int) hx, (int) hy);
		g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(new Color(255, 255, 255, 230));
		g.drawLine((int) sx, (int) sy, (int) hx, (int) hy);
		g.setStroke(os);
		if (t >= 1.0)
		{
			int r = (int) (14 * (p - 0.66) / 0.34);
			g.setColor(alpha(WHITE, (int) (200 * (1 - p))));
			g.drawOval((int) hx - r, (int) hy - r, r * 2, r * 2);
		}
	}

	private static void drawOrb(Graphics2D g, Rectangle a, Rectangle d, float p, Color tc)
	{
		double t = Math.min(1.0, p * 1.2);
		double x = lerp(a.getCenterX(), d.getCenterX(), t);
		double y = lerp(a.getCenterY(), d.getCenterY(), t) - 16 * Math.sin(Math.PI * t);
		if (t < 1.0)
		{
			g.setColor(alpha(tc, 90));
			g.fillOval((int) x - 11, (int) y - 11, 22, 22);
			g.setColor(tc);
			g.fillOval((int) x - 7, (int) y - 7, 14, 14);
			g.setColor(new Color(255, 255, 255, 220));
			g.fillOval((int) x - 3, (int) y - 3, 6, 6);
		}
		if (p > 0.7)
		{
			// splash ring on landing
			int r = (int) (26 * (p - 0.7) / 0.3);
			Stroke os = g.getStroke();
			g.setStroke(new BasicStroke(3f));
			g.setColor(alpha(tc, (int) (200 * (1 - p))));
			g.drawOval((int) d.getCenterX() - r, (int) d.getCenterY() - r, r * 2, r * 2);
			g.setStroke(os);
		}
	}

	private static void drawSwirl(Graphics2D g, Rectangle a, Rectangle d, float p, Color tc)
	{
		double x = lerp(a.getCenterX(), d.getCenterX(), p);
		double y = lerp(a.getCenterY(), d.getCenterY(), p) - 14 * Math.sin(Math.PI * p);
		Stroke os = g.getStroke();
		g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		AffineTransform old = g.getTransform();
		g.translate(x, y);
		g.rotate(p * 6 * Math.PI);
		g.setColor(alpha(tc, 220));
		g.drawArc(-9, -9, 18, 18, 20, 200);
		g.drawArc(-6, -6, 12, 12, 200, 200);
		g.setTransform(old);
		g.setStroke(os);
	}

	private static void drawFireball(Graphics2D g, Rectangle a, Rectangle d, float p, int seed, Color tc)
	{
		double t = Math.min(1.0, p * 1.2);
		double hx = lerp(a.getCenterX(), d.getCenterX(), t);
		double hy = lerp(a.getCenterY(), d.getCenterY(), t) - 14 * Math.sin(Math.PI * t);
		if (t < 1.0)
		{
			// trailing embers
			for (int k = 1; k <= 4; k++)
			{
				double tk = t - 0.07 * k;
				if (tk <= 0)
				{
					continue;
				}
				double ex = lerp(a.getCenterX(), d.getCenterX(), tk);
				double ey = lerp(a.getCenterY(), d.getCenterY(), tk) - 14 * Math.sin(Math.PI * tk);
				Color c = ParticleBurst.EMBER[k % ParticleBurst.EMBER.length];
				g.setColor(alpha(c, 200 - k * 40));
				int s = 6 - k;
				g.fillOval((int) ex - s / 2, (int) ey - s / 2, s, s);
			}
			g.setColor(tc);
			g.fillOval((int) hx - 7, (int) hy - 7, 14, 14);
			g.setColor(new Color(255, 245, 200, 230));
			g.fillOval((int) hx - 3, (int) hy - 3, 6, 6);
		}
		if (p > 0.65)
		{
			drawExplosion(g, d, (float) ((p - 0.65) / 0.35), seed, tc);
		}
	}

	private static void drawIceShard(Graphics2D g, Rectangle a, Rectangle d, float p, int seed, Color tc)
	{
		if (p < 0.75)
		{
			double t = Math.min(1.0, p / 0.75);
			double x = lerp(a.getCenterX(), d.getCenterX(), t);
			double y = lerp(a.getCenterY(), d.getCenterY(), t) - 16 * Math.sin(Math.PI * t);
			int[] xs = {(int) x, (int) x + 5, (int) x, (int) x - 5};
			int[] ys = {(int) y - 9, (int) y, (int) y + 9, (int) y};
			g.setColor(tc);
			g.fillPolygon(xs, ys, 4);
			g.setColor(new Color(255, 255, 255, 200));
			g.drawPolygon(xs, ys, 4);
		}
		else
		{
			// shatter on landing
			ParticleBurst.render(g, d, (float) ((p - 0.75) / 0.25), seed, ParticleBurst.FROST);
		}
	}

	private static void drawSpinningLog(Graphics2D g, Rectangle a, Rectangle d, float p)
	{
		double x = lerp(a.getCenterX(), d.getCenterX(), p);
		double y = lerp(a.getCenterY(), d.getCenterY(), p) - 22 * Math.sin(Math.PI * p);
		AffineTransform old = g.getTransform();
		g.translate(x, y);
		g.rotate(p * 4 * Math.PI);
		g.setColor(new Color(120, 80, 45));
		g.fillRoundRect(-12, -4, 24, 8, 4, 4);
		g.setColor(new Color(80, 52, 28));
		g.drawRoundRect(-12, -4, 24, 8, 4, 4);
		g.drawLine(-6, -4, -6, 4);
		g.drawLine(4, -4, 4, 4);
		g.setTransform(old);
	}

	private static void drawChin(Graphics2D g, Rectangle a, Rectangle d, float p, int seed)
	{
		if (p < 0.55)
		{
			double t = p / 0.55;
			double x = lerp(a.getCenterX(), d.getCenterX(), t);
			double y = lerp(a.getCenterY(), d.getCenterY(), t) - 18 * Math.sin(Math.PI * t);
			g.setColor(new Color(90, 60, 40));
			g.fillOval((int) x - 5, (int) y - 5, 10, 10);
		}
		else
		{
			drawExplosion(g, d, (float) ((p - 0.55) / 0.45), seed, new Color(230, 130, 40));
		}
	}

	// ------------------------------------------------------------------ cone

	private static void drawBreath(Graphics2D g, Rectangle a, Rectangle d, float p, int seed, Color tc)
	{
		double ax = a.getCenterX();
		double ay = a.getCenterY();
		double ang = Math.atan2(d.getCenterY() - ay, d.getCenterX() - ax);
		double dist = Math.hypot(d.getCenterX() - ax, d.getCenterY() - ay);
		double ca = Math.cos(ang);
		double sa = Math.sin(ang);
		for (int i = 0; i < 18; i++)
		{
			int h = hash(seed, i);
			double lat = (h & 0xFF) / 255.0 - 0.5;
			double along = ((h >>> 8) & 0xFF) / 255.0;
			double pr = clamp((p - along * 0.3) / 0.7, 0, 1);
			if (pr <= 0)
			{
				continue;
			}
			double r = pr * dist * 0.95;
			double spread = 0.38 * r * lat;
			double px = ax + ca * r - sa * spread;
			double py = ay + sa * r + ca * spread;
			Color c = ParticleBurst.EMBER[Math.floorMod(h >>> 16, ParticleBurst.EMBER.length)];
			// tint toward the move's type so dragon breath differs from fire breath
			g.setColor(alpha(mix(c, tc, 0.4f), (int) (220 * (1 - pr))));
			int s = 5 - (int) (pr * 3);
			g.fillOval((int) px - s, (int) py - s, s * 2, s * 2);
		}
	}

	// ------------------------------------------------------------------ area effects

	private static void drawExplosion(Graphics2D g, Rectangle d, float p, int seed, Color tc)
	{
		int cx = (int) d.getCenterX();
		int cy = (int) d.getCenterY();
		// central flash
		int fa = (int) (170 * Math.sin(Math.PI * p));
		if (fa > 0)
		{
			g.setColor(alpha(WHITE, fa));
			g.fillOval(cx - 10, cy - 10, 20, 20);
		}
		// expanding ring
		int r = (int) (p * Math.max(d.width, d.height) * 0.85);
		Stroke os = g.getStroke();
		g.setStroke(new BasicStroke(3f));
		g.setColor(alpha(tc, (int) (200 * (1 - p))));
		g.drawOval(cx - r, cy - r, r * 2, r * 2);
		g.setStroke(os);
		ParticleBurst.render(g, d, p, seed, new Color[]{tc, tc.brighter(), new Color(255, 240, 210)});
	}

	private static void drawBarrage(Graphics2D g, Rectangle d, float p, int seed)
	{
		int baseY = d.y + d.height - 2;
		int n = 6;
		g.setColor(alpha(new Color(140, 200, 240), (int) (110 * Math.sin(Math.PI * p))));
		g.fillRoundRect(d.x, d.y, d.width, d.height, 12, 12);
		Stroke os = g.getStroke();
		g.setStroke(new BasicStroke(1.5f));
		for (int i = 0; i < n; i++)
		{
			int h = hash(seed, i);
			double phase = clamp((p - i * 0.05) / 0.5, 0, 1);
			// erupt fast, then hold
			double grow = phase < 0.6 ? phase / 0.6 : 1.0;
			double crystalH = grow * d.height * (0.65 + ((h & 0x7) / 7.0) * 0.5);
			int x = d.x + (int) (d.width * (i + 0.5) / n) + ((h >>> 4 & 0x7) - 3);
			int w = 6 + (h >>> 8 & 0x3);
			int tipY = baseY - (int) crystalH;
			int[] xs = {x - w, x + w, x};
			int[] ys = {baseY, baseY, tipY};
			g.setColor(new Color(150, 210, 245, 235));
			g.fillPolygon(xs, ys, 3);
			g.setColor(new Color(235, 250, 255, 235));
			g.drawPolygon(xs, ys, 3);
			g.drawLine(x, baseY, x, tipY);
		}
		g.setStroke(os);
		if (p > 0.7)
		{
			ParticleBurst.render(g, d, (float) ((p - 0.7) / 0.3), seed, ParticleBurst.FROST);
		}
	}

	private static void drawBlizzard(Graphics2D g, Rectangle d, float p, int seed)
	{
		g.setColor(alpha(new Color(180, 225, 255), (int) (80 * Math.sin(Math.PI * p))));
		g.fillRoundRect(d.x, d.y, d.width, d.height, 12, 12);
		Stroke os = g.getStroke();
		g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		for (int i = 0; i < 20; i++)
		{
			int h = hash(seed, i);
			int x = d.x + Math.floorMod(h, Math.max(1, d.width));
			double phase = ((p * 1.6) + (h >>> 8 & 0xFF) / 255.0) % 1.0;
			int y = d.y + (int) (phase * (d.height + 12)) - 6;
			Color c = ParticleBurst.FROST[Math.floorMod(h >>> 16, ParticleBurst.FROST.length)];
			g.setColor(alpha(c, 220));
			g.drawLine(x, y, x - 4, y + 10);
		}
		g.setStroke(os);
	}

	private static void drawCloud(Graphics2D g, Rectangle d, float p, int seed)
	{
		int cx = (int) d.getCenterX();
		int cy = (int) d.getCenterY();
		float env = (float) Math.sin(Math.PI * Math.min(1f, p * 1.2f));
		for (int i = 0; i < 7; i++)
		{
			int h = hash(seed, i);
			int ox = (h & 0x1F) - 16;
			int oy = ((h >>> 5) & 0x1F) - 16;
			int rise = (int) (p * 12);
			int rad = 10 + (h >>> 10 & 0x7) + (int) (p * 6);
			Color c = ParticleBurst.TOXIN[Math.floorMod(h >>> 16, ParticleBurst.TOXIN.length)];
			g.setColor(alpha(c, (int) (120 * env)));
			g.fillOval(cx + ox - rad, cy + oy - rise - rad, rad * 2, rad * 2);
		}
	}

	private static void drawRoots(Graphics2D g, Rectangle d, float p)
	{
		int baseY = d.y + d.height;
		int n = 4;
		Stroke os = g.getStroke();
		g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(new Color(60, 130, 55, 235));
		for (int i = 0; i < n; i++)
		{
			int x = d.x + (int) (d.width * (i + 0.5) / n);
			int climb = (int) (p * d.height);
			Path2D vine = new Path2D.Double();
			vine.moveTo(x, baseY);
			int segs = 6;
			for (int s = 1; s <= segs; s++)
			{
				double f = (double) s / segs;
				if (f * d.height > climb)
				{
					break;
				}
				double wob = Math.sin(f * Math.PI * 3 + i) * 6;
				vine.lineTo(x + wob, baseY - f * d.height);
			}
			g.draw(vine);
		}
		g.setStroke(os);
	}

	// ------------------------------------------------------------------ contact impacts

	private static void drawSlash(Graphics2D g, Rectangle d, float p, Color tc)
	{
		Stroke os = g.getStroke();
		for (int i = 0; i < 3; i++)
		{
			float start = 0.12f + i * 0.16f;
			float local = (p - start) / 0.35f;
			if (local <= 0 || local >= 1)
			{
				continue;
			}
			int a = (int) (230 * (1 - local));
			int ox = (i - 1) * 12;
			int x1 = d.x + d.width / 4 + ox;
			int y1 = d.y + d.height / 4;
			int x2 = d.x + 3 * d.width / 4 + ox;
			int y2 = d.y + 3 * d.height / 4;
			g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(alpha(tc, a));
			g.drawLine(x1, y1, x2, y2);
			g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(alpha(WHITE, a));
			g.drawLine(x1, y1, x2, y2);
		}
		g.setStroke(os);
	}

	private static void drawBite(Graphics2D g, Rectangle d, float p, int seed, Color tc)
	{
		int cx = (int) d.getCenterX();
		int cy = (int) d.getCenterY();
		int w = d.width / 3;
		int gap = (int) ((1 - Math.sin(Math.PI * p)) * d.height * 0.32);
		Stroke os = g.getStroke();
		g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(alpha(WHITE, 220));
		// upper jaw (teeth pointing down)
		int[] ux = {cx - w, cx - w / 2, cx, cx + w / 2, cx + w};
		int uy0 = cy - gap - 8;
		int[] uy = {uy0, uy0 + 8, uy0, uy0 + 8, uy0};
		g.drawPolyline(ux, uy, 5);
		// lower jaw (teeth pointing up)
		int ly0 = cy + gap + 8;
		int[] ly = {ly0, ly0 - 8, ly0, ly0 - 8, ly0};
		g.drawPolyline(ux, ly, 5);
		g.setStroke(os);
		if (p > 0.45)
		{
			// status-coloured splash as the jaws close
			ParticleBurst.render(g, new Rectangle(cx - 6, cy - 6, 12, 12),
				(float) ((p - 0.45) / 0.55), seed, new Color[]{tc, tc.brighter(), WHITE});
		}
	}

	private static void drawShockwave(Graphics2D g, Rectangle d, float p, Color tc)
	{
		int cx = (int) d.getCenterX();
		int cy = (int) d.getCenterY();
		if (p < 0.3)
		{
			g.setColor(alpha(WHITE, (int) (220 * (1 - p / 0.3))));
			g.fillOval(cx - 12, cy - 12, 24, 24);
		}
		Stroke os = g.getStroke();
		g.setStroke(new BasicStroke(4f));
		for (int k = 0; k < 2; k++)
		{
			float local = p - k * 0.18f;
			if (local <= 0)
			{
				continue;
			}
			int r = (int) (local * d.width * 0.9);
			g.setColor(alpha(tc, (int) (200 * (1 - local))));
			g.drawOval(cx - r, cy - r, r * 2, r * 2);
		}
		g.setStroke(os);
	}

	private static void drawSpinSlashes(Graphics2D g, Rectangle d, float p, Color tc)
	{
		int cx = (int) d.getCenterX();
		int cy = (int) d.getCenterY();
		int r = (int) (d.width * 0.5);
		Stroke os = g.getStroke();
		g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(alpha(tc, (int) (220 * Math.sin(Math.PI * p))));
		for (int i = 0; i < 6; i++)
		{
			double a = p * 4 * Math.PI + i * Math.PI / 3;
			int x = cx + (int) (Math.cos(a) * r);
			int y = cy + (int) (Math.sin(a) * r);
			int x2 = cx + (int) (Math.cos(a) * (r - 9));
			int y2 = cy + (int) (Math.sin(a) * (r - 9));
			g.drawLine(x, y, x2, y2);
		}
		g.setStroke(os);
	}

	private static void drawWhipLash(Graphics2D g, Rectangle a, Rectangle d, float p, Color tc)
	{
		double ext = Math.sin(Math.PI * p);
		double ax = a.getCenterX();
		double ay = a.getCenterY();
		double ex = lerp(ax, d.getCenterX(), ext);
		double ey = lerp(ay, d.getCenterY(), ext);
		double mx = (ax + ex) / 2;
		double my = Math.min(ay, ey) - 22 * ext;
		Path2D lash = new Path2D.Double();
		lash.moveTo(ax, ay);
		lash.quadTo(mx, my, ex, ey);
		Stroke os = g.getStroke();
		g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(tc);
		g.draw(lash);
		g.setStroke(os);
		if (ext > 0.9)
		{
			g.setColor(alpha(WHITE, 200));
			g.fillOval((int) ex - 4, (int) ey - 4, 8, 8);
		}
	}

	private static void drawFlash(Graphics2D g, Rectangle d, float p, Color tc)
	{
		int a = (int) (140 * Math.sin(Math.PI * p));
		if (a > 0)
		{
			g.setColor(alpha(tc, a));
			g.fillRoundRect(d.x, d.y, d.width, d.height, 12, 12);
		}
	}

	// ------------------------------------------------------------------ caster / status

	private static void drawDrain(Graphics2D g, Rectangle a, Rectangle d, float p, int seed)
	{
		for (int i = 0; i < 12; i++)
		{
			int h = hash(seed, i);
			double pr = ((p * 1.3) + (h & 0xFF) / 255.0) % 1.0;
			double x = lerp(d.getCenterX(), a.getCenterX(), pr) + ((h >>> 8 & 0x7) - 3);
			double y = lerp(d.getCenterY(), a.getCenterY(), pr) - 10 * Math.sin(Math.PI * pr)
				+ ((h >>> 11 & 0x7) - 3);
			Color c = ParticleBurst.SHADOW[Math.floorMod(h >>> 16, ParticleBurst.SHADOW.length)];
			g.setColor(alpha(c, (int) (220 * (1 - pr))));
			g.fillOval((int) x - 3, (int) y - 3, 6, 6);
		}
	}

	private static void drawWail(Graphics2D g, Rectangle a, Rectangle d, float p, Color tc)
	{
		int ax = (int) a.getCenterX();
		int ay = (int) a.getCenterY();
		int dir = d.getCenterX() >= ax ? 1 : -1;
		double reach = Math.abs(d.getCenterX() - ax);
		Stroke os = g.getStroke();
		g.setStroke(new BasicStroke(2.5f));
		for (int k = 0; k < 3; k++)
		{
			double local = clamp((p - k * 0.14) / 0.7, 0, 1);
			if (local <= 0)
			{
				continue;
			}
			int rx = (int) (local * reach);
			int ry = (int) (12 + local * d.height * 0.5);
			g.setColor(alpha(tc, (int) (200 * (1 - local))));
			g.drawArc(ax - (dir < 0 ? rx : 0), ay - ry, rx, ry * 2,
				dir > 0 ? -70 : 110, 140);
		}
		g.setStroke(os);
		if (p > 0.6)
		{
			g.setColor(alpha(tc, (int) (90 * (1 - p))));
			g.fillRoundRect(d.x, d.y, d.width, d.height, 12, 12);
		}
	}

	private static void drawBuffAura(Graphics2D g, Rectangle a, float p, int seed, Color tc)
	{
		for (int i = 0; i < 14; i++)
		{
			int h = hash(seed, i);
			double pr = ((p) + (h & 0xFF) / 255.0) % 1.0;
			int x = (int) a.getCenterX() + ((h >>> 8 & 0x3F) - 32);
			int y = a.y + a.height - (int) (pr * a.height);
			g.setColor(alpha(tc, (int) (200 * (1 - pr))));
			int s = 2 + (h >>> 14 & 1);
			g.fillRect(x, y, s, s);
		}
		// a couple of rising chevrons
		Stroke os = g.getStroke();
		g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(alpha(tc, (int) (220 * Math.sin(Math.PI * p))));
		int cx = (int) a.getCenterX();
		int cy = a.y + a.height / 2 - (int) (p * a.height * 0.4);
		g.drawPolyline(new int[]{cx - 8, cx, cx + 8}, new int[]{cy + 6, cy - 4, cy + 6}, 3);
		g.setStroke(os);
	}

	// ------------------------------------------------------------------ helpers

	private static int hash(int seed, int i)
	{
		return (seed * 31 + i) * 0x9E3779B9;
	}

	private static double lerp(double from, double to, double t)
	{
		return from + (to - from) * t;
	}

	private static double clamp(double v, double lo, double hi)
	{
		return v < lo ? lo : (v > hi ? hi : v);
	}

	private static Color alpha(Color c, int a)
	{
		int clamped = a < 0 ? 0 : (a > 255 ? 255 : a);
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), clamped);
	}

	private static Color mix(Color a, Color b, float t)
	{
		return new Color(
			(int) (a.getRed() + (b.getRed() - a.getRed()) * t),
			(int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
			(int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}
}
