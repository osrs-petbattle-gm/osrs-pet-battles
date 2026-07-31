package com.petbattles.battle;

import com.petbattles.PetBattlesConfig;
import com.petbattles.engine.BattleEvent;
import com.petbattles.engine.MoveDef;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.SoundEffectID;
import net.runelite.api.SoundEffectVolume;

/**
 * Plays a matching in-game sound effect as each battle event is surfaced. Sounds are the game's
 * own effects, triggered by numeric id through {@link Client#playSoundEffect(int, int)} — nothing
 * is bundled or decoded, so there are no audio assets to ship. Playback is gated by a config
 * toggle and scaled by a config volume (a 0–100% slider, independent of the in-game sound slider).
 *
 * <p>The move-to-sound mapping is an explicit, auditable matrix keyed on a move's {@code animation}
 * family — the same key that drives its on-screen effect — so sight and sound always agree and a
 * whole family shares one cue. A small {@link #MOVE_OVERRIDES} table wins first for the handful of
 * moves that want an exact thematic sound (skilling pets swinging real tools, a harpoon spear, an
 * arcane bolt). Every real move resolves to a sound, enforced by {@code everyBundledMoveMakesASound}.
 *
 * <p>IDs marked "wiki" below come from the OSRS Wiki "List of sound IDs" and should be confirmed
 * in-game (Developer Tools → Sound Effect); IDs referenced through {@link SoundEffectID} are
 * RuneLite-verified constants. {@link #soundIdFor} is pure/static so it is unit-tested without a
 * live client.
 */
public class BattleSoundManager
{
	/** No sound for this event (also guards {@link Client#playSoundEffect} against bad ids). */
	static final int SILENT = -1;

	/** Thematic exact matches, applied before the animation matrix. Move id → sound id. */
	private static final Map<String, Integer> MOVE_OVERRIDES = new HashMap<>();

	/**
	 * The matrix: animation family → sound id. Single source of truth for which sound a move makes.
	 */
	private static final Map<String, Integer> ANIMATION_SOUNDS = new HashMap<>();

	/** Fallback for a move whose animation isn't in the matrix (real content never hits this). */
	private static final int DEFAULT_SOUND = SoundEffectID.ATTACK_HIT;

	static
	{
		MOVE_OVERRIDES.put("pickaxe_poke", SoundEffectID.MINING_TINK);
		MOVE_OVERRIDES.put("log_toss", SoundEffectID.TREE_CHOP);
		MOVE_OVERRIDES.put("timber", SoundEffectID.TREE_FALLING);
		MOVE_OVERRIDES.put("harpoon", 2487);      // spear / harpoon thrust (wiki)
		MOVE_OVERRIDES.put("arcane_blast", 174);  // magic bolt — else the bolt family reads as crossbow (wiki)

		// -- contact: melee, nature whips/fangs, undead bashes, demon claws, dragon tails --
		ANIMATION_SOUNDS.put("slash", 2515);      // claw / scratch (wiki)
		ANIMATION_SOUNDS.put("bite", 1933);       // bite / chomp (wiki)
		ANIMATION_SOUNDS.put("shockwave", SoundEffectID.ATTACK_HIT);
		ANIMATION_SOUNDS.put("spin", SoundEffectID.ATTACK_HIT);
		ANIMATION_SOUNDS.put("whip", SoundEffectID.ATTACK_HIT);
		ANIMATION_SOUNDS.put("lunge", 2487);      // spear poke (wiki)
		ANIMATION_SOUNDS.put("flash", SoundEffectID.ATTACK_HIT);
		// -- ranged throws / shots --
		ANIMATION_SOUNDS.put("dart", 2696);       // dart throw (wiki)
		ANIMATION_SOUNDS.put("bolt", 2695);       // crossbow (wiki)
		ANIMATION_SOUNDS.put("chin", 2707);       // thrown weapon (wiki)
		// -- magic --
		ANIMATION_SOUNDS.put("swirl", 220);       // wind strike (wiki)
		ANIMATION_SOUNDS.put("orb", 207);         // water blast (wiki)
		ANIMATION_SOUNDS.put("drain", 174);       // soul drain — magic bolt (wiki)
		ANIMATION_SOUNDS.put("projectile", 174);  // generic magic bolt fallback (wiki)
		// -- fire --
		ANIMATION_SOUNDS.put("fireball", 155);    // fire blast (wiki)
		ANIMATION_SOUNDS.put("breath", 585);      // dragonfire (wiki)
		ANIMATION_SOUNDS.put("explosion", 162);   // fire wave — big AoE boom (wiki)
		// -- ice --
		ANIMATION_SOUNDS.put("ice_shard", 170);   // ice burst (wiki)
		ANIMATION_SOUNDS.put("barrage", 168);     // ice barrage (wiki)
		ANIMATION_SOUNDS.put("blizzard", 169);    // ice blitz (wiki)
		// -- nature status inflicters --
		ANIMATION_SOUNDS.put("roots", 151);       // entangle (wiki)
		ANIMATION_SOUNDS.put("cloud", 2408);      // poison gas (wiki)
		// -- self buffs / heals / enemy debuffs --
		ANIMATION_SOUNDS.put("sparkle", SoundEffectID.PRAYER_ACTIVATE_RAPID_HEAL);
		ANIMATION_SOUNDS.put("buff_aura", SoundEffectID.PRAYER_ACTIVATE_ULTIMATE_STRENGTH);
		ANIMATION_SOUNDS.put("grow", SoundEffectID.PRAYER_ACTIVATE_ULTIMATE_STRENGTH);
		ANIMATION_SOUNDS.put("wail", SoundEffectID.PRAYER_DEACTIVE_VWOOP);
		// -- skilling wood --
		ANIMATION_SOUNDS.put("spinning_log", SoundEffectID.TREE_CHOP);
	}

	private final Client client;
	private final PetBattlesConfig config;

	public BattleSoundManager(Client client, PetBattlesConfig config)
	{
		this.client = client;
		this.config = config;
	}

	/**
	 * Play the cue for this event, if any and if the config toggle is on. Must be called on the
	 * client thread (as the battle sequence already is).
	 */
	public void play(BattleEvent event, MoveDef move)
	{
		if (!config.battleSoundEffects())
		{
			return;
		}
		int id = soundIdFor(event, move);
		if (id <= 0)
		{
			return;
		}
		int volume = scaleVolume(config.battleSoundVolume());
		if (volume > 0)
		{
			client.playSoundEffect(id, volume);
		}
	}

	/** Map a 0–100 config percentage to the game's 0–{@value SoundEffectVolume#HIGH} volume scale. */
	static int scaleVolume(int percent)
	{
		int clamped = Math.max(0, Math.min(100, percent));
		return Math.round(clamped * SoundEffectVolume.HIGH / 100f);
	}

	/** The sound effect id for an event/move pair, or {@link #SILENT} for none. */
	static int soundIdFor(BattleEvent event, MoveDef move)
	{
		if (event == null)
		{
			return SILENT;
		}
		switch (event.getType())
		{
			case MOVE_USED:
				return moveSound(move);
			case MISSED:
				// The classic magic "splash" — a good generic whiff cue for any missed move.
				return SoundEffectID.MAGIC_SPLASH_BOING;
			default:
				return SILENT;
		}
	}

	private static int moveSound(MoveDef move)
	{
		if (move == null)
		{
			return SILENT;
		}
		Integer override = MOVE_OVERRIDES.get(move.getId());
		if (override != null)
		{
			return override;
		}
		Integer byAnimation = move.getAnimation() == null ? null : ANIMATION_SOUNDS.get(move.getAnimation());
		return byAnimation != null ? byAnimation : DEFAULT_SOUND;
	}
}
