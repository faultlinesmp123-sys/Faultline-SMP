# Rocco Vendetta (and Little Brother Werner)

Inspired by Ricardo from Limbus Company, renamed **Rocco Vendetta**. The **2nd hardest boss** (after the Kraken).
**Coded:** `plugins/FaultlineBosses/src/main/java/net/faultlinesmp/bosses/Vendetta.java`. Settings live under `rocco:` in the
Bosses config. Admin command: `/rocco <summon|kill|phase <2|3>|tattoos [n]|werner|tattoo [n]|item <contract|fist>>`.
Art: `tools/vendetta_models.py` (skins, rigs, Fist, Contract, Index icons). Werner's look follows the owner's reference
picture (white hair, purple coat with black fur, gold chains, white trousers); Rocco is the darker big brother.

## Summoning
- **Vendetta Contract**: craft a Book with 4 Gold Blocks (corners) and 4 Netherite Scrap (sides). Use it on open, flat ground
  (same check as Don Lorenzo: no buildings, no villagers). 15 minute cooldown after he falls.

## Fight
- 5,000 health, **3 phases** (2/3 and 1/3 start the next phase, with a short invulnerable roar and shockwave).
- **At half health** Werner (300 HP) joins.
- **Vengeance Tattoo:** every hit he takes adds one (at most every 10 ticks), max 50, each +1% damage. At 50 (phase 2+)
  his next move is COMPLETE AND TOTAL EXTERMINATION!!!, which resets them.
- Kicks and punches stun for 3 seconds (can't move, jump or attack). After a stun you're immune for 1.5 seconds.

## Moveset
| Phase | Move | What it does |
|---|---|---|
| 1+ | Kick | Front kick, HEAVY (a shield halves it), 3s stun |
| 1+ | Punch | Step-in straight, blockable, 3s stun |
| 1+ | Payback | 2s guard. Hit him and he blinks behind you and uppercuts (more with tattoos) |
| 1+ | Assemble | Vendetta Enforcers (vindicators): 2 + one per fighter, max 6 alive, 30s cooldown |
| 1+ | Watch Your Back! | Blinks behind you, hits, blinks behind you again, hits |
| 2+ | MY HAIR COUPOOOOOOOOOOONS!!! | 2.5s charge, a 14-block-wide blast: half your current health + 1s stun |
| 2+ | Swear Vengeance | Dodges everything for 5s, then 15s of +25% damage, +40% speed, 25% less damage taken |
| 2+ | No More Tests | Blinks to 3 people (or the same one again) and hits each |
| 50 tattoos | COMPLETE AND TOTAL EXTERMINATION!!! | Leaps 22 blocks, a ring follows a random player, locks 1s before he lands: inside = dead (no totem) |

## Werner (300 HP)
| Move | What it does |
|---|---|
| Aim for the Solar Plexus | Body blow, 1s stun |
| Right in the Dome | Headbutt, Weakness II (a shield blocks it) |
| Gut Crush | Two punches, then +25% damage for 10s |
| Vengeance Awaits You All! | Slam: half your health if you have Weakness, +25% damage per debuff, then 3s stun, Slowness II, Weakness II |
| Counter - Seize ya Chance | Guard: hit him and he counters with Weakness II |

Passive: every Vendetta Enforcer that dies gives him +15% damage. If he dies, Rocco gets +15% damage, and each fighter has
a 1 in 3 chance at a cosmetic ("his coat, book and chains").

## Drops (Rocco)
- **Vendetta Fist** (100%), **50-150 Mythic Goodie Bags**, **10 Goodie Bags**, 3,000 XP.
- **One cosmetic** they don't have yet: Golden Chains, Book or Coat (`cosmetic unlock` in FaultlineCosmetics).

## Vendetta Fist ("his passives")
+8 attack damage, fast swing. Sneak + right-click picks a move, right-click uses it (each has its own cooldown). Moves never hit players.
Kick, Punch (stun mobs), Payback (the next mob hit on you is thrown back x1.5), Assemble (2 friendly Enforcers for 30s),
Watch Your Back!, MY HAIR COUPONS!!! (half a mob's health), Swear Vengeance (5s untouchable + Strength, Speed, Resistance),
No More Tests (3 hits), COMPLETE AND TOTAL EXTERMINATION!!! (needs 50 tattoos: kills a mob, 250 to a boss), plus
Mercy of the Big Brother (Regen + Resistance), TRICKED ME, DID YOU??!! (Strength II + Speed II), Ah, You Were One of the Fam
(Absorption IV for 3 hearts, 10 minute cooldown, standing in for "once per boss fight").
Passives: getting hit while holding it gives Vengeance Tattoos (kept on the player); dropping below half health brings a
friendly Werner for 30 seconds (every 5 minutes). Other Fist moves deal at most 60 to a boss.
