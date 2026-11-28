# Nebula Dominion

An endless space RTS built with Java and libGDX. All graphics are drawn shapes, so there are no asset files.

## Run

- Double-click `run.bat`, or run `gradlew.bat lwjgl3:run`.
- `gradlew.bat lwjgl3:dist` builds a standalone `lwjgl3/build/libs/NebulaDominion.jar`. Run it with `java -jar NebulaDominion.jar`.
- `gradlew.bat test` runs headless simulation tests, including a 40-minute game and a save/load round trip.
- `gradlew.bat :core:stressTest` measures how many ships the game logic handles in its 33 ms per-update budget (about 10,000 units in one big battle, 19,000 spread out, on the dev PC).

Requires a JDK (17 or newer; developed on 25). The first build downloads Gradle and libGDX.

## The game

- **Mothership**: 12,000 hull. Builds Harvesters. If it is destroyed, you lose. At tier II it gains **Warp Jump** (L): jump up to 1000 to a point you click, then fly at scout speed for 20 s. 20-minute cooldown.
- **Economy**: Harvesters gather **energy** from stars and **metal** from asteroids, barren planets and wrecks. **Stars hold a massive but finite energy deposit (300,000–600,000)**: they shrink as harvesters and Solar Collectors drain them and burn out when empty. Asteroids run out, and new fields drift in over time. Every colony also trickles in energy and metal on its own, much more slowly than harvesting.
- **Richer asteroids**: every asteroid holds 3x the metal (1,500–3,600 each).
- **Giant asteroids** hold **150,000–300,000 metal** each: huge, but they do run out. They have craters and glinting ore veins and are named on the map. Classic maps have **four, one near the middle of each edge**, and about a third of the Hive's Asteroid Belts start with one.
- **Asteroid clumps** hold twice as many rocks as before.
- **Asteroid showers**: once a minute, every system rolls for a fresh field to drift in. The chance is **1 in 60** per hex, or **1 in 20** in Asteroid Belts and on the classic map. A shower brings a giant 6% of the time (12% in belts). Showers stop in systems that are already full (60 asteroids, or 120 in belts). You're told about showers in explored or owned space.
- **Mining Barges** (built at a Ship Station) are tough, slow haulers. They hold 150 cargo (a Harvester holds 30) and mine 1.5x faster, but they can't build.
- **Wreck salvage**: destroyed ships leave wrecks worth **half** their metal cost (pirates leave fixed amounts). Salvage pays metal only. Harvesters and barges collect it. Wrecks drift away after 5 minutes, so battlefields are worth holding.
- **Scarce finds** (shown in the top bar once you find one) turn up at low odds:

  | Item | Found by |
  |---|---|
  | **Dark Matter** | Harvesters unloading asteroid metal (2% per load); Asteroid Miners (4% per minute) |
  | **Star Essence** | Solar Collectors (4% per minute); harvesters unloading starlight (1%) |
  | **Neutronium** | Salvaging a wreck to the end (8%); pirate stronghold wrecks always hold 2 |
  | **Exotic Matter** | Ruins outposts (12% per relic); destroyed pirate Dreadnoughts (25%) |

  They pay for tier III upgrades, the tier III ships, the Ion Cannon, the Ascension Gate and three late techs: Neutronium Plating (+20% hull), Hyperdrive Lattice (+10% speed, instant gate recharge) and Dark Matter Reactors (+30% build speed).
- **Tier upgrades**: select the Mothership or a Ship Station and press **U** (or use its upgrade button).
  - **Mothership II / III** (1500/1500 + rare items, then 4000/4000 + scarce finds): +30% hull and shield and an extra gun per tier, and they unlock more Harvester buildings. **Tier II also unlocks Warp Jump, a second Research Hub (the two labs research different techs at the same time) and 2 more Ship Stations (4 in all).**
  - **Ship Station II / III**, per station: each tier builds **30% faster**. Tier II unlocks Corsair, Tempest, Pyre, Aegis and Phantom; tier III unlocks Sovereign and Leviathan.
  - **Research Hub**: the Hub Expansion II/III techs are the lab upgrades. Each tier researches **25% faster**.
  - **Stellar Refinement** (endgame, at the Mothership; needs Mothership III and Research Hub III): a stacking upgrade, **+2% to all mining, harvesting and energy collection per level, up to 999 levels**. Each level takes 60 s and costs **2000E / 2000M × (1 + ln(1 + level))**: level 10 costs 3.4x, level 100 costs 5.6x, and level 999 costs 7.9x. Only one level can be queued at a time.
- **Harvesters also build** (select one, then press a number key):

  | Key | Structure | Where |
  |---|---|---|
  | 1 | **Ship Station**: builds every warship, Colony Ship and Mining Barge. **At most 2.** Mobile (half the Mothership's speed). | Click open space |
  | 2 | **Research Hub** (only one). Mobile (half the Mothership's speed). | Click open space |
  | 3 | **Repair Station** (800E / 750M): repairs every ally within 400; damaged ships retreat to it. No housing. Mobile (half the Mothership's speed). | Click open space |
  | 4 | **Solar Collector** (4500 hull): produces 6 energy/s by itself, max 2 per star | Click a star |
  | 5 | **Asteroid Miner** (5000 hull): mines 5 metal/s by itself, moving on to the next asteroid within 450 as each runs out, and once the whole field is dry it slowly crawls (speed 15) to the nearest asteroid clump in its system (4/s forever on a metal planet); one per field | Click an asteroid or metal-rich planet |
  | 6 | **Command Center** (one per empire): unlocks battle tactics and their upgrades, picks the active tactic. Mobile (half the Mothership's speed). | Click open space |
  | 7 | **Claim Beacon** (Hive only): claims the hex it stands in | Click open space |
  | 8 | **Defense Platform** (Mothership II): standalone heavy turret | Click open space |
  | 9 | **Ore Refinery** (Mothership II): drop-off point; metal unloaded within 1500 is worth 25% more | Click open space |
  | 0 | **Sensor Array** (Mothership II): 2500 vision, detects cloaked ships | Click open space |
  | - | **Ion Cannon** (Mothership III): range-1600 siege battery, 400 armor-piercing damage with splash | Click open space |
  | = | **Ascension Gate** (Hive only, Mothership III): the endgame wonder | The conquered Citadel hex |

  Collectors and miners need no hauling and out-farm a harvester (about 2-3/s), but pirates like to raid them.

  The cost is paid when you give the order and refunded if the harvester is stopped or destroyed first.
- **Planet upgrades**: click one of your colonies, then press **1** (Turret Upgrade: +2 defense turrets) or **2** (Shield Upgrade: +2000 planetary shield and faster recharge), or use the buttons. The nearest free Harvester flies over, builds it, and goes back to harvesting. Each colony has **5 upgrade points** to split however you like (for example 5 turrets, 5 shields, or 3 + 2).
- **Rally flag**: press **F** and click to plant it. New warships from every station gather there. **Shift+F** removes it.
- **Stations and Rally Stations**: Ship Stations, the Research Hub and Repair Stations are the mobile **stations**. On their own they crawl at **half the Mothership's speed** (14). Press **V** (or the **Rally Stations** button on the Mothership's panel) and every station lines up in **hex rings around the Mothership**, using the same layout as the Hive galaxy: 6 in the first ring, 12 in the second, and so on. Rallied stations move **as fast as the Mothership**, boosts and warp included, and follow it through jump gates. Move orders to rallied stations are ignored. Press V again and they stop where they are and go back to crawling.
- **Housing**: your fleet size is capped by housing.
  - **Mothership**: 35 housing.
  - **Habitable worlds** (undefended): send a Colony Ship for +30 housing.
  - **Fortified worlds** (defended): native turrets and guards respawn while the turrets stand. **They hold fire until attacked**, so fleets can fly past; once an empire hits them, they fight that empire until 30 s after its last hit. Destroy the turrets, then colonize for +65 housing.
  - Every new colony comes with a free Harvester that starts working straight away.
  - **Buoys**: every 5 minutes a colony launches a ring of 5 vision buoys, each ring 250 further out (250, 500 ... 1250 from the planet), up to 5 rings. Buoys can't be targeted. They light up the area around them for the colony's owner and allies, and vanish if the colony is lost.
- **Travel boosts** (the highest one applies; they don't stack):
  - 2x speed when heading to one of your own colonies.
  - 1.75x when heading to any other planet.
  - 1.75x while charging into an attack, until the ship is in weapon range.
- **Long fights**: every ship, structure, colony and turret has 5x the HP and shields (weapons are unchanged), so battles are drawn-out fights where you wear the enemy down. Shields recover at **half speed**, but **colonies carry 5x the shield** (and recharge 5x faster to match), so conquered planets are real fortresses.
- **Housing per ship**: every ship costs 1 more housing than before except infantry (Lancers stay at 1). For example, Harvester 2, Bulwark 6, Longbow 3, Phantom 4, Leviathan 21.
- **Auto-retreat** (per ship, toggle with **R**): a warship below 25% hull pulls back to the nearest Repair Station or base (Ship Station or Mothership), repairs to 90%, then attack-moves back to where it left the fight. Bots don't auto-retreat: their ships fight to the end.
- **Base repair**: any ship near your Ship Station or Mothership that has been out of combat for 6 s repairs 2% of its hull per second. **Colonies no longer repair ships.**
- **Repairs cost metal**: every repair (base repair, Repair Stations, Mothership self-repair) is paid automatically by the ship's owner. A full repair from zero hull costs **half the ship's metal price**; the Mothership is priced as if it cost 1000 metal. With no metal, repairs stop. Colonies, turrets and fixed structures still repair for free. The upkeep tooltip in the top bar shows your current repair spending.
- **Upkeep** (Warcraft 3 style): a big army costs energy every second. Each ship counts its housing and each station counts **4**:

  | Upkeep | Energy per second |
  |---|---|
  | 0-40 | None |
  | 41-100 | Low: 0.03 per point (for example 60 points = 1.8/s) |
  | 101+ | High: 0.06 per point (for example 150 points = 9/s) |

  Energy never goes below zero. The top bar shows your tier and the drain. Bots pay it too.
- **Research Hub**: researches 38 techs across five branches (military, economy, colony, mothership, hub tiers) and builds **Surveyors**.
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
- **Energy prices**: everything you buy (ships, structures, upgrades, research) costs **50 more energy** than it used to.
- **Logarithmic warship pricing**: each warship type gets pricier the more of it you already have (alive or queued, counted per squad): **price = base × (1 + 0.35 × ln(1 + copies))**. That's +24% for your 2nd, +84% with 10 in service, and +138% with 50. **Bulwarks, Longbows and Phantoms climb twice as fast (0.7)**: +49% for the 2nd, +168% with 10. A different type starts again at base price, and losses bring the price back down. Harvesters, barges, colony ships, surveyors, upgrades and structures stay at base price, and rare-item costs don't scale. Cancelling refunds what you actually paid. Salvage stays at 50% of the base metal cost.

  | Ship | Role | Notes |
  |---|---|---|
  | Wisp | Scout | Q: Sensor Ping; E: Ghost Drive (cloaked and 60% faster for 6 s, cannot fire; 30 s cooldown); detects cloaked ships |
  | Lancer | Infantry | Squad of 3; 330 hull and 14 damage per ship |
  | Bulwark | Tank | T: Taunt; as fast as Lancers (165) |
  | Longbow | Sniper | Range 850; as fast as Lancers (165); reload 5.5 s; revealed to its victims for 4 s each time it fires; Z: Evasive Dash (leaps 400 away from the nearest threat, or straight back; 15 s cooldown) |
  | Mining Barge | Hauler | Not a warship; 150 cargo, 1.5x mining speed, salvages wrecks; 4 housing |
  | Patrol Ship | Patrol | Mothership II (built at the Mothership); 1 housing; flies a circuit of every colony and back to the Mothership on its own; faster than infantry but with lighter guns; splits into 2 Patrol Skiffs when destroyed |
  | Pyre | Incendiary frigate | Station II; 450E / 450M, 7 housing. Short-range flamer (200) with splash (90); every hit sets ships burning for 4 s (10 damage/s, armor doesn't help); **4x damage to Hive drones and Broodlings** |
  | Hive | Carrier | Needs research; with **Brood Swarm** research (Military, tier 2, after Carrier Doctrine) a destroyed Hive releases **5 Broodlings**, fast fighters that keep fighting |
  | Corsair | Strike frigate pair | Station II; very fast, bonus vs light hulls |
  | Tempest | Missile cruiser | Station II; range 700, big splash: breaks up swarms |
  | Phantom | Cloaked sniper | Range 950; as fast as Lancers (165); Station II; rare; needs research and rare items; reload 6 s; firing reveals it for 4 s, even through cloak; Z: Evasive Dash |
  | Aegis | Shield-bubble tank | Station II; rare; needs research and rare items |
  | Sovereign | Battleship | Station III; scarce finds; twin batteries; O: Overcharge (double rate of fire for 6 s) |
  | Defender | Guardian | Mothership II, built at the Mothership; max 4; 2500E / 3000M + rare items, 180 s build, 14 housing. A huge armored escort (9000 hull, 3500 shield) that **orbits the Mothership** outside the station rings and shoots anything within 560. It never leaves its post: move orders are ignored, and right-clicking an enemy near the Mothership picks its target. T: Taunt |
  | Leviathan | Massive capital ship | Station III; rare; needs research and rare items; N: Nova Cannon |

- **Main menu**: Campaign · Multiplayer · Load Game · Load Autosave · Quit. Use the mouse, or the arrow keys with Enter. Left / Right change a setting and Esc goes back.
- **Multiplayer** (classic galaxy, up to 4 empires): host a game and fill the four seats with LAN players, bots or nobody. Native garrisons and pirate raids that grow forever join every game.
  - **Loading:** after Start, every machine shows a loading screen while it builds the galaxy and warms up, then waits until every player is ready, so the game starts smoothly for everyone at once. Anyone still loading after 60 s is dropped and a bot takes over their empire.
  - **Joining:** other players open **Multiplayer → Join LAN Game**. Games on the network appear in the list on their own, or type the host's **room code**: the lobby shows it in big letters (for example `K7Q-4MXV`). The code is the host's network address in a short, typo-checked form, so it works on the same network, or over a shared VPN such as Radmin, ZeroTier or Hamachi (the lobby lists a code for each network). The host's IP address still works too. The game uses TCP port 47778 and UDP port 47777 for discovery.
  - **To play alone against bots,** host, add bots and start. Nobody needs to join, and the game runs offline with saves enabled.
  - **Teams:** each seat picks No team or Team 1-4. Allies share vision, never shoot each other, and win together. **The match ends** when every enemy empire is eliminated (its Mothership destroyed): the game freezes on a VICTORY screen with **match stats** for every empire (result, kills, structures destroyed, ships built and lost, damage, energy and metal gathered, spent, research, colonies, peak army; the best in each column is highlighted). The defeat and Ascension screens show the same table. Press Enter for the main menu, or C to keep playing (single player). Players choose their own team and color; the host sets the bots'.
  - **Bots** each have their own difficulty (see below). They **sit still for the first 8 minutes**. Their attack grace period (first strike time) still counts from the start of the game.
  - **LAN play** is deterministic lockstep: only orders travel over the network. If someone lags, the game shows "Waiting for players…". A player who leaves is replaced by a Normal bot. Pause and speed are shared. Saving is off in games with more than one human.
- **Campaign: the Hive** (formerly the pirates-only mode): a long campaign with no rival empires, meant to be played over many sessions. Instead:
  - **The map** is a honeycomb of **91 hexagonal systems** (centre + 5 rings), joined by **jump gates** on every shared edge. You start in the **bottom** hex.
  - **EVE-scale space:** each hex is about 21,600 across (hex radius 10,800), and the whole hive is about 200,000 × 230,000. Each hex holds about 3x the stars, planets, asteroid fields and pirate patrols, spread out into clusters. Zoom out up to 24x to see a whole hex.
  - **Warp cruise:** a ship heading more than 3,000 away, with no target and not hit in the last 5 s, spools its warp drive for 2 s and then cruises at **4x speed**, leaving a bright streak. It drops out of warp 1,200 from its goal, or as soon as it fights, gets hit or is slowed. Every side uses it, including pirate raids and harvesters on long hauls.
  - **Conquest, hex by hex:** every hex's **danger** is its jump distance from home (0 to 10, the Pirate Citadel at the top). Patrol fleets hold every hex: 3 Raiders near home, heavy fleets with Dreadnoughts near the top.
    - Build a **Claim Beacon** in a hex to own it: +0.5 energy and metal per second, wide vision, and no pirate stronghold can rise in or next to it.
    - On the galaxy map, grey hexes are unclaimed, blue are yours, orange are contested and red are pirate.
    - Pirate raids scale with your **frontier** (the most dangerous hex you hold), not the clock, and they hit your frontier hexes hardest.
  - **Endgame:** destroy the **Pirate Citadel** (3x a stronghold, huge garrison), claim its hex, and at Mothership III build the **Ascension Gate** (6000/6000 + scarce finds, 8 minutes). While it's under construction, every stronghold sends a surge at it every minute. Finish it and you win; you can keep playing afterwards.
  - **Autosave** every 5 game minutes to `nebula_dominion_autosave.json`; load it with **Load Autosave** on the main menu.
  - **Gates:** right-click a gate to fly through it, or double-click it to look through. Orders to another system route through the gates automatically.
  - **Galaxy map:** **Tab** opens it over the background. Click a hex to view it, or right-click to send your selected ships there.
  - **Varied systems:** Stellar Nurseries (many stars), Asteroid Belts, Garden Clusters (many habitable worlds), Fortress Worlds, Rare Frontiers, Ancient Ruins, Nebulae (vision reduced), Dead Voids (old wrecks to salvage) and Pirate Havens.
  - **Pirate strongholds:** each keeps a garrison on station that it slowly rebuilds, and raids launch from them and travel through the gates. Destroying one weakens raids and leaves a 1500-metal wreck, but new strongholds rise in far-off systems, so the game is endless.
  - **J** jumps the camera to the latest alert.
  - **Background:** a slowly spinning spiral galaxy of glowing stars.
- **Difficulty**: set per bot in the lobby. The Campaign difficulty and the lobby's *Pirate threat* set the pirate waves.

  | | Easy | Normal | Hard | Insane |
  |---|---|---|---|---|
  | Bot warship cap (regardless of housing) | 64 | 120 | 200 | unlimited |
  | Time between bot decisions | 20 s | 10 s | 5 s | 1.5 s |
  | Colonies a bot may hold | 3 at first, +2 every 15 min (max 14) | same | same | same |
  | Bots may attack empires from | 30 min | 17 min | 12 min | 10 min |
  | Bot income | 70% | 100% | 125% | 150% |
  | Army size before a bot attacks | much bigger | a bit bigger | smaller | smallest |
  | Pirate waves | 65% | 90% | 120% | 150% |
  | Warning before a bot fleet moves on you | 20 s | 20 s | none (still announced) | none (still announced) |
  | Other | Humans start with +400/+400 and 3 extra Lancers; bots hit your colonies before your Mothership | | Bots focus fire; they build a Command Center and attack as a Bulldozer | Bots focus fire; they build a Command Center and attack as a Bulldozer |

- **Auto-defense** (on by default, toggle with **G** or the top-bar button):
  - Idle warships near a base attack enemies that come within range of your colonies, factories, hub or Mothership, sending about twice the threat's strength. They return to their post afterwards.
  - Harvesters flee from raiders and go back to the same resource once it's safe.
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
| Y | Toggle the active tactic: attack orders form the selected ships up, march and fight together (needs a Command Center) |
| K | Cycle the targeting mode of the selected warships: Nearest, Farthest, Most health, Least health, Random |
| V | Toggle Rally Stations (stations form hex rings around the Mothership and move with it) |
| R | Toggle auto-retreat for the selected warships |
| Click a colony, then 1 / 2 | Turret / Shield Upgrade (the nearest Harvester builds it) |
| Right-click with a station selected | Move it (half the Mothership's speed; ignored while rallied) |
| Shift + right-click with a Ship Station selected | Set that station's own rally point (overrides the flag) |
| 1-9, 0, -, = | Build from the selected Mothership, Ship Station, Hub or Harvester |
| Shift + click while placing | Place more structures with the other selected harvesters |
| H / F2 / B / I | Select Mothership / cycle Ship Stations / Research Hub / idle harvesters |
| Ctrl+A | Select all warships (not Patrol Ships, which fly their own circuit) |
| Q / E / T / Z / N / O / L | Skills, each on its own key: Sensor Ping, Ghost Drive, Taunt, Evasive Dash, Nova Cannon, Overcharge, Warp Jump (then click where to jump) (or click the skill buttons in the bottom panel) |
| X | Stop |
| Delete | Destroy the selected ships / structures for 25% of their price back (with only the Mothership selected: Surrender, after an "Are you sure?" popup) |
| / (Shift+/ for team) | Chat: type, Enter sends, Esc cancels; start with `/t ` to send to your team only. Works in LAN games and while eliminated |
| C | Center the camera on the selection |
| WASD, arrows, screen edges (also with the cursor on the title bar or just below the window), middle-drag | Pan |
| Mouse wheel | Zoom |
| Minimap click | Jump the camera there |
| Space | Jump the camera to the selected ships; hold it to lock on and follow them |
| P / Pause | Pause |
| [ ] | Game speed |
| F5 / F9 | Save / load (`nebula_dominion_save.json`) |
| Esc | Menu |
| F1 | Tutorial (paginated: arrows, A / D or the Back / Next buttons) |

## Command Center and battle tactics

Build a **Command Center** with a Harvester (key **6**, one per empire). It's a station: it crawls at half the Mothership's speed and joins the rally (V). Its build list unlocks **tactics** and **tactic upgrades**. Select it to pick the **active tactic**.

Press **Y** (or the **Tactic** button on top of the bottom bar) to switch tactics ON. While ON, an **attack** order (Ctrl+right-click, or right-click an enemy) with the selected ships:
1. **forms them up** in the tactic's formation,
2. **marches** them together: once formed, every ship moves at **Lancer speed**,
3. **fights** with the tactic's behavior once enemies are close or someone is hit.

Only the selected ships take part. Ships the tactic has no place for simply attack-move to the target, and so does everyone without a living Command Center. Giving a ship any other order takes it out of its formation.

| Role | Ships |
|---|---|
| Tanks | Bulwark, Aegis, Sovereign, Leviathan |
| Infantry | Lancer, Corsair, Patrol Ship / Skiff, Broodling, Wisp |
| Support | Hive |
| Snipers | Longbow, Phantom, Tempest |

| Tactic | Formation | In the fight |
|---|---|---|
| **Bulldozer** (400E/400M) | The infantry is split evenly across the tanks, and each group circles its own tank; support behind, snipers at the back | The blob pushes on; each group stays around its tank (and moves to the nearest tank if its own dies); **snipers shoot whatever is closest to the tanks** |
| **Pincer** (600E/600M + 1 Plasma) | Tanks in front, infantry wings to the sides and a little back, support behind the tanks, snipers at the back | Once a tank drops below 85% hull, **the wings close in around the enemy**; **snipers switch between the nearest and the farthest target every 3 shots** |
| **Flank** (600E/600M + 1 Void) | Tanks + half the infantry + half the snipers in front, a 300 gap, then the other halves | **Hurt tanks Taunt, and again every time it recharges**; the rear half **swings round in two arcs, left and right, to 900 past the tanks** to catch the enemy's snipers |
| **Focus** (600E/600M + 1 Pyro) | Infantry in front, then tanks, then snipers (support just attack-moves) | **Snipers kill hive drones first, then the biggest hull**, with **+50% damage on tanks and Hives** |

| Upgrade | For | Effect |
|---|---|---|
| Martyr Charge | Flank | An infantry ship about to die with an enemy sniper within its range + 100 charges it at 3x speed and explodes, **stunning the sniper and every enemy within 50 for 0.5 s** |
| Crippling Line | Pincer | Pincer tanks slow enemies that turn and run by 5% for 3 s |
| Ramming Plates | Bulldozer | Bulldozer tanks creep straight into the enemy; bumping an enemy ship deals massive damage (once a second per ship) |
| Shield Wall | Bulldozer | Bulldozer tanks take 40% less damage from snipers, and their Taunt reaches 1000 |
| Replenish | all | Ships lost from a formation are re-queued at your Ship Stations at normal price (a squad once all of it is lost) and sent to the attack destination |

**Targeting modes** (every warship, always available): **K** or the "Target:" button in the bottom panel cycles the selected ships between **Nearest** (default), **Farthest**, **Most health**, **Least health** and **Random**. Taunts still win. Tactic sniper rules override the mode during a tactical fight.

## Cheats

The Warcraft III cheat codes, adapted to space. Open the chat with **/** and type a code; capitals and spaces don't matter, and a code is never posted as chat. Cheats work only when no other human is in the game: Campaign, or Multiplayer against bots.

- **Unlock first**: cheats are locked until you type **`ThereIsNoCowLevel`**. Typing it again locks them and switches every cheat off.
- **`cheatlist`** (or `/cheats`): lists every code in your chat, with ON/OFF for the toggles. Only you see it. Page Up / Page Down scroll the chat history.
- Using any cheat puts a red **CHEATS** tag in the top bar for the rest of the game.

| Code | In Warcraft III | Here |
|---|---|---|
| WhosYourDaddy | God mode | Your units take no damage and kill in one hit (toggle) |
| ISeeDeadPeople | Reveal the map | No fog of war (toggle) |
| SharpAndShiny | All upgrades | Every tech researched; Mothership and Ship Stations at tier III |
| WarpTen | Fast building | Ships, buildings and upgrades build 10x faster (toggle) |
| WhoIsJohnGalt | Fast research | Research 10x faster (toggle) |
| Synergy | No tech tree | No tech, tier or prerequisite requirements (toggle) |
| KeyserSoze [n] | +n gold | +n energy (default 500) |
| LeafItToMe [n] | +n lumber | +n metal (default 500) |
| GreedIsGood [n] | +n gold and lumber | +n energy and metal, plus n/100 (at least 1) of every rare item |
| PointBreak | No food limit | No housing limit (toggle) |
| ThereIsNoSpoon | Unlimited mana | Skills never recharge (toggle) |
| TheDudeAbides | Reset cooldowns | Every skill ready now |
| IocainePowder | Fast decay | Wrecks rot away 20x faster (toggle) |
| StrengthAndHonor | No game over | Losing the Mothership doesn't end the game (toggle) |
| ItVexesMe | No victory | Victory is disabled (toggle) |
| Motherland [hex] | Jump to a campaign level | Jumps your Mothership, stations and fleet to a Hive hex (name or number; empty = centre), or to the map centre |
| SomebodySetUpUsTheBomb | Instant defeat | Instant defeat |
| AllYourBaseAreBelongToUs | Instant victory | Every hostile empire eliminated (Campaign: the pirates fall and the galaxy ascends) |
| RiseAndShine | Dawn | Next pirate raid 5 minutes later |
| LightsOut | Dusk | Next pirate raid now |
| DayLightSavings [s] | Set / freeze the time of day | With s: next raid in s seconds; without: freeze / unfreeze the raid timer |
| TenthLevelTaurenChieftain | Plays "Power of the Horde" | A light show around your Mothership |

## Code map (`core/src/main/java/spacerts`)

| Folder | Contents |
|---|---|
| `units/` | `ShipClass` (stats table for every unit), `Unit`, `Spec`, orders |
| `world/` | `World` (tick order), `GalaxyGenerator`, `Body`, `Faction`, fog of war |
| `systems/` | Behavior (incl. Defender orbits), combat, movement, economy (incl. upkeep), production and research, `StationRally` (Rally Stations hex formation), `ThreatDirector` (pirates) |
| `research/` | `Tech` tree, `RareItem` |
| `ai/` | `RivalAI` (bots) |
| `net/` | LAN: `Command` / `CommandExecutor` (every player action), `Lockstep`, `NetHost` / `NetClient`, `Lobby`, `LanDiscovery` |
| `ui/` | Renderer and HUD |
| `input/` | Input handling |
| `save/` | Save and load |
