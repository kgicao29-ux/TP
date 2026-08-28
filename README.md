# TramPhim — CloudStream extension

CloudStream provider for **Trạm Phim** ([tramphim3.org](https://tramphim3.org)) — phim
vietsub / thuyết minh / lồng tiếng, phim lẻ, phim bộ, phim chiếu rạp, hoạt hình.

Written in Kotlin, following the same project structure as
[kgicao29-ux/P4K](https://github.com/kgicao29-ux/P4K) (recloudstream plugin template).

## Features

- **Main page** — 7 sections scraped from the site's server-rendered list pages:
  Phim lẻ, Phim bộ, Phim chiếu rạp, Phim song ngữ, Phim lồng tiếng, Hoạt hình, Phim sắp chiếu
  (all paginate via `?page=N`).
- **Search** — the site's JSON endpoint `/api/search?keyword=…`.
- **Detail** — full metadata (poster, backdrop, plot, cast, genres, countries, year, TMDB score,
  duration, trailer, recommendations) parsed from the Next.js RSC payload embedded in each
  `/phim/{slug}` page.
- **Streams** — every server the site exposes:
  - servers embedded on the page (VSmov and friends),
  - backup servers from `/api/backup-servers?slug=…&tmdb_id=…` → **Nguonc**, **KKPhim/PhimApi**,
    **ViCDN**, **VSmov**,
  - direct `link_m3u8` playlists are played natively (downloadable),
  - `VSmov` embeds (`v{N}.streamvsmov.com/video/{uuid}`) are resolved server-side to
    `{base}/stream/{uuid}/master.m3u8` **including their WebVTT subtitles** (Vietnamese/English),
  - unknown embeds are delegated to CloudStream's built-in extractors.
- Movies are typed `Movie`/`AnimeMovie`, series `TvSeries`/`Anime` (animation categories are
  detected automatically).

## Install

Add this repository in CloudStream using:

```
https://raw.githubusercontent.com/kgicao29-ux/TramPhim/master/repo.json
```

Then install or update **Trạm Phim** from the repository list.

> If you fork this repo, change `pluginLists` in `repo.json` (and `GITHUB_REPOSITORY` is picked
> up automatically by the Gradle plugin in CI) to point at your own `builds` branch.

## Build

Requirements: JDK 17, Android SDK (platform 35).

```
./gradlew make            # produces TramPhim/build/TramPhim.cs3
./gradlew makePluginsJson # produces build/plugins.json
```

Pushing to `master` triggers the GitHub Actions workflow which publishes the `.cs3` and
`plugins.json` to the `builds` branch.

## How the site works (notes)

tramphim3.org is a Next.js (App Router) frontend aggregating several Vietnamese movie
back-ends. Everything this provider needs:

| Purpose | Source |
|---|---|
| Listing pages | `/phim-le`, `/phim-bo`, `/phim-chieu-rap`, `/phim-song-ngu`, `/phim-long-tien`, `/hoat-hinh`, `/phim-sap-chieu` — rendered `<a class="movie-card">` cards |
| Search | `GET /api/search?keyword=…&limit=24` → `{items:[FilmObject…]}` |
| Film detail | `GET /phim/{slug}` → RSC flight data in `self.__next_f.push([1,"…"])` chunks; the film object is `"movie":{…}` (matched by slug) followed by `"episodes":[{server_name, server_data:[{name, link_m3u8, link_embed}]}]` |
| Backup servers | `GET /api/backup-servers?slug=…&tmdb_id=…` → `{nguoncEpisodes, phimApiEpisodes, vsmovEpisodes, vicdnEpisodes}` |
| VSmov player | `GET v{n}.streamvsmov.com/video/{uuid}` → `baseUrl`, `videoHash`, `signedMasterUrl`, `subtitles[]` → `{base}/stream/{uuid}/master.m3u8` + `.vtt` |
| Posters | cards use `wsrv.nl/?url=<encoded>` proxy — unwrapped to the original image URL |

Note: `streamc.xyz` and `vicdn.cc` embeds serve AES-encrypted HLS that only their in-page JS
can decode, so those servers are skipped in favour of the direct m3u8 servers above. kkphim
m3u8 URLs are minted per API call and expire within minutes — the extension always fetches
them at play time, so they are fresh; if one 404s, just reopen the episode.
