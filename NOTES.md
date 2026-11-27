# Development notes (handoff)

Context for picking this project up on another computer. The README describes the game for players; this file covers how the code is put together, what was built recently, and what is still open.

## Status
- Branch: `feature/hive-galaxy` (not merged into `main` yet).
- Build and tests: `./gradlew :core:test --offline` (158 tests). Run the game with `./gradlew lwjgl3:run` (or `run.bat`).
- Java 17+, libGDX (LWJGL3 desktop). There is no Python on the original machine, so edits were made by hand or with sed/awk.

## Ground rules of the codebase
- **Deterministic lockstep simulation.** The game is played over LAN by running the same simulation on every machine, so:
  - every player action is a `net/Command`, applied by `net/CommandExecutor`. Never mutate the world directly from input or UI code: go through `GameScreen.issue(cmd)`;
  - simulation code must never branch on `w.player` (the local player) except for cosmetic things: `World.notify` (messages to one faction), rings / effects, local UI;
  - randomness inside the simulation uses `w.rng` (or another seeded stream), never `Math.random`.
- **When you add a Command type, bump `net/Protocol.VERSION`** (currently **8**), so mismatched builds refuse to connect.
- Saves are `save/SaveManager` (libGDX reflection JSON over the `SaveData` / `FactionData` / `UnitData` classes). Add new persistent fields there in both directions. Tactical formations are deliberately not saved.
- Tests live in `core/src/test/java/spacerts/`. `SimulationTest` is the original suite. Newer ones: `MultiplayerTest` (LAN, bots, teams), `GameplayTest` (stations, Defender, upkeep, skills, destroy, chat), `CheatTest`, `TacticsTest`.

## What has been built (most recent last)
1. **Menu, Campaign, LAN multiplayer** (`screens/MenuScreen`, `net/*`, `world/GameSetup`): up to 4 seats (humans or bots with per-bot difficulty), teams, UDP discovery on port 47777 and TCP on 47778. Bots hold for 3 minutes. Bot warship caps are 64 / 120 / 200 / unlimited for Easy / Normal / Hard / Insane.
2. **Stations and Rally** (`systems/StationRally`): Ship Station (ex Ship Factory), Research Hub, Repair Station and Command Center are mobile "stations" at speed 14. **V** rallies them in hex rings around the Mothership at its speed.
3. **Defender**: built at the Mothership (tier II), max 4. It orbits the Mothership (`UnitBehavior.defender`).
4. **Economy**: repairs cost metal (`World.repair`, a full repair costs about half a ship's metal price). Warcraft 3–style energy upkeep tiers live in `EconomySystem` (ships count their housing, each station counts 4).
5. **Skills, one key each** (`units/Ability`): Q Ping, E Ghost Drive (Wisp), T Taunt, Z Evasive Dash (Longbow / Phantom), N Nova, O Overcharge. Brood Swarm research makes a destroyed Hive release 5 Broodlings.
6. **UI**: paginated F1 tutorial; the top bar never overlaps (drops or shrinks segments). Edge panning works over the title bar / taskbar thanks to a GLFW cursor probe (`SpaceRtsGame.cursor`, set in `DesktopLauncher`).
7. **Destroy / Surrender** (Delete key, 25% refund, a confirmation popup for Surrender) and **chat** (`/`, Shift+/ or `/t ` for team).
8. **Cheats** (`world/Cheats`): all 22 Warcraft III codes, typed in chat. Unlock them with `ThereIsNoCowLevel`; `cheatlist` lists them. Single player only.
9. **Command Center and battle tactics** (`systems/TacticsSystem`, `units/Tactic`, `TacticUpgrade`, `Targeting`):
   - Bulldozer / Pincer / Flank / Focus formations: form up → march at the slowest speed → fight with the tactic's behavior.
   - Upgrades: Martyr Charge, Crippling Line, Ramming Plates, Shield Wall, Replenish.
   - Per-ship targeting modes (K): nearest / farthest / most / least health / random.
   - **Y** toggles tactics; the tactic is picked at the Command Center.
10. **Bots use Bulldozer** (`ai/RivalAI`: `commandCenter`, `tacticalLaunch`): Hard / Insane bots build a Command Center once their Hub stands (from 12 min, Insane 9 min, with 900/900 spare), unlock Bulldozer, then Shield Wall and Ramming Plates when rich (2500/2500). When an assault is grouped and the target is at least 1500 away, the ships near the leader (needs a tank and 6 ships) march in as a Bulldozer; the rest attack-move as before. Bot orders go through `RivalAI.order`, which drops the ship out of its formation first, and `attackMove` skips formation members.
11. **Balance pass** (`BalanceTest` covers it):
    - Ships: Halcyon retired (kept in `ShipClass` for old saves, no longer in any build list). Longbow range 850, Phantom 950. Bulwark, Longbow and Phantom fly at Lancer speed (165). Housing: Bulwark 6, Longbow 3, Phantom 4.
    - Prices: `Spec.cost()` and the `Tech` constructor add `Spec.ENERGY_SURCHARGE` (50) to anything with a cost, so spec numbers in `ShipClass` are the old base prices. Repair Station base cost 750/750. `ProductionSystem.priceK()`: Bulwark / Longbow / Phantom use 0.7, other warships 0.35.
    - Limits: at most `ConstructionSystem.MAX_STATIONS` (2) Ship Stations; still one Research Hub.
    - Repair: colonies no longer repair docked ships (`World.nearestRepairPoint(..., colonies=false)` in base repair and auto-retreat).
    - Fortified worlds: natives only target factions in `Body.provoked` (last hit time per faction id, set in `CombatSystem.applyDamage`, saved) within `Body.PROVOKE_MEMORY` (30 s).
    - Resources: stars hold 300k-600k energy in `Body.metal` / `maxMetal` (`finiteStar`, `drainStar`, `burntOut`); stars from older saves have `maxMetal` 0 and stay endless. Giant asteroids hold 150k-300k; the classic map has 4, one near each edge midpoint. `GalaxyGenerator.CLUMP_MULT` doubles every asteroid clump. Metal planets are still endless.
    - Bots: `RivalAI.START_DELAY` 480 s; `colonyLimit()` 3 + 2 every 15 min (max 14); decisions every `Difficulty.thinkInterval()` (20 / 10 / 5 / 1.5 s).

12. **Lancer buff, Pyre, buoys, bots fight on** (`UnitsTest`):
    - Lancer: 330 hull, 14 damage.
    - Pyre (`ShipClass.PYRE`, Station II): short-range splash flamer. `Spec.burn(dps, s)` sets `Unit.burnTime` / `burnDps` in `CombatSystem.hit` (splash victims too); `UnitBehavior` ticks it through `applyDamage(..., piercing, null)`. `Spec.antiSwarm` multiplies damage against Drones and Broodlings (x4). Tactics role INFANTRY; bots build it.
    - Buoys are not units: `Body.buoyRings` / `buoyTimer` (saved). `EconomySystem.launchBuoys` adds a ring every 300 s up to 5; `Body.buoy(k, i)` gives positions; `World.updateVision` reveals them (300) for the owner and allies; rings reset when the colony is gone.
    - `AutoDefense.retreats` skips factions with an AI (`owner.ai != null`).

13. **Tougher economy structures, match end**: Research Outpost 1750 hull, Solar Collector 4500, Asteroid Miner 5000 (5x). When `World.eliminate` decides a victory it also sets `World.matchOver` and `winner` (saved); `World.update` then returns at once on every machine, and the HUD shows the result (Enter: menu; C: keep playing in single player, which sets `noVictory`). Your own defeat does not freeze the world (other LAN players play on); it keeps the old defeat screen.

14. **Room codes** (`net/RoomCode`, `RoomCodeTest`): the host's IPv4 address plus a 3-bit checksum, as 7 Crockford base-32 characters (`ABC-DEFG`). No server: same network or a shared VPN only. `MenuScreen` shows the code(s) in the lobby (private-network addresses first) and the join box accepts a code or a plain IP (`RoomCode.resolve`). Internet play would need a relay server.

15. **Mothership, labs, formations, loading** (`FeaturesTest`):
    - Mothership 12000 hull. `Ability.WARP` (L, tier II): `UnitBehavior.useAbility(u, a, x, y)` jumps up to `WARP_REACH` (1000) and sets `Unit.sprintTimer` (20 s at Wisp speed, in `MovementSystem`); 20-minute cooldown. Aimed with `GameInput.aimingWarp`; the point rides on the ABILITY command.
    - Tier II: `ConstructionSystem.maxStations` (2 -> 4) and `maxHubs` (1 -> 2). Lab 2 is `Faction.hub2` / `researching2` / `researchProgress2`; `ProductionSystem.freeLab` picks the lab, `cancelResearch(f, t)` cancels the right one. Bots fill both labs and build the second hub.
    - Formations: once past FORMING every member moves at `TacticsSystem.lancerSpeed` (no travel boosts); the anchor advances at `MARCH_PACE` (80%) of it. Bulldozer infantry get a `Member.guard` tank and a ring slot around it.
    - Ctrl+A uses `GameInput.isArmy` (no Patrol Ships). `NetClient.close()` works while still connecting, and the menu ignores repeat joins (double-click fix).
    - LAN loading: `GameScreen.renderLoading` draws the game behind a loading screen, warms the simulation on a throwaway world (`warmUp`), then `Lockstep.markReady()`. READY / GO messages (`Protocol` 12 / 13); no turns before GO; the host drops anyone not ready after `READY_TIMEOUT` (60 s). Headless timing showed the simulation and network are cheap at start (galaxy ~60 ms, first game second ~20 ms, LAN lockstep with no waits), so the start-up lag is most likely first-frame rendering on a cold JVM, which now happens behind the loading screen.
    - Research panel: one column per branch (`Tech.PANEL_BRANCHES`, `Tech.branchOrder`), tiers ascending with follow-ups under their prerequisites; tall branches wrap.

16. **Race groundwork** (`RaceTest`), no change in play:
    - `units/Race.java`: each race names its `flagship`, `worker` (builder) and `scout`, what each producer builds (`buildList(ShipClass)`), and the bot's army mix (`botArmy`, `botCapital`, `botBattleship`). The current roster is `Race.COALITION`, the only race so far.
    - `Faction.race` (from `GameSetup.Slot.race`, sent in the lobby setup, saved as `FactionData.race`; older saves load as COALITION). `Protocol.VERSION` 8.
    - `ShipClass.buildList(Race)` replaces the old global lists. Role checks use `ShipClass.isFlagship()` / `isBuilder()` (`Spec.flagship()` / `builder()`) instead of `== MOTHERSHIP` / `== HARVESTER`; the starting fleet, colony harvesters and the bot's worker/scout come from the race.
    - To add a race: new ships in `ShipClass` (one marked `.flagship()`, one `.builder()`), a constant at the END of `Race` with its lists, `TacticsSystem.role` lines, `WorldRenderer` shapes, and a race pick in the lobby (`MenuScreen`; there's none yet since only one race exists). Still shared by every race: the tech tree, the structure roster, the Mothership tier upgrades (`MS_TIER_2/3`) and Warp Jump.

## Performance (stress test)
- `./gradlew :core:stressTest --offline` (`StressTest`, tagged `stress`, left out of `test`) times `World.update` with more and more near-unkillable ships and writes `core/build/reports/stress/stress-*.csv`. It asserts only correctness (no NaN), never speed.
- The budget is 33 ms per update (30 a second). Measured on the dev PC (JDK 25), logic only, no rendering:
  - **Battle** (two fleets fighting in one spot, worst case): about 10,400 units at 27 ms average (p95 41 ms); 15,500 units is over budget (51 ms).
  - **Spread out** (ships flying across the map, no combat): about 19,000 units at 23 ms; 28,000 units is over budget (50 ms).
  - Up to about 5,000 units in battle, an update takes under 10 ms. Cost grows faster than the unit count once one fight packs thousands of ships together.
- Not measured: drawing (FPS), and real games with bots and pirates at these counts.

## Not verified / known gaps
- Nothing visual has been checked with screenshots. Worth a look in game: the tactic toggle and the Command Center's tactic picker (both sit just above the bottom bar), the chat box, the surrender popup, the paginated tutorial, and the top bar at small window sizes.
- LAN play has only been tested over localhost (automated test), not between two real PCs. Windows Firewall will ask to allow Java on first host.
- Tactic tuning (spacing, the Flank arc of 900, Ramming damage 800 × durability, Pincer trigger at 85% hull) is untested in real fights.
- Bots only use Bulldozer (Hard / Insane). They don't use the other tactics, Replenish, Ghost Drive or Dash yet (they do auto-use skills they have via `UnitBehavior.autoAbility`).
- Saving is disabled in LAN games with more than one human.

## Ideas queued / possible next steps
- Let bots pick Pincer / Flank / Focus to counter what they face.
- Show formation previews (slot markers) before confirming a tactical attack.
