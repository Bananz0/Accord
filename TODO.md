# TODO

## Deploy the Last.fm signing proxy (Cloudflare Worker)

**Status:** the app supports it; the Worker does not exist yet.

### Why

Spotify and Google both use flows built for public clients — Spotify's client ID and Google's
Android OAuth client ID are safe to ship, because PKCE and package-signature binding replace the
secret. Last.fm is the odd one out: its **shared secret** signs every call, so an APK carrying it
hands the secret to anyone who unzips it.

Shipping it anyway is what every open-source scrobbler does and works fine. A proxy is better for a
distributed build: the secret stays on the server, it can be rotated without an app update, and the
same endpoint later hides a YouTube client secret if one is ever needed.

### How the app uses it

`LastFmCredentialStore.brokerUrl`, set either at build time (`lastfmBrokerUrl` in
`package.properties`) or in Settings → Scrobbling → Signing proxy. When present, `LastFmClient`
POSTs the parameters there instead of to Last.fm, and skips local signing entirely. With it set, the
API key and secret fields can be left empty.

### Contract

The Worker receives a `application/x-www-form-urlencoded` POST containing exactly the parameters
Last.fm expects, **minus** `api_key`, `api_sig` and `format`. It must:

1. Add `api_key` and `format=json`.
2. Compute `api_sig`: every parameter except `format` and `callback`, sorted by name, joined as
   `name+value` with no separators, the shared secret appended, then MD5, lowercase hex.
3. POST the result to `https://ws.audioscrobbler.com/2.0/`.
4. Return Last.fm's response body and status untouched — the app parses it exactly as if it had
   called Last.fm directly, so anything else silently breaks error handling.
5. **For `auth.getToken` only**, add `"api_key"` to the returned JSON. The approval URL the user is
   sent to carries the key as a query parameter, so a device holding no credentials cannot build one
   without it. The key is public — it is visible in that URL — so returning it gives nothing away;
   only the shared secret must stay on the server. Without this the browser opens
   `last.fm/api/auth?api_key=&token=…` and Last.fm rejects it.

### Sketch

```js
export default {
  async fetch(request, env) {
    if (request.method !== 'POST') return new Response('Method not allowed', { status: 405 })

    const params = Object.fromEntries(await request.formData())
    // format and callback are excluded from the signature by Last.fm; including either
    // produces "Invalid method signature" with no further explanation.
    delete params.format
    delete params.api_sig
    params.api_key = env.LASTFM_API_KEY

    const payload = Object.keys(params).sort().map(k => k + params[k]).join('') + env.LASTFM_SECRET
    const digest = await crypto.subtle.digest('MD5', new TextEncoder().encode(payload))
    params.api_sig = [...new Uint8Array(digest)]
      .map(b => b.toString(16).padStart(2, '0')).join('')
    params.format = 'json'

    const upstream = await fetch('https://ws.audioscrobbler.com/2.0/', {
      method: 'POST',
      body: new URLSearchParams(params),
    })

    // auth.getToken has to carry the key back so the app can build the approval URL.
    if (params.method === 'auth.getToken') {
      const body = await upstream.json()
      body.api_key = env.LASTFM_API_KEY
      return Response.json(body, { status: upstream.status })
    }
    return new Response(upstream.body, { status: upstream.status })
  },
}
```

> Workers do not expose MD5 through `crypto.subtle` — it is not in the WebCrypto spec. Use a small
> MD5 implementation (`js-md5`, or ~40 lines inline) instead of the call above.

### Before it is useful

- [ ] Write the Worker, with an MD5 that actually exists in the Workers runtime.
- [ ] Store `LASTFM_API_KEY` and `LASTFM_SECRET` as Worker secrets, not vars.
- [ ] Rate-limit per IP. The endpoint signs on behalf of anyone who can reach it, so an open one is
      a free signing oracle for the key.
- [ ] Restrict `method` to what the app actually calls: `auth.getToken`, `auth.getSession`,
      `track.updateNowPlaying`, `track.scrobble`, `artist.getSimilar`. Without this the proxy can be
      used to call anything the key is permitted to.
- [ ] Set `lastfmBrokerUrl` in `package.properties` and rebuild.

---

## Publishing gates for the other services

Neither is a code change; both are account work that has to happen before other people can sign in.

### Spotify — Extended Quota Mode

New apps are capped at **25 users**, each added by email in the dashboard. Beyond that, request
Extended Quota Mode. Needs a privacy policy and a UI following Spotify's branding rules; approval is
reviewed and not guaranteed. Playlist reading — all Accord uses — is unaffected by the 2025
endpoint restrictions.

- [x] Register the app, set `accord://spotify-callback` as a redirect URI.
- [x] Put the client ID in `package.properties` as `spotifyClientId` so users do not paste their own.
- [ ] Apply for Extended Quota Mode.

### YouTube Music — OAuth verification

There is no YouTube Music API. YouTube Data API v3 exposes the same playlists (including `LM` for
Liked Music). An Android OAuth client is bound to package name and signing certificate, so there is
no secret to ship — but `youtube.readonly` is a **sensitive** scope, and unverified apps are capped
at **100 users**.

- [ ] Google Cloud project, YouTube Data API v3 enabled.
- [ ] Android OAuth client for the release signing certificate (the debug certificate needs its own).
- [ ] OAuth consent screen with a privacy policy and homepage.
- [ ] Submit for sensitive-scope verification — allow weeks.

---

## Smaller things

- [ ] Floating mini-player: needs `PlayerBottomSheet` restructured onto upstream Accord's
      `FloatingPanelLayout` + `PreviewPlayer`.

---

## YouTube Music

Playlist **reading** is the only part worth building. See the OAuth verification checklist above.

Playing audio off YouTube is deliberately **not** implemented. Extracting streams violates the
YouTube API Services Terms (no separating audio from video, no playback outside their player), and
the practical consequences land on this project specifically: it gets the Google Cloud project
terminated, which kills the playlist reading in the same app, and it makes the build
undistributable. It is also the worse outcome — a stream gives no file, no offline copy, no library
entry. Lidarr gets the actual music instead.

## Apple Music

Blocked by Apple, not by effort. The API needs a developer token signed with a MusicKit private key,
which requires a paid Apple Developer Program membership. There is no official Android MusicKit SDK,
so obtaining a *user* token — the thing needed to read someone's own library playlists — has no
supported path on Android. Public playlists addressed by URL would work with a developer token
alone, if the membership exists.
