# Vystorm Paddock

**Your horse stays yours.** Tame a horse, donkey, mule, llama, camel or undead horse and it is claimed for you: nobody else can ride it, hurt it, leash it or open its saddlebags. Put it into your virtual stable and summon it anywhere, saddle, armour, chest and name included.

- Claim on taming (automatic) or `/paddock claim` for animals you tamed before.
- Trust friends per animal: `/paddock trust <player>`.
- `/paddock store` puts the animal you ride or look at into your stable, `/paddock list` shows it, `/paddock summon [number|name]` brings it back next to you (no argument: your only animal, or the stable menu; names tab-complete).
- Stable size per permission: `vystormpaddock.slots.<n>` (default 3), summon cooldown, worlds without summoning.
- Animals rest in the stable: they heal evenly while stored, fully after `stable-rest-minutes` (default 10).
- `/paddock info` shows owner, trusted players, health, speed in blocks/s and jump height.
- `/paddock compare` compares the ordinary horse you ride with another ordinary horse you look at within `reach`: estimated speed, jump height and maximum health, with signed differences (looked-at minus ridden). Useful for choosing breeding or racing horses; also `/pferdekoppel vergleichen` and `/马场 比较`. Uses `vystormpaddock.use`; no ownership required, just like info.
- Owners get a message with place and killer when their animal dies. Optional protection from all non-player damage.
- `/paddock stable` opens your stable as a chest menu, `/paddock list` entries are clickable, summoned animals carry you right away (with saddle).
- `/paddock call` whistles the animal you last rode to you, `/paddock rename NAME` names it without a name tag.
- Breeding: foals remember sire, dam and generation, every animal gets a breeding score from 0 to 100 from speed, jump and health; foals of two of your animals are yours automatically.
- Works next to Vystorm Pets: horses, donkeys, llamas and camels that are pets stay with Pets (taming, storing, summoning); Paddock just protects them for their owner and handles mules, trader llamas and undead horses.
- **Herd book**: `/paddock herd` lists every claimed animal you have out in the world – where it was last seen and how far away it is. Click to get an arrow to it, right click to call it over.
- **Horse races**: time trials with checkpoints, personal bests, a top 10 per course and record announcements. `/paddock race` opens the course list.
- **Race betting** (needs a Vault economy): while a rider waits in a course's start circle, players nearby bet with `/paddock bet <rider> beat|miss <amount>` whether the rider beats their own best time; `/paddock bet` lists open books. Settled bets go to `data/bet-log.csv` (`betting.log`).
- Ownership is stored on the animal itself, stables in one small file per player. No database; Vystorm Core is required.
- Texts in English, German, Simplified Chinese, French, Brazilian Portuguese, Italian and Russian through the Vystorm Core language system; `config.yml` is editable in game with `/vadmin config`.
- Needs [Vystorm Core](https://www.spigotmc.org/resources/139848/) 0.65.0 or newer (free). What the build checks: Java 21 bytecode (`--release 21`), compiled against the Paper API 1.21.11 and, in `compat1206`, against Paper 1.20.6, with isolated tests against the 1.20.6 API. Use the Java version your server needs.
- Tested on Paper and Purpur 26.2, Paper 1.20.6 and Spigot 26.2.

## Animal trade (1.14.0)

`/paddock offer <player> [price]` sells or gifts the claimed animal you look at or ride; the other player accepts with a click (`/paddock accept`) or declines (`/paddock decline`). Prices use Vault.

## Animal training (1.11.0)

Ride your own adult mount in Survival and use `/paddock train ride` or `/paddock train jump`. Riding records ground distance; jumping records completed jumps at least two blocks long and 0.6 blocks high, landing within one block of the takeoff height. `/paddock train status` shows both levels, partial progress and recovery time; `/paddock train stop` stops the exercise without losing saved progress.

The defaults are 300 metres or 12 qualifying jumps per level, five levels and 60 seconds of shared recovery after an improvement. `training.*` controls these thresholds and the per-level bonuses. Training's own modifiers are capped at +0.05 movement speed and +0.15 jump strength in total, independent of the configured level count. Other plugins' attribute modifiers remain separate. Each animal keeps its progress, cooldown and permanent modifiers in its own persistent data, including stable storage and server restarts.

`vystormpaddock.train` defaults to true, but only the animal's owner can train it; trust and administrator bypass do not substitute for ownership. Pets-managed animals and disabled worlds are excluded. Unsupported attributes are rejected. Teleporting, dismounting, disconnecting, reloading or ten minutes ends the session. The existing race clock samples only opted-in riders; no additional repeating task or world scan is added. All messages and command help are available in all seven bundled languages.


## Stable browser

`/paddock stable [search]` opens a searchable stable with 45 animals per page and previous/next arrows. Search matches part of a name or an English animal type, case-insensitively (for example `Silver`, `donkey` or `skeleton horse`; underscores also work for types). The book shows total occupancy and the number of matches; the milk bucket clears the search. Click an animal to summon it under the usual world and cooldown rules. Searches do not change `/paddock list` or `/paddock summon` numbering.

German: `/pferdekoppel stall [Suchtext]`; Simplified Chinese: use the localized stable command or `/paddock stable [搜索内容]`. Uses `vystormpaddock.use` and the existing `stable-slots` / `vystormpaddock.slots.<n>` limits; no new config keys or data migration are required.

## Herd book

Lost your horse somewhere in the wild? `/paddock herd` (or the Vystorm Core main menu `/vmenu`) opens your herd book: one icon per claimed animal with world, coordinates, distance and when it was last seen.

- **Left click**: an arrow and the distance in your action bar lead you to the animal (to its live position if its area is loaded). `/paddock herd stop` ends it.
- **Right click**: calls a loaded animal to you – same rules and cooldown as `/paddock call`.
- **Shift + right click**: removes an entry (e.g. an animal that died while nobody was near).
- The chest button opens your stable.

Positions are noted when an animal is claimed, dismounted, renamed or summoned and whenever its chunk loads or unloads – animals claimed with older versions join the book by themselves. One small file per player in `plugins/VystormPaddock/herd/`.

```yaml
herd-book:
  enabled: true
  track-seconds: 120   # how long the arrow keeps pointing
  max-animals: 200     # most animals one player's book keeps; the longest unseen drop out first
```

## Horse races

Time trials on horseback with checkpoints, personal bests and records.

**Build a course** (permission `vystormpaddock.course`, op by default): stand at the start and run `/paddock course create NAME`, ride the track and run `/paddock course point NAME` at every checkpoint – the last point is the finish (start and finish may be the same place for a loop). `/paddock course undo|radius|reset|delete NAME` fixes mistakes, changes the size of the circles (1–16 blocks), clears the times or removes the course. Adding or removing points clears the times.

**Race**: `/paddock race` (or `/vmenu`) lists the courses with record and your best time; a click shows the start and the top 10. Ride any horse, donkey, mule, llama or camel out of the start circle and the clock runs; pass the checkpoints in order (the next one glows for you), the action bar shows time and gate. Getting off, teleporting or the time limit ends the run, `/paddock race stop` too. New records are announced to everyone online. Courses and times are stored in `plugins/VystormPaddock/races.yml`.

```yaml
races:
  enabled: true
  max-minutes: 10          # longer runs are cancelled
  default-radius: 4        # circle size of new courses
  announce-records: true
  show-checkpoints: true   # particles at the next checkpoint
```

## Switches

Every feature has an on/off switch in `config.yml` (listed in Vystorm Core's `/vadmin features`, applied without a restart except `tutorial.enabled`; all on by default except `protect-from-environment` and `call-across-worlds`, which shipped off): `claims.enabled` (the whole plugin), `auto-claim-on-tame`, `protect-from-environment`, `trust.enabled`, `stable.enabled`, `stable-menu.enabled`, `stable-rest.enabled`, `mount-on-summon`, `info.enabled`, `death-notices.enabled`, `call.enabled`, `call-across-worlds`, `rename.enabled`, `breeding-records.enabled`, `claim-foals`, `pets-protection.enabled`, `herd-book.enabled`, `races.enabled`, `races.course-editor.enabled`, `races.announce-records`, `races.show-checkpoints`, `betting.enabled`, `betting.log`, `training.enabled`, `trade.enabled`, `tutorial.enabled`. A switched-off feature answers "This feature is switched off on this server."; a command whose own switch is on but which needs another one names that switch (`/paddock course` and `/paddock bet` need `races.enabled`, the stable menu needs `stable.enabled`); claims, stables, herd books, races and training stay saved.

## Placeholders

With Vystorm Core 0.65.0 the plugin registers the namespace `paddock` (server thread; empty while the feature is switched off): `stable_slots`, `stable_stored` (kept for five seconds, it reads the stable file), `mount_speed_level`, `mount_jump_level`, `mount_owner`, `race_best`, `race_rank`, `race_record` (each with a course name) and `race_courses`. Examples: `%vystorm_paddock_stable_stored%` and `{paddock:race_best:Meadow}` (the player's best time on the course Meadow). `/vadmin placeholders paddock` lists them.

## Permissions

| | |
|---|---|
| `vystormpaddock.use` | claim, store, summon, herd book, horse comparison, searchable stable browser (everyone) |
| `vystormpaddock.race` | ride time trials (everyone) |
| `vystormpaddock.course` | create and edit race courses (op) |
| `vystormpaddock.slots.<n>` | stable size |
| `vystormpaddock.bypass` | manage every animal, no cooldown (op) |
| `vystormpaddock.admin` | `/paddock reload` (op) |

## Building

`./gradlew build` → `build/libs/VystormPaddock-<version>.jar`

Compatibility uses Core's attribute and inventory adapters. No Paddock feature is disabled on 1.20.6. Animals absent from the server use a saddle menu icon. Serialized animals from newer Minecraft versions cannot be downgraded; failed deserialization keeps the stable entry intact.

Build with JDK 25 and `-PvystormCoreJar=<Core-0.65.0-or-newer.jar>`. `check` includes `compat1206` (Java 21 compilation against the 1.20.6 API) and isolated legacy API tests. Override the default local library directory with `-Ppaper1206Libraries=<path/to/1.20.6/libraries>`; the directory must contain the Paper API and its libraries.

## License

Copyright (c) 2026 Esoren / Vystorm

Versions after 1.16.1 are licensed under the GNU General Public License v3.0 (GPL-3.0), see [LICENSE](LICENSE).
Version 1.16.1 and earlier were released under the MIT License ([LICENSE-MIT-until-1.16.1.txt](LICENSE-MIT-until-1.16.1.txt)) and stay available under those terms.
