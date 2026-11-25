# Faultline SMP backup (before the overworld overhaul)

Snapshot of everything as of commit `430fbc04543a1807e725310695b204d9e96d1b7e` (2026-11-25).

| File | What |
|---|---|
| `faultlinesmpbackup.jar` | The whole server in one jar (= FAULTLINESMPJAR 1.1.0 with all 11 plugins bundled) |
| `plugins/*.jar` | The same 11 plugins as separate jars |
| `faultlinesmp-source.zip` | Source of every plugin + `tools/` + CLAUDE.md + the Bedrock mappings |

Versions: Items 1.3.57, Bosses 2.3.1, Deep 1.19.3, Index 1.9.27, Raids 1.4.1, Ships 1.7.0, Events 2.1.4,
Kingdoms 1.4.4, Cosmetics 1.1.7, Skills 1.0.3, LimboBlackMarket 1.2.4.

Resource packs at this point (download them from this exact commit):
- `FaultlineSMP.zip` sha1 `3ea651592e499747c61b7d967707cb4b7a5bba53`
- `FaultlineDeepMusic.zip` sha1 `d13735ff9b06f03f39d1e378f553927dbecfba04`
- `https://raw.githubusercontent.com/faultlinesmp123-sys/Faultline-SMP/430fbc04543a1807e725310695b204d9e96d1b7e/<pack>.zip`

## Rolling back
1. Stop the server.
2. In `plugins/`, delete `FAULTLINESMPJAR.jar` and put `faultlinesmpbackup.jar` there instead.
   NEVER run both at once: they carry the same 11 plugins and would fight over them.
3. Start the server. Plugin data folders (`plugins/FaultlineItems/` etc.) are shared, so nothing is lost.

This only backs up the CODE. Back up the world folders and `plugins/*/` data on the VPS yourself
(e.g. `tar czf faultline-world-backup.tar.gz world world_nether world_the_end faultline_deep plugins`) before the overhaul.
