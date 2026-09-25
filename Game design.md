# Crooked Guess — game design (web + Android)

Single-file game: `index.html` (in the gameM folder). The same file is the website and, copied into `mobile-app/www/` by `npm run sync`, the Android app. Game logic (`randomCode`, `scoreGuess`, `checkSlot`) is pure and separate from the UI.

Folder layout:
- `index.html`: the game.
- `lan-server.js`: zero-dependency Node server for Versus in the browser (`node lan-server.js`, then open the printed address on both devices).
- `mobile-app/`: Capacitor 8 Android project (package `com.soham.crookedguess`, targets API 36), native LAN host plugin, AdMob, launcher icons, splash, and `store-listing/` (Play icon, feature graphic, 8 screenshots, listing text, privacy policy, Play Console answers). `mobile-app/README.md` has build and publishing steps.

## Core rules
- Secret code of N slots; colours may repeat.
- Player fills all slots and submits a guess.
- **Crooked feedback**: only a count of pegs in the correct colour AND position. Never says which pegs, and never reports right-colour-wrong-place.
- **Hint**: tap Hint, then tap a placed peg -> shows ✓ (right colour for that slot) or ✗ (wrong). Limited per game, does not cost a guess. Re-checking a known slot/colour combo is free. Results stay marked on the pegs, including in past rows.
- **Extra hints for a video**: when hints run out, the Hint button turns gold ("▶ Hint"). Tapping it offers a rewarded video for +1 hint, up to 3 per game (`AD_CONFIG.maxPerGame`). Not offered in the tutorial or in Versus. The Time Attack clock is paused while the offer or the video is up.

## Levels
| Level  | Slots | Colours | Guesses | Hints | Time Attack | Base points |
|--------|-------|---------|---------|-------|-------------|-------------|
| Easy   | 4     | 4       | 12      | 3     | 3:00        | 300         |
| Medium | 4     | 6       | 10      | 2     | 4:00        | 600         |
| Hard   | 5     | 6       | 10      | 2     | 5:00        | 1000        |

Easy uses red, blue, green, yellow. Medium and Hard add purple and orange. Tuning is in the `LEVELS` table (`time` in seconds) and `LEVEL_PTS` / `PTS`.

## Screens
- **Home**: Start game, Mode picker, Time Attack switch, Tutorial card, **Versus** and **Scoreboard** tiles (the Scoreboard tile shows your best score), sound, How to play, stats line for the chosen mode. Choices are remembered.
  - The whole menu fits on one screen, no scrolling: the six hero pegs hop between the ? and sound buttons, spacing and sizes step down on small or short phones (`clamp()` with `vw`/`vh`), and `fitHome()` zooms the menu out a little if it still doesn't fit (tiny screen, large system font). Tested down to 320×540.
  - In the app (edge to edge) a dark strip sits behind the status bar so scrolling pages don't run under the clock.
- **Game**: home (menu) button, help, sound, level chip, guess count, New Game (hidden in Versus). Leaving a game in progress asks for confirmation. Browser Back and the Android Back button walk back through the screens (asking first when a game is in progress).
- **Scoreboard** page: your name, tabs for High scores / Stats / Versus, Reset scoreboard (with confirmation).
- **Versus** page: your name, Host a game (level picker), Join a game, How it works; then a lobby. In the app, games hosted nearby appear under Join a game by themselves (tap to join); typing the host's address is tucked under "Game not showing up?". The website shows the address box open, and only mentions `lan-server.js` on the web, never in the app.
- The tray (colour pegs, Erase, Hint, Guess) is fixed to the bottom. When the board scrolls, the current row and the row after it stay visible above the tray.

## Feedback dots animation
- When a guess is submitted, that row flashes, a light sweeps across its dots, then the correct dots light up one by one with a glow and the count pops in. The rising blips of the guess sound are timed to land with each dot, so the result registers even out of the corner of your eye. A 0 gives a small shake.
- When the current row is full and ready to guess, its pending dots ripple gently as a nudge to press Guess.
- The animation keeps its place across re-renders (`--ago` negative delay), so typing the next row doesn't cut it off.

## Win celebration ("code bloom")
- Replaces the old Klondike-style bouncing balls.
- The cracked code flips open peg by peg with a golden glow, light rays turn slowly behind it and two shockwave rings expand.
- Each secret peg launches as a rocket and bursts **in the shape of its own symbol** (▲ ● ■ ◆ ★ ✚) with sparkle trails, glitter and a flash, then a second volley rises from the bottom and confetti in the code's colours drifts down.
- A "CRACKED!" banner drops in letter by letter (Versus: "YOU WIN!") with "in N guesses · +points".
- Whoosh / boom / crackle sounds are synthesised (noise + notes). Tap or any key skips to the result card; soft fireworks keep going behind the card (up to 30 s). Skipped for prefers-reduced-motion.
- Loss: board shake + lose sound, then the reveal dialog.

## Scoreboard
- Points for a solo win: level base + 50 per spare guess + 75 per unused hint (of the level's own hints) + 2 per second left in Time Attack. Shown on the result card with a breakdown and a count-up, plus badges ("New high score!", "#3 on your scoreboard", "🔥 3 wins in a row").
- Top 10 high scores (name, mode, guesses, hints, time, when).
- Stats per mode and Classic / Time Attack: played, win %, streak, best streak, fewest guesses, fastest; guess-distribution bars; recent games.
- Versus: total W/L/D, head-to-head per opponent name, recent matches.
- Stored in localStorage (`store.scores`, `store.history`, `store.stats`, `store.vs`, `store.name`).

## LAN Versus
- Two players on the same Wi-Fi (or one phone's hotspot) get the **same secret**; the first to crack it wins the round. Hints as normal for the level, no video hints.
- Host picks the level in the lobby, presses Start match; both see a 3-2-1-GO countdown. In game, a bar shows both names, match score, a stopwatch, the rival's row and a tiny bar per rival guess (how many pegs right, never colours).
- Out of guesses: you wait; if the rival also runs out it's a draw. Leaving mid-round forfeits (the other player wins). Rematch when both tap it.
- Transport: a WebSocket relay that numbers every message and echoes it to both players; both act only on echoed messages, so they agree on who finished first.
  - Android: the host phone runs the relay (`LanRelay.java`, port 8765) and broadcasts "game here" on UDP 8766 every second; the other phone lists it under Join a game (`LanServerPlugin.java`). Screen stays on while hosting.
  - The app's page must load Capacitor's runtime (`capacitor.js`, copied into `www/` and injected before the game script by `mobile-app/scripts/copy-web.js`). Without it `Capacitor.registerPlugin` doesn't exist and the app acts like the website (no hosting, "type the IP", no ads, no Back button handling). `nativePlugin()` in index.html also falls back to `Capacitor.Plugins` as a safety net.
  - Web: `node lan-server.js` serves the game and the relay with 4-digit room codes (rooms listed automatically).

## Tutorial
- Started from the Tutorial card on the home screen (badge "Start here", then "✓ Done" once finished). Not counted in stats; finishing it also stops the rules dialog popping up on the first real game.
- Practice round: 3 slots, 4 colours, 6 guesses, 3 hints. **Fixed secret for everyone: Red, Yellow, Yellow.**
- Guided by "Pip", a floating coach card (a peg mascot) hovering above the tray: gentle float, progress dots, word-by-word text, colour chips, a speech tail aimed at the target, a gold highlight ring and a tapping hand pointer.
- Every step unlocks only the control it teaches; everything else is dimmed and a wrong tap shakes Pip. When the step is done, Pip cheers and the **Next button pops out**; the player must tap Next to move on.
- Steps: intro → fill the row with Red (score 1) → Guess and read the crooked count → fill with Yellow → Hint on the first peg (✗) → Guess (score 2) → deduce Red is slot 1 → build Red, Yellow, Yellow and Guess to win (win celebration + "Tutorial complete" dialog with Replay / Menu).
- Skip tutorial, the home button and Back leave straight away (no confirmation).

## Time Attack
- Optional switch on the home page; works with any mode.
- Countdown pill + progress bar above the secret. Turns red under 30 s, ticking sound in the last 10 s.
- Clock hitting 0 = loss ("Time's up!"). Clock pauses while a dialog or a video is open.
- Separate stats per mode for Time Attack, including fastest solve.

## Sound
- All sounds synthesised with Web Audio (no audio files). Sound on/off saved in localStorage.
- Each colour has its own note when placed; guess thud + light shimmer + one rising blip per correct peg (in time with the dots); hint yes/no tones; win fanfare; firework whoosh/boom/crackle; countdown beeps; rematch ping; reward chime; lose "sad trombone"; time-up buzzer; tutorial step chime.

## Ads (Android)
- AdMob rewarded video through `@capacitor-community/admob`; Google's UMP consent form where required, and "Ad privacy choices" in How to play when Google says it's needed.
- Ships with Google's test IDs (`AD_CONFIG` in index.html, `admob_app_id` in strings.xml). Replace both and set `testing: false` before release.
- Website: no AdMob; a clearly labelled 5-second demo stands in (`AD_CONFIG.webDemo`).

## Extras
- Each colour also has a symbol (▲ ● ■ ◆ ★ ✚) for colour-blind players.
- How to play shows a small coloured peg icon before every "peg" and a dashed-ring icon before "slot".
- Keyboard: 1-6 place, Backspace erase, Enter guess, H hint, arrows move. Enter on the home page starts. Keys go through the same tutorial locks.
- The app bundles the Outfit font so it looks right offline; safe-area insets are respected edge to edge.

## Ideas for later
- Daily seeded puzzle, haptics on guess, share result, more hint types, online (internet) Versus, iOS build.
