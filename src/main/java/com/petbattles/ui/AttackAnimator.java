package com.petbattles.ui;

import com.petbattles.engine.BattleEvent;
import com.petbattles.engine.MoveDef;
import com.petbattles.engine.MoveEffect;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/**
 * Maps the current battle event to sprite motion and overlay effects. Everything is
 * pure interpolation over the session's 0→1 animation progress — no state, no
 * allocation beyond loop locals — so it's safe to call every frame.
 */
public final class AttackAnimator
{
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
	 * Resolve a move's animation id: hand-authored value, else a category default —
	 * heal → sparkle, self-buff → grow, debuff/status → shake, attack → flash.
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
				return "grow";
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
				switch (animationId(move))
				{
					case "grow":
						// Swell up and settle back (Bulk Up / War Cry)
						return new Transform(0, 0, 1f + 0.35f * (float) Math.sin(Math.PI * progress));
					case "lunge":
					case "whip":
					{
						// Dart toward the opponent and back
						float f = 0.30f * (float) Math.sin(Math.PI * progress);
						int dx = (int) ((other.getCenterX() - self.getCenterX()) * f);
						int dy = (int) ((other.getCenterY() - self.getCenterY()) * f);
						return new Transform(dx, dy, 1f);
					}
					default:
						return Transform.IDENTITY;
				}
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
	 * Overlay-drawn effects for the MOVE_USED phase: projectiles in flight, a tinted
	 * flash on the defender, or a sparkle on the caster. Call after sprites are drawn.
	 */
	public static void drawEffects(Graphics2D g, BattleEvent event, MoveDef move, float progress,
		Rectangle attacker, Rectangle defender)
	{
		if (event == null || event.getType() != BattleEvent.Type.MOVE_USED || move == null)
		{
			return;
		}
		Color typeColor = new Color(move.getType().getColorRgb());
		switch (animationId(move))
		{
			case "projectile":
			case "ice_shard":
			{
				// A shard lerped from attacker to defender along progress
				double x = attacker.getCenterX() + (defender.getCenterX() - attacker.getCenterX()) * progress;
				double y = attacker.getCenterY() + (defender.getCenterY() - attacker.getCenterY()) * progress
					- 20 * Math.sin(Math.PI * progress);
				g.setColor(typeColor);
				g.fillOval((int) x - 5, (int) y - 5, 10, 10);
				g.setColor(new Color(255, 255, 255, 180));
				g.fillOval((int) x - 2, (int) y - 2, 4, 4);
				break;
			}
			case "flash":
			{
				int alpha = (int) (140 * Math.sin(Math.PI * progress));
				if (alpha > 0)
				{
					g.setColor(new Color(typeColor.getRed(), typeColor.getGreen(), typeColor.getBlue(), alpha));
					g.fillRoundRect(defender.x, defender.y, defender.width, defender.height, 12, 12);
				}
				break;
			}
			case "sparkle":
				ParticleBurst.render(g, attacker, progress, move.getId().hashCode(), ParticleBurst.SPARKLE);
				break;
			default:
				break;
		}
	}
}
