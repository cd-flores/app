# Development notes (handoff)

Context for picking this project up on another computer. The README describes the game for players; this file covers how the code is put together, what was built recently, and what is still open.

## Status
- Branch: `feature/hive-galaxy` (not merged into `main` yet).
- Build and tests: `./gradlew :core:test --offline` (116 tests, all passing). Run the game with `./gradlew lwjgl3:run` (or `run.bat`).
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

## Not verified / known gaps
- Nothing visual has been checked with screenshots. Worth a look in game: the tactic toggle and the Command Center's tactic picker (both sit just above the bottom bar), the chat box, the surrender popup, the paginated tutorial, and the top bar at small window sizes.
- LAN play has only been tested over localhost (automated test), not between two real PCs. Windows Firewall will ask to allow Java on first host.
- Tactic tuning (spacing, the Flank arc of 900, Ramming damage 800 × durability, Pincer trigger at 85% hull) is untested in real fights.
- Bots don't use tactics, the Command Center, Ghost Drive or Dash yet (they do auto-use skills they have via `UnitBehavior.autoAbility`).
- Saving is disabled in LAN games with more than one human.

## Ideas queued / possible next steps
- Teach Hard / Insane bots to build a Command Center and use Bulldozer.
- Show formation previews (slot markers) before confirming a tactical attack.
