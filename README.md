# Nebula Dominion

An endless space RTS built with Java and libGDX. All graphics are drawn shapes, so there are no asset files.

## Run

- Double-click `run.bat`, or run `gradlew.bat lwjgl3:run`.
- `gradlew.bat lwjgl3:dist` builds a standalone `lwjgl3/build/libs/NebulaDominion.jar`. Run it with `java -jar NebulaDominion.jar`.
- `gradlew.bat test` runs headless simulation tests, including a 40-minute game and a save/load round trip.

Requires a JDK (17 or newer; developed on 25). The first build downloads Gradle and libGDX.

## The game

- **Mothership**: builds Harvesters. If it is destroyed, you lose.
- **Economy**: Harvesters gather **energy** from stars and **metal** from asteroids and barren planets. Asteroids run out, and new fields drift in over time. Every colony also trickles in energy and metal on its own, much more slowly than harvesting.
- **Harvesters also build** (select one, then press a number key):

  | Key | Structure | Where |
  |---|---|---|
  | 1 | **Ship Factory**: builds every warship and Colony Ship. Build as many as you like. Can move, as slowly as the Mothership. | Click open space |
  | 2 | **Research Hub** (only one). Can move, as slowly as the Mothership. | Click open space |
  | 3 | **Turret Upgrade**: +2 defense turrets | Click one of your colonies |
  | 4 | **Shield Upgrade**: +400 planetary shield and faster recharge | Click one of your colonies |
  | 5 | **Mender**: a crawling mobile repair station (6 housing) that heals every ally around it | Where the harvester is |

  | 6 | **Solar Collector**: produces 6 energy/s by itself, max 2 per star | Click a star |
  | 7 | **Asteroid Miner**: mines 5 metal/s by itself, moving on to the next asteroid within 450 as each runs out (4/s forever on a metal planet); one per field | Click an asteroid or metal-rich planet |

  Collectors and miners need no hauling and out-farm a harvester (about 2-3/s), but pirates like to raid them.

  Each colony has **5 upgrade points**. Every Turret or Shield Upgrade spends one, so split them however you like (for example 5 turrets, 5 shields, or 3 + 2).

  The cost is paid when you give the order and refunded if the harvester is stopped or destroyed first.
- **Rally flag**: press **F** and click to plant it. New warships from every factory gather there. **Shift+F** removes it.
- **Housing**: your fleet size is capped by housing.
  - **Habitable worlds** (undefended): send a Colony Ship for +10 housing.
  - **Fortified worlds** (defended): native turrets and guards respawn while the turrets stand. Destroy the turrets, then colonize for +25 housing.
- **Colony boost**: ships fly 50% faster when heading to one of your own colonies.
- **Research Hub**: researches 26 techs across five branches (military, economy, colony, mothership, hub tiers) and builds **Surveyors**.
- **Surveyors** land on uninhabitable planets and build an outpost that extracts that planet's rare item:

  | Planet | Rare item |
  |---|---|
  | Volcanic | Pyrocrystal |
  | Ice | Cryo Core |
  | Gas giant | Plasma Essence |
  | Crystal | Void Shard |
  | Ancient Ruins (guarded) | Relic Fragment |

  Rare items pay for upgrades and for the rare ships.
- **Warships**: Lancers beat Longbows, Longbows beat Bulwarks, and Bulwarks beat Lancers.

  | Ship | Role | Notes |
  |---|---|---|
  | Wisp | Scout | Q: Sensor Ping; detects cloaked ships |
  | Lancer | Infantry | Squad of 3 |
  | Bulwark | Tank | Q: Taunt |
  | Longbow | Sniper | Reload 5.5 s; revealed to its victims for 4 s each time it fires |
  | Mender | Mobile repair station | Built by Harvesters; crawls (speed 12); 6 housing; heals everything within 400 |
  | Hive | Carrier | Needs research |
  | Phantom | Cloaked sniper | Rare; needs research and rare items; reload 6 s; firing reveals it for 4 s, even through cloak |
  | Aegis | Shield-bubble tank | Rare; needs research and rare items |
  | Leviathan | Massive capital ship | Rare; needs research and rare items; Q: Nova Cannon |

- **Opponents**: 1 to 3 AI rival empires that expand, research and attack, plus native garrisons and pirate raids that grow forever.
- **Difficulty** (main menu, Up / Down):

  | | Easy | Normal | Hard |
  |---|---|---|---|
  | Rivals may attack empires from | 30 min | 17 min | 12 min |
  | Rival income | 70% | 100% | 125% |
  | Army size before a rival attacks | much bigger | a bit bigger | smaller |
  | Pirate waves | 65% | 90% | 120% |
  | Warning before a rival fleet moves on you | 20 s | 20 s | none (still announced) |
  | Other | You start with +400/+400 and 3 extra Lancers; rivals hit your colonies before your Mothership | | Rivals focus fire |

- **Auto-defense** (on by default, toggle with **G** or the top-bar button):
  - Idle warships near a base attack enemies that come within range of your colonies, factories, hub or Mothership, sending about twice the threat's strength. They return to their post afterwards.
  - Harvesters flee from raiders and go back to the same resource once it's safe.
  - Ships below 30% HP retreat to the nearest Mender and come back once repaired.
  - Your ships focus fire on targets allies are already shooting and on wounded enemies.
  - Ships you are giving orders to are never overridden.

## Controls

| Input | Action |
|---|---|
| Left-click / drag | Select (double-click selects all of a type on screen) |
| Right-click | Move / attack / harvest / colonize / survey (depends on the target) |
| Ctrl + Right-click | Attack-move |
| F / Shift+F | Place / remove the rally flag |
| G | Toggle auto-defense |
| Right-click with a Factory or Hub selected | Move it (as slow as the Mothership) |
| Shift + right-click with a Factory selected | Set that factory's own rally point (overrides the flag) |
| 1-9, 0, -, = | Build from the selected Mothership, Ship Factory, Hub or Harvester |
| Shift + click while placing | Place more structures with the other selected harvesters |
| H / F2 / B / I | Select Mothership / cycle Ship Factories / Research Hub / idle harvesters |
| Ctrl+A | Select all warships |
| Q | Use the ability of the selected ships |
| X | Stop |
| C | Center the camera on the selection |
| WASD, arrows, screen edge, middle-drag | Pan |
| Mouse wheel | Zoom |
| Minimap click | Jump the camera there |
| Space | Pause |
| [ ] | Game speed |
| F5 / F9 | Save / load (`nebula_dominion_save.json`) |
| Esc | Menu |
| F1 | Help |

## Code map (`core/src/main/java/spacerts`)

| Folder | Contents |
|---|---|
| `units/` | `ShipClass` (stats table for every unit), `Unit`, `Spec`, orders |
| `world/` | `World` (tick order), `GalaxyGenerator`, `Body`, `Faction`, fog of war |
| `systems/` | Behavior, combat, movement, economy, production and research, `ThreatDirector` (pirates) |
| `research/` | `Tech` tree, `RareItem` |
| `ai/` | `RivalAI` |
| `ui/` | Renderer and HUD |
| `input/` | Input handling |
| `save/` | Save and load |