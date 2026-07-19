# Pet Battles

A RuneLite plugin that turns your Old School RuneScape collection log pets into a
Pokemon-style battle team. Collect pets in-game to unlock them, level them up by
playing the game with them out, and pit them against themed AI trainers.

## Features

- **Real collection log sync** — open your Collection Log in-game (any pets page)
  and every pet you actually own joins your roster. New drops unlock live via the
  collection log chat message, and any pet seen following you is unlocked too.
- **10 OSRS-flavored types** — the combat triangle (Melee > Ranged > Magic > Melee)
  plus Fire, Ice, Nature, Undead, Demon, Dragon, and Skilling. Ranged beats Dragon
  (dragon hunter crossbow), Magic beats Demon (demonbane), Fire cremates Undead,
  Ice freezes Dragons, and everything bullies Skilling pets — except Nature,
  which they chop down.
- **Leveling with your gameplay** — your active follower pet gains XP whenever you
  kill an NPC, with a 3x *home-turf bonus* for killing its own boss (take Vet'ion Jr.
  to fight Vet'ion). Winning trainer battles also grants XP.
- **Learnsets and movesets** — pets learn new moves at level thresholds and can
  equip up to 4 at a time from the side panel.
- **Hidden easter egg moves** — certain actions with the right pet following unlock
  secret moves. Try chopping a tree with your Beaver out... there are more.
- **Interactive battle overlay** — a Pokemon-style battle scene drawn over the game:
  HP bars, type badges, a battle log, and clickable move buttons. Battles run
  against 8 themed trainers from Party Pete (easy) to the Wise Old Man (endgame).

## Getting started

1. Enable the plugin and log in.
2. Open your **Collection Log** and view a pets page to sync your roster
   (the "Other > All Pets" page covers everything at once).
3. In the Pet Battles side panel, add up to 3 pets to your team.
4. Pick a trainer and hit **Fight!**

Progress is saved per RS account through RuneLite's config service.

Note: the "new collection log addition" live unlock requires the in-game setting
*Collection log - new addition notification* to be enabled (a chat message).

## Development

Standard RuneLite external plugin layout:

```
gradlew.bat build     # compile + run all unit tests
gradlew.bat test      # engine and content validation tests
gradlew.bat run       # launch RuneLite in developer mode with the plugin loaded
```

The battle engine (`com.petbattles.engine`) is pure Java with no RuneLite
dependencies and all randomness injected — fully deterministic under test.
Opponents implement `OpponentController`, the seam where Party-service PvP can
be added later. Content (species, moves, trainers, type chart) lives in JSON
resources under `src/main/resources/com/petbattles/data/` and is validated by
`ContentValidationTest`.

A hidden dev config key `petbattles.devUnlockAll` (set via the RuneLite config
export or temporarily unhide it) treats every pet as owned for testing.
