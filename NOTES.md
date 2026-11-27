# Development notes (handoff)

Context for picking this project up on another computer. The README describes the game for players; this file covers how the code is put together, what was built recently, and what is still open.

## Status
- Branch: `feature/hive-galaxy` (not merged into `main` yet).
- Build and tests: `./gradlew :core:test --offline` (129 tests, all passing). Run the game with `./gradlew lwjgl3:run` (or `run.bat`).
- Java 17+, libGDX (LWJGL3 desktop). There is no Python on the original machine, so edits were made by hand or with sed/awk.

## Ground rules of the codebase
- **Deterministic lockstep simulation.** The game is played over LAN by running the same simulation on every machine, so:
  - every player action is a `net/Command`, applied by `net/CommandExecutor`. Never mutate the world directly from input or UI code: go through `GameScreen.issue(cmd)`;
  - simulation code must never branch on `w.player` (the local player) except for cosmetic things: `World.notify` (messages to one faction), rings / effects, local UI;
  - randomness inside the simulation uses `w.rng` (or another seeded stream), never `Math.random`.
- **When you add a Command type, bump `net/Protocol.VERSION`** (currently **6**), so mismatched builds refuse to connect.
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
