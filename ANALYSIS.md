# tramphim3.org — site analysis (2026-08-28)

Findings from reverse-engineering the site while building the CloudStream extension.

## Stack

Next.js 14/15 (App Router, RSC streaming) front-end, custom back-end aggregating
four content sources:

| Code name | Back-end | Player |
|---|---|---|
| OP/NC | `phim.nguonc.com/api` | direct m3u8 / embeds |
| KK | `phimapi.com` (kkphim mirrors `v7.kkphimplayer7.com`) | direct m3u8 + `player.phimapi.com` embed |
| VS | `vsmov.com/api` (`v5.streamvsmov.com` …) | custom JW page → direct HLS |
| VI | `vicdn.cc` | custom JW page, AES playlist |

Client config discovered in the JS bundles:

```js
api: {
  nguoncUrl: "https://phim.nguonc.com/api",
  phimapiUrl: "https://phimapi.com",
  vicdnUrl:   "https://vicdn.cc",
  vsmovUrl:   "https://vsmov.com/api",
  tmdbUrl:    "https://api.themoviedb.org/3",
}
```

## Endpoints used by the extension

| Endpoint | Purpose |
|---|---|
| `GET /{section}?page=N` | server-rendered lists; cards are `<a class="movie-card" href="/phim/{slug}">` with `<h3>` title, `<img src>` (wsrv.nl proxy) |
| `GET /api/search?keyword=&limit=` | JSON search (`items[]` film objects) |
| `GET /phim/{slug}` | detail page; film object + episodes live in the RSC payload (`self.__next_f.push([1,"…"])` chunks → `"movie":{…}` matched by slug, then `"episodes":[{server_name,server_data[]}]`) |
| `GET /api/backup-servers?slug=&tmdb_id=` | JSON: `nguoncEpisodes`, `phimApiEpisodes`, `vsmovEpisodes`, `vicdnEpisodes` (needs tmdb_id to match external catalogs) |

Film object fields: `name, origin_name, content, type(single|series), status,
poster_url, thumb_url, trailer_url, time, episode_current, episode_total, quality,
lang, year, actor[], director[], category[{id,name,slug}], country[…], tmdb{id,type,
season,vote_average}, imdb{id,vote_average}, source_slugs{nguonc,kkphim,vsmov,vicdn}`.

## Players

- **VSmov** `v{N}.streamvsmov.com/video/{uuid}` — the page contains
  `const baseUrl=…; const videoHash=…;` and `playerOptions{subtitles[], enableSignedUrl, signedMasterUrl}`.
  Playlist: `{baseUrl}/stream/{uuid}/master.m3u8` (open, verified). Subtitles are plain
  WebVTT at `{base}/video/{uuid}/subtitle/{name}.vtt` with `code` "vie"/"eng".
  → implemented natively in the provider.
- **streamc.xyz** `embed{N}.streamc.xyz/embed.php?hash=` — the m3u8 behind `/{sUb}` is
  **not standard HLS**: `#ENC-AESGCM;iv=…` headers, body is base64 ciphertext, decoded by
  a heavily-obfuscated player.js (RC4 string array + WebCrypto AES-GCM). → skipped.
- **ViCDN** `vicdn.cc/{slug}-{ep}` — JW page whose playlist is fetched/decrypted
  client-side (`SHA-256 → importKey → Decrypt`, key string `vicdn_cc_key`). → skipped
  (delegated to built-in extractors, which currently don't support it).
- **kkphim** `v7.kkphimplayer7.com/…/index.m3u8` — plain HLS, but URLs are **short-lived**
  (minted per API call, expire within minutes). The extension fetches them at play time,
  so they are fresh. If a link 404s, retrying the episode re-mints it.

## Notes / gotchas

- Images go through `wsrv.nl/?url=<urlencoded>` — unwrap `url` param for originals.
- `/api/backup-servers` without `tmdb_id` returns empty lists for most films.
- Films with neither TMDB id nor VSmov server (some custom uploads, e.g.
  `mv-696393-1`) are vicdn/streamc-only → no playable links in the extension.
- `/phim-le?page=999` returns an empty grid (no 404) → `hasNext=false` on empty page works.
- `phim.nguonc.com/api` itself returns `403` for datacenter IPs; the site's
  `/api/backup-servers` proxies it server-side, so the extension never calls nguonc directly.

## Verification log (sandbox)

- list parsing: phim-le 48, phim-bo 48, hoat-hinh 32 cards/page ✓
- search API: 24 items for "one piece" ✓
- film object + episodes extraction: movie, series, anime, `mv-*`, `tv-*` pages ✓
- VSmov master.m3u8 + VTT subtitles verified playable (`#EXTM3U`) ✓
- kkphim m3u8 verified playable when fresh ✓
- build: `./gradlew make makePluginsJson` → `TramPhim.cs3` + `plugins.json` ✓ (JDK 17, AGP 8.7.3, cloudstream gradle plugin 81b1d424d2)
