## 1.18.0 — 2026-11-18

- Requires Vystorm Core 0.65.0 or newer. Update Core before you update this plugin.
- Every feature can now be switched on and off in `config.yml`. A server that updates keeps working as before: the new switches are on, `protect-from-environment` and `call-across-worlds` stay off as they shipped, and the new keys are added to your existing `config.yml` with their comments. Each switch is listed in Core's `/vadmin features` with a one-line description in seven languages and applies at once, without a restart (only `tutorial.enabled` needs one).
  - The whole plugin: `claims.enabled`. Off: claimed animals are not protected (their claim stays on the animal), no menu, race, bet, trade or training runs, and every `/paddock` command except `reload` is switched off.
  - Animals: `trust.enabled`, `info.enabled` (info and compare), `death-notices.enabled`, `call.enabled`, `rename.enabled`, `breeding-records.enabled`, `pets-protection.enabled` (off: animals of Vystorm Pets are not protected by Paddock).
  - Stable: `stable.enabled` (store, summon, list), `stable-menu.enabled`, `stable-rest.enabled` (off: no healing in the stable).
  - Races: `races.course-editor.enabled` (off: courses cannot be built or changed; they stay saved).
  - Tutorial: `tutorial.enabled` (needs a restart).
  - Keys that already existed keep their names and are now listed as switches too: `auto-claim-on-tame`, `protect-from-environment`, `mount-on-summon`, `call-across-worlds`, `claim-foals`, `races.announce-records`, `races.show-checkpoints`, `betting.log`.
- A switched-off feature answers one shared message, "This feature is switched off on this server.", in all seven languages (new language key `feature-off`), and its subcommands are hidden from help and tab completion. Claims, stables, herd books, races, bets and training stay saved and work again when the switch is back on.
- Tab completion and help follow a switch at once, without registering the command again.
- Fixed: `/paddock course` worked while `races.enabled` was off; it now answers that the feature is switched off.
- Placeholders: the plugin registers its values with Vystorm Core under the namespace `paddock`: stable slots and stored animals, the training levels and the owner of the animal the player rides, the player's best time and place on a course, the course record and the number of ready courses. Examples: `%vystorm_paddock_stable_stored%` and `{paddock:race_best:Meadow}` (the player's best time on the course Meadow). `/vadmin placeholders paddock` lists them. A value of a switched-off feature is empty.
- The off messages of the herd book, races, betting and trade are now the shared message.
- README: the introduction comes first again, the compatibility line says what the build checks, and a broken character in the breeding score line was fixed.

## 1.17.0 — 2026-11-17

- Requires Vystorm Core 0.64.0 or newer. Update Core before you update this plugin.
- The order in which Vystorm plugins react to the same event is now set in one place, Vystorm Core (`event-order` in Core's `config.yml`). The protection of claimed animals (damage, mounting, leashing and use by strangers) now denies before other plugins act.
- License: GPL-3.0 from this version. Earlier versions stay under the MIT License.

## 1.16.1 — 2026-11-16

- Tab completion for the stake in `/paddock bet <rider> beat|miss <amount>` now suggests the minimum and maximum stake configured under `betting`.

## 1.16.0 — 2026-11-15

- Rebuild the stable, herd book and race course menus as Core Panels with paged lists, selected-entry details, stable search and sorting, race records and confirmed herd entry removal. All three original chest menus remain as fallbacks for players or Core versions without Panels; no item-transfer menus needed to stay chest-only.
- Translate the new panel controls and animal types into all seven supported languages.

## 1.15.1 — 2026-11-12

- Bet log: every settled race bet is written to `data/bet-log.csv` (course, rider, times, bettor, side, stake, payout). Winning "miss" bets by someone in the rider's party, or on a run the rider cancelled, are marked there, in the server log and to admins online (`vystormpaddock.admin`). Nothing is blocked – admins decide. Setting `betting.log`.

## 1.15.0 — 2026-11-12

- Race betting (needs a Vault economy): while a rider waits in a course's start circle, players nearby are told that bets are open and can bet whether the rider beats their own best time there (`/paddock bet <rider> beat|miss <amount>`; `/paddock bet` lists open books with the current odds). Leaving the circle starts the run and closes the bets. The pool minus a small house cut goes to the winning side by stake; with only one side everyone gets their stake back, and open bets are refunded when the run never starts. Riders cannot bet on themselves. Settings under `betting`, also in the first-run setup; permission `vystormpaddock.bet`.

## 1.14.0 — 2026-11-11

- Animal trade: `/paddock offer <player> [price]` offers the claimed animal you look at or ride to another player. They accept or decline with a click; a price is paid through Vault (no price = gift). The buyer becomes the owner, trusted players are cleared. With Vystorm Core 0.55.0 an offline seller finds the sale in their inbox.

## 1.13.0 — 2026-11-11

- Short tutorial in the main menu (/vmenu → Tutorials) in all seven languages.
- /paddock without arguments opens your stable.

## 1.12.0 — 2026-11-10

- First-run setup: when the plugin is installed for the first time, admins answer a few questions or take the recommended settings, with a preview before anything is saved (needs Vystorm Core 0.54.0 or newer).
- All settings, including extra configuration files, appear in Core's new `/vadmin config` menu.

## 1.11.0 — 2026-11-07

- Added animal training. Ride your own adult mount in Survival and use `/paddock train ride` or `/paddock train jump`. Riding records ground distance; jumping records completed jumps at least two blocks long and 0.6 blocks high, landing within one block of the takeoff height. `/paddock train status` shows both levels, partial progress and recovery time; `/paddock train stop` stops the exercise without losing saved progress.
- Added all seven languages, persistent progress, permission checks and focused regression tests. Existing Spigot/CraftBukkit and Paper paths remain supported.
- See `FEATURE_VALIDATION.md` for the tested build and native server flows.

## 1.10.0 - Spigot and CraftBukkit compatibility

- Require Vystorm Core 0.51.0 or newer.
- Use lossless native entity persistence through Core; preserve stored horses when a summon is cancelled or throws before spawning. Avoid duplicate stored copies after successful world insertion.
- Validation: 65 automated checks (0 skipped), plus combined startup, listener registration and Core diagnostics/help on official Spigot26.3 and CraftBukkit26.3.

## 1.9.0 (2026-11-06)

- Add complete French (`fr`), Brazilian Portuguese (`pt_br`), Italian (`it`) and Russian (`ru`) language bundles. Minecraft client locales and the Core language selector choose them automatically.
- Validate packaged language keys, placeholders, formatting and tutorial coverage in server-loader tests.

## 1.8.0 (2026-11-05)

- The stable browser has a sort button: oldest stored, newest stored, name, or animal type. Sorting applies before pagination and stays selected when changing pages or clearing a search. Selecting an animal still uses its stable ID. Includes English, German and Simplified Chinese labels.
- Fixed data loss when a stable file contains a non-section animal entry: the entry now survives subsequent saves and removal of other animals. A malformed `animals` root is rejected without modifying or deleting the file.

## 1.7.0 (2026-11-03)

- Runs on Paper 1.20.6 – 26.x. Java 21 bytecode and minimum API 1.20.6; requires Vystorm Core 0.48.0+ for runtime attribute and inventory compatibility.
- All Paddock features remain available on 1.20.6. Unknown animal icons fall back to a saddle. Minecraft entity data saved on newer versions cannot be downgraded; failed deserialization now preserves the stored entry.
- Added offline `compat1206` to `check` and runtime fallback tests against the real 1.20.6 API.

## 1.6.0 (2026-11-03)

- **Searchable stable browser**: `/paddock stable [search]` filters stored animals by name or English animal type. Browse 45 animals per page, see matching and total counts, and clear the search from the menu. All stored animals remain accessible even with more than 54 slots. Menu clicks resolve the displayed animal by ID, so a changed stable cannot summon a different animal. Includes English, German and Simplified Chinese texts.

## 1.5.0 (2026-11-02)

- **Horse comparison**: ride an ordinary horse, look at another and run `/paddock compare` to see estimated speed, jump height and maximum health side by side, with signed differences. Uses the existing `reach` setting and `vystormpaddock.use` permission. Includes German (`vergleichen`) and Simplified Chinese (`比较`) commands and messages.

## 1.4.0 (unreleased)

- **Resting in the stable**: animals heal while they stand in your stable, evenly over `stable-rest-minutes` (default 10) and fully after that. A horse you stored injured comes back healthier; the summon message shows how many hearts it regained. `0` switches the healing off.

## 1.3.0 (unreleased)

Needs Vystorm Core 0.45.0.

- Simplified Chinese (zh_cn) translation.
- `/paddock` now runs through Vystorm Core's command system: subcommands, help, tab completion and error messages in the player's language (`/paddock herbeirufen`, `/paddock strecke erstellen`, `/paddock 召唤` work next to the English names for everyone), a clickable `/paddock help` (`hilfe`, `帮助`), a "did you mean" for typos and an entry in `/vhelp`. New localized root commands `/pferdekoppel` (German) and `/马场` (Chinese) from the language files (`commands.paddock.*`); which languages are active is set server-wide in Core (`commands.localized-aliases`). `/paddock` and `/stable` and every English subcommand stay. The command is no longer declared in plugin.yml.

## 1.2.0 (2026-10-30)

- **Herd book** (`/paddock herd`, also in the Vystorm Core main menu `/vmenu`): a chest menu of all your claimed animals out in the world with world, coordinates, distance and when they were last seen. Left click shows the way with an arrow and the distance in the action bar, right click calls a loaded animal to you (same rules and cooldown as `/paddock call`), shift + right click removes a stale entry. Positions are recorded on claiming, dismounting, renaming, summoning and whenever a chunk with the animal loads or unloads, so animals claimed with older versions join the book on their own. One small file per player in `herd/`. Config: `herd-book.enabled`, `herd-book.track-seconds`.
- **Horse races** (`/paddock race`, also in `/vmenu`): time trials on horseback. Builders with `vystormpaddock.course` (op) lay out a course by riding it: `/paddock course create NAME` sets the start, `/paddock course point NAME` adds checkpoints, the last point is the finish. Riding out of the start circle starts the clock; checkpoints count in order, the next one glows for the rider, the action bar shows time and gate. Personal bests and a top 10 per course, server-wide announcement of new records. Getting off, teleporting or the time limit ends the run. All courses in `races.yml`. New permission `vystormpaddock.race` (everyone). Config: `races.enabled`, `races.max-minutes`, `races.default-radius`, `races.announce-records`, `races.show-checkpoints`.

### Fixed

- Dead, released or stored animals are also removed from the herd book while `herd-book.enabled` is false, so they no longer reappear when it is switched on again.
- A stabled animal whose stored data was damaged (for example after a hand edit of `stables/<player>.yml`) was hidden and then silently deleted from the file the next time the player stored or summoned an animal. It is now kept unchanged in the file, and the console names the entry once so it can be repaired.

## 1.1.0 (2026-10-29)

- Death notices now say where the animal died (block coordinates and world) and, if another player killed it, who did.
- `/paddock summon` without an argument summons your only stabled animal right away, or opens the stable menu if there are several. Tab completion for `/paddock summon` now offers animal names as well as numbers.

## 1.0.1 (2026-10-28)

- Registers itself for the Vystorm Core update check (Core 0.40.2+); older Cores are unaffected.

# Changelog

## 1.0.0 (2026-10-28)

- First release: claim on tame or by command, protection against riding, damage, leashing and inventory access by others, trust per animal, virtual stable with permission-based size, summon with cooldown, info with speed and jump height, death notice, English and German messages.
- Built on Vystorm Core: Core language system (`lang/en.yml`, `lang/de.yml`), in-game config editor (`/vadmin config`), Core task groups and atomic file writes.
- Stable menu, clickable list, mount on summon, `/paddock call`, `/paddock rename`, breeding score and pedigree, foals claimed automatically.
- Vystorm Pets compatibility: pets are protected for their pet owner but never claimed, stored or summoned by Paddock.
