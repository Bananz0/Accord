# Fincord project history

## Identity migration — 2026-08-31

- The product and installed Android application are now **Fincord** with application ID
  `com.bananz0.fincord` (`com.bananz0.fincord.debug` for debug builds).
- Android treats this application ID as a separate app from Accord. Existing Accord app data,
  downloads and local credentials are not migrated automatically.
- Google Cast Intent-to-Join uses `fincord://cast/join`. Register that exact URI together with
  `com.bananz0.fincord` in the Cast Developer Console before publishing the receiver.
- Kotlin/Java sources deliberately retain the historical `uk.akane.accord` namespace. It is code
  lineage, not the installed package identity, and keeping it makes future Accord APK/source
  comparisons substantially clearer.
- Spotify OAuth uses the registered Fincord callback `fincord://spotify-callback`. It is separate
  from the Cast Intent-to-Join URI and must remain registered in the Spotify developer dashboard.
- Historical APKs, decoded trees, screenshots, UI dumps and logs keep their original Accord
  names and package IDs. Never bulk-rewrite them: they are comparison/provenance material for
  improvements brought in from later APKs.

## Lineage and attribution

Fincord continues Accord and AccordLegacy, which grew from Gramophone by the Akane Foundation.
Preserve their copyright notices, GPL-3.0 licensing, commit history, and visible Settings credits.
Developers and contributors already identified by this tree include AkaneTan, lightsummer233,
123Duo3, nift4, FoedusProgramme, emylfy and v3ndable. Add provenance here when a new APK or source
line contributes work; do not present upstream work as newly authored Fincord work.

**AkaneTan and FoedusProgramme are one account, renamed** - `AkaneTan/Gramophone` 301-redirects to
`FoedusProgramme/Gramophone` today. Treat them as one person when counting contributors, and link
to the current name. The `Copyright (c) 2024 Akane Foundation` notice and the `@AkaneTan` credit in
Settings are *not* to be rewritten: a notice records who held copyright when it was written, and a
credit records the name the work was published under.

The real chain, verified against the GitHub API on 2026-09-02:

    FoedusProgramme/Gramophone      canonical, active, not a fork
      -> FoedusProgramme/AccordLegacy   separate repo, archived 2025-03
        -> emylfy/Accord                fork of AccordLegacy
          -> v3ndable/Accord            fork of emylfy/Accord  <- `upstream` remote
            -> Bananz0/Accord           this tree, published as Fincord  <- `origin`

Fincord and Gramophone do share git history - their common ancestor is `ac8ca61c` (2024-03-15) -
but they have diverged past the point of merging: 279 commits on our side against 2307 on
Gramophone's beta, and 1445 files differing. Gramophone is a source to *cherry-pick* from, never to
rebase onto; the `gramophone` remote exists for reading it, and `upstream` stays v3ndable/Accord.

### Automix provenance — 2026-09-01

- Beat tracking, the phase vocoder and the FFT are **aubio 0.4.9** (GPL-3.0), by Paul Brossier and
  contributors, vendored under `automix/src/main/cpp/aubio/` with its `COPYING` and `AUTHORS` intact.
- The **0.25 BPM tempo snap** in `automix_analyze.c` is an idea taken from **walkywalker's Automix**
  (github.com/walkywalker/automix), which rounds its grid tempo on the grounds that electronic music
  is authored at quantised tempos. Implemented here from that observation, not from its source: that
  project is GPL-2.0 with no "or later" grant found, which would be incompatible with this tree's
  GPL-3.0, so no code was taken from it and none should be.
- **Mixxx** (github.com/mixxxdj/mixxx) is GPL-2.0-**or-later** and therefore *is* compatible with
  GPL-3.0, so its code may be used here with attribution. Nothing has been taken yet; its `Beats`
  model - zero or more beat markers followed by one tempo marker, with constant tempo as the case
  where there are no markers - is the reference for the variable-tempo grid work when that happens.
- Key finding uses the **Krumhansl-Kessler** probe-tone profiles from *Cognitive Foundations of
  Musical Pitch*, which are published data rather than anyone's source.

An idea is not code. Reading a project to learn its approach and then implementing it is fine and is
what happened above; copying its expression is a licence question, and for a GPL-2.0-only project
the answer here is no. Note also that a clean-room reimplementation means one party writes a
specification and a *different* party who has never seen the original implements from it - one
person reading the source and then writing the replacement is not a clean room and gains nothing.

### BitChord — 2026-09-02

- **kushagrasinghx/BitChord** (github.com/kushagrasinghx/BitChord) is a Kotlin/Android YouTube Music
  client, **GPL-3.0**, not a fork, first pushed 2026-08-11. Licence and repository facts verified
  against the GitHub API on 2026-09-02.
- GPL-3.0 means it is the *compatible* case, unlike walkywalker's Automix: its code may be used here
  with attribution and the licence headers intact. Nothing has been copied from it so far.
- What was taken is one idea: that a crossfade needs a second `ExoPlayer`, because one player
  renders one queue item and can therefore only be at gain 1 or gain 0 at a boundary. Also the
  property that follows from doing it with two players - the session, notification, queue index and
  scrobbler move to the incoming track the moment it becomes audible, rather than trailing the song
  on its way out.
- **What was not taken, and the note it corrects.** `AutomixTransitions` carried a comment saying
  its structure was "BitChord's design". It is not. BitChord loads the *incoming* track on its
  standby player and swaps which player backs the session; Fincord loads the *outgoing* track on a
  ghost and laps to it. BitChord's `CrossfadeController` documents the ghost arrangement as an
  earlier design it abandoned, having measured 9-41 ms of unavoidable misalignment between two
  ExoPlayers rendering the same audio. The comment has been corrected; do not restore the claim.
- Its `TransitionFilterProcessor` and Fincord's `BassSwapAudioProcessor` arrived at similar places
  independently - both `BaseAudioProcessor`, both re-aimed on a 30 ms fade tick, both gliding rather
  than stepping the cutoff. BitChord's is the better filter: trapezoidal state-variable rather than
  a biquad, 24 dB/octave, and a geometric glide because cutoff is heard logarithmically. If that is
  ported rather than reimplemented, it is GPL-3.0 and needs its header and a note here.

### Vendored media3 classes — 2026-09-02

- media3 1.11.0 deleted `MetadataRetriever` and `MediaExtractorCompat`. Both are vendored verbatim
  from **media3 1.10.1** (Apache-2.0, The Android Open Source Project) under
  `app/src/main/java/androidx/media3/exoplayer/`: `MediaExtractorCompat`,
  `MediaExtractorCompatInternal`, `MetadataRetriever` and `MetadataRetrieverInternal`.
- They keep the `androidx.media3.exoplayer` package on purpose. Each depends on package-private
  members of that package, and an AAR is not a JPMS module, so the split package is legal and is the
  only way to carry them without rewriting media3 internals.
- **Do not edit them.** They are byte-identical to upstream so they can be re-synced or dropped
  wholesale if media3 restores the classes. The `compileOnly` `checker-qual` and
  `error_prone_annotations` entries in `app/build.gradle.kts` exist only so their annotations
  resolve; media3 declares the same two the same way and neither reaches the APK.
- If a future media3 reintroduces either class, these copies must be deleted in the same change or
  the build will fail on duplicate classes - which is the intended, loud outcome.
- Why vendored rather than replaced with the platform `MediaExtractor`: `MediaExtractorCompat`
  takes a media3 `DataSource.Factory`, which is what lets automix analysis read through
  `JellyfinMediaCache`. The platform class only opens URIs itself, so swapping to it would
  re-download tracks this device has already streamed.

### ReplayGain port — 2026-09-02

- `ReplayGainUtil` and the starting point for `ReplayGainAudioProcessor` come from
  **FoedusProgramme/Gramophone** beta (GPL-3.0, nift4); the copyright and licence headers remain in
  both source files. This is a source port, not a cherry-pick, because Gramophone's renderer and
  audio-output stack now depend on its media3 and hificore forks.
- Stock media3 1.11 exposes LAME's gain record as `Mp3InfoReplayGain`; Gramophone's media3 fork calls
  the equivalent type `ReplayGainInfo`. The parser uses the stock type without changing its fields.
- Gramophone can protect positive gain with a native dynamic-range compressor from its hificore
  fork. Fincord does not carry that compressor, so its processor always chooses the peak-aware
  attenuation path and saturates integer samples defensively. ReplayGain is disabled by default;
  while disabled the processor copies PCM bytes unchanged. Enabling it deliberately means output
  is no longer bit-perfect.
- Stock media3 bypasses custom processors when float output is selected. If ReplayGain is already
  enabled when the playback service builds its sink, the renderer therefore overrides the Float
  audio output preference and uses the integer processor pipeline. Changes to either setting need
  a playback-service restart to change that pipeline; the Audio settings summary says so.
- `MixingAudioSink` sits outside `DefaultAudioSink`, so ReplayGain currently acts on the completed
  Automix buffer. Both decks therefore share the active track's gain during the overlap rather than
  receiving independent per-deck normalization. Independent normalization belongs in the deck
  pipeline if it is added later.

### Gramophone's audio sink is not cherry-pickable — checked 2026-09-02

Checked directly, so nobody spends the afternoon again. Gramophone's float-output and ReplayGain
work is all built on its **patched media3 fork** and none of it applies to stock media3 1.11:

- `74118d0c5` (nift4, 2025-05-19) renamed the concept: `setEnableAudioFloatOutput` became
  `setPcmEncodingRestrictionLifted`, now `setEnableHighResolutionPcmOutput`, and `buildAudioSink`
  took an extra parameter. The same commit deleted `HifiAudioSink.java`, 2400 lines of hand-rolled
  sink, in favour of the fork. **The switch stopped meaning "output float" and started meaning
  "do not restrict the PCM encoding"** - which is the right framing, because float on a phone
  speaker is not a hi-fi feature, it is a way to break one.
- `204f0c40e` (nift4, 2026-01-02) "Refactor ReplayGain to only convert to float32 if compressor is
  used" dropped `ToFloatPcmAudioProcessor` from the chain. Gain now rides a
  `PostAmpAudioOutputProvider` at the output level instead of forcing a float PCM pipeline.
- The APIs those use - `AudioOutputProvider`, `AudioTrackAudioOutputProvider`,
  `setEnableHighResolutionPcmOutput`, and an `AudioProcessorChain.getAudioProcessors(Format)` that
  takes the input format - **do not exist in stock media3**. Ideas port; commits do not.

`FoedusProgramme/Accord` (GPL-3.0, 139 commits, last pushed 2026-01-15) carries the same files under
this tree's own `uk.akane.accord` namespace, but every commit touching them is "Sync upstream" or
"Accord: Play music" - it inherited the stack and did no audio work of its own. It is **not** in this
tree's ancestry either: `emylfy/Accord` forks `FoedusProgramme/AccordLegacy` and `v3ndable/Accord`
forks `emylfy/Accord`, both confirmed against the API on 2026-09-02. It is a parallel repo, and a
place to read, not a source to merge.

### Gramophone ALAC fixes — 2026-09-02

- The active `GramophoneRenderFactory` registers the app's `AlacRenderer` before media3's platform
  audio renderers. This ports FoedusProgramme/Gramophone commit `dae39af6d`, so bundled ALAC support
  is preferred instead of depending on a device decoder.
- Decoder changes from commits `59dff089a` and `6391f8a87` preserve diagnostics for malformed
  streams, tolerate ALAC data-stream elements, and correctly reconstruct signed 20-bit stereo
  samples. The renderer consequently advertises 20-bit ALAC again.
- The decoder's original BSD-3-Clause notices were restored from commit `d4eb52189`. Keep those
  headers if the vendored decoder sources are moved or resynchronised.
- These are source ports rather than literal cherry-picks because Fincord's module layout and
  active renderer factory have diverged from Gramophone. Compile and unit tests cover integration;
  decoding still needs an ALAC fixture or device playback check when this path changes again.

## Signing

- Release signing belongs to the Fincord maintainer, never an Accord/Akane key or the Android debug
  key. Populate an ignored `keystore.properties` from `keystore.properties.example`, or provide the
  four `FINCORD_RELEASE_*` Gradle/environment values.
- Never commit a keystore, passwords, aliases containing secrets, exported certificates with
  private material, or a populated `keystore.properties`.
- Debug builds use the standard local Android debug certificate and are intentionally distinct
  from release builds.

## Homelab — where this app's server lives, and where builds go

`C:/Users/glenm/homelab` is the maintainer's infrastructure reference (`README.md`,
`infrastructure.md`, `media.md`, `apps.md`, `system.md`, `todo.md`, `credentials.md`). It is a
**separate private folder, not part of this repository**: read it rather than guessing at the server
side, never copy it in here, and never commit anything sourced from `credentials.md`.

The parts that bear on Fincord:

- **The Jellyfin server is deliberately not behind the Cloudflare tunnel.**
  `jellyfin.glenmuthoka.com` is a grey-cloud A record to an Oracle Cloud VPS running Caddy, which
  reaches `phastos:8096` over Tailscale - video stays off Cloudflare (ToS) and unthrottled. Every
  other subdomain does go through the `phastos` tunnel and Nginx Proxy Manager. A streaming problem
  and a sign-in problem therefore travel completely different paths, and diagnosing one as the other
  wastes an afternoon.
- **Jellyfin authenticates against LLDAP.** Users carry an `AuthenticationProviderId` on their
  database row and `glenm` is an LDAP user, not a local one. Anything exercising sign-in should know
  that before concluding the client is at fault.
- **Publishing a build:** `ssh phastos "dl-publish - <name>.apk" < <file>` streams a file straight in
  and prints its URL. It lands in `/srv/storage/morphe-dl/<32-hex>/<name>` behind NPM proxy host 58
  (`dl.glenmuthoka.com` -> `morphe-dl:80`), is kept for **7 days**, and is served `no-store`. Any
  file type, any size, no content checks. `https://dl.glenmuthoka.com/` answering 404 is correct -
  there is no index. Documented in `homelab/todo.md`.

`ssh phastos` works from this machine over `cloudflared access ssh`, key-only. Use a GitHub release
when a build needs to stay reachable; use `dl.` when it only needs to reach a phone this week.

## Incoming APK workflow

Record an incoming APK's filename, SHA-256, package ID, version code/name, source and date in this
file or `TODO.md` before using it as a reference. Keep decoded/generated material untracked, compare
behavior and resources, then implement improvements in source with original attribution intact.

## Playback outputs — 2026-08-31

- One `MediaLibrarySession` in `GramophonePlaybackService` fronts **three** players, and
  `updateSessionPlayer()` is the single place that decides which: the local `ExoPlayer`,
  `CastQueuePlayer` (Cast), or `JellyfinRemotePlayer` (Finnect).
- **Cast splits authority rather than sharing it.** The local player owns *what the queue is* (every
  track, full metadata); the receiver owns *what is playing* (item, position, play state, volume).
  `CastQueuePlayer` presents the local timeline and maps the receiver's `MediaStatus.currentItemId`
  through `MediaQueue`'s ordered receiver IDs. A pending handoff stays pinned to the requested local
  item until the receiver confirms its entity/content ID, so stale status from the replaced queue
  cannot move the app to a different song.
- Do **not** put media3's `CastPlayer`/`RemoteCastPlayer` timeline in front of the session. Cast
  queues are lazy: the receiver sends the current item complete and the rest as bare ids, and
  `MediaStatus.getQueueItems()` — which media3's `CastTimelineTracker` reads — is deprecated for
  exactly that reason. Doing so produced a queue of "unknown track / unknown artist" placeholders.
  Google's answer is `MediaQueue`, which media3's Cast player does not expose; fetching into it does
  not reach the timeline (verified on device).
- A handoff uses `RemoteMediaClient.queueLoad` once, with the local current track as receiver index
  zero and the captured position in the same request. Further 20-item batches use
  `queueInsertItems` when the receiver has eight items left; passive playback never reloads or
  slides the queue. Reorder/remove/jump use the receiver-assigned IDs in `MediaQueue`.
  `MediaQueueItem.setPreloadTime` is measured backwards from the *end* of the current item, so it
  must stay well under a track length; an hour made the receiver discard its whole queue in seconds.
- **Never drive Cast or Finnect transport from the UI.** `AccordCast` owns the Cast *session* only.
  A second transport path around the side of the session is what previously left the notification,
  lock screen, mini bar and other apps showing a paused phone during remote playback, and lost the
  session entirely when the Activity died. UI reads `instance` (the controller) and
  `Player.deviceInfo.playbackType`, never the receiver directly.
- Google Home's next/previous/shuffle/repeat are gated by the receiver's `supportedMediaCommands`,
  not by the sender. Receiver `6635F618` reported `commands=274447`, which omits every queue bit.

## Service automation — 2026-08-31

- Lidarr configuration is pulled automatically from the authenticated Jellyfin plugin at sign-in
  and process start. Fincord then discovers the root folder and profiles without requiring the
  user to open Lidarr Settings.
- Plugin-sourced credentials are labelled **Synced through plugin** in onboarding and Settings.
  Explicit manual Lidarr entry remains an override and must not be silently replaced when it
  differs from the plugin response.

### The shared alignment rules — 2026-09-08

- `.lrc-work/elrc_rules.py` is a **verbatim copy** of the canonical module in
  **Bananz0/fincord-lyrics-studio** (GPL-3.0, this maintainer). Do not edit it here:
  change the canonical file and re-copy, or the drift it exists to end starts again.
  Same convention as the vendored media3 classes above, and for the same reason - a
  build-time dependency on another repository would be worse than a file.
- It is imported by `enhanced_lrc.py` and copied into the image by the `Dockerfile`. It is
  pure Python with no dependencies, so it costs nothing but the file.
- **Why it exists.** The rules had three implementations - the Dockerised service, the
  batch runner and the desktop app - and they had already drifted into three different
  opinions about the same bug. Only the app clamped a line's end to the next line's start;
  only the runner checked the flash-word rate; only the service checked interior holds on
  short lines. The app therefore had the weakest gate of the three, which is backwards for
  the one a human is watching. The folded gate is the **union**, so every caller got
  stricter rather than the three being averaged.
- One deliberate behaviour change came out of it: `stamp` now **rounds** to the nearest
  centisecond. Two of the three truncated, which leans every cue up to 10 ms early - always
  the same direction, so it does not average out across a line the way rounding does.
- The Enhanced LRC format is why the gate reads word objects rather than the file it
  writes: the format stores only word *starts*, so a finished file cannot tell a two-second
  held note from a short word followed by silence. Judging output that way rejected good
  tracks for singing slowly. `stretched_word_in_rendered` is the file-level check, and is
  only for auditing a library whose alignment is long gone.

### The server plugin, and the discography cache — 2026-09-04

- The Jellyfin plugin is **Fincord** (GUID `0d22caeb-c386-47c4-868b-b390e41b5441`), source at
  `E:\Jellyfin.Plugin.Fincord`, deployed to
  `phastos:/opt/docker/appdata/jellyfin/plugins/Fincord_<version>/`. It serves
  `/Fincord/ClientConfig` (Lidarr's address and key) and
  `/Fincord/Discography/{musicbrainz-artist-id}`.
- **It was called "Spotify Mirror" up to 0.2.0.0.** Renamed at 0.3.0.0 because two of its three
  jobs have nothing to do with Spotify. A rename means a new GUID, so it installs beside the old
  plugin rather than upgrading it: `Spotify Mirror_0.2.0.0` and the old
  `Jellyfin.Plugin.SpotifyMirror.xml` are parked in
  `/opt/docker/appdata/jellyfin-removed-plugins-fincord-rename/`, and the config was copied to
  `Jellyfin.Plugin.Fincord.xml` so the Lidarr credentials survived. The config file is named for
  the **assembly**, not the plugin name or GUID.
- The discography endpoint caches MusicBrainz's **raw `release-groups` array**, unfiltered, one
  JSON file per artist under `/config/data/fincord/discography/`. Raw on purpose: which
  releases belong on an artist page is a client decision, so changing it is an app release rather
  than a server redeploy, and the cache never needs invalidating because that judgement moved.
- `JellyfinDiscography` prefers it and falls through to `MusicBrainzResolver` on 404/503/auth
  failure, remembering a 404 for the process's lifetime. The two paths return the same thing
  because both parse through `MusicBrainzResolver.artistReleaseGroups`.
- MusicBrainz allows one request per second **per client**, and every phone was its own client.
  The server is one client, stays awake, and keeps what it fetched.
- Three caches, and they are not redundant: `MusicBrainzResolver`'s in-memory map (this process,
  6 h), `DiscographyCache` on the phone's `filesDir` (instant first paint and offline, 30 days),
  and the plugin's (shared by every client on the server, 30 days by default).
- A release group with **any** secondary type used to be dropped, which reads as "no compilations"
  and is not: MusicBrainz files a mixtape as an Album with the secondary type Mixtape/Street, so
  the rule threw away the only album some artists have. Bhad Bhabie came back as 25 singles and no
  `15`. Only `EXCLUDED_SECONDARY_TYPES` is dropped now.
