# Signing + Publish Setup (Anime Extensions)

This repo is the **anime** counterpart of
[`aniyomi-revived-extensions`](https://github.com/Blackyfi/aniyomi-revived-extensions) (manga).
It **reuses the same signing key**, so the cert SHA-256 fingerprint is identical and already
pinned in [`repo.json.template`](repo.json.template):

```
e4bbc0829bf2b1ef674b4772407c93898253620c5bebca3b3ddb372b6863ca9b
```

## Trust model (same as the manga repo)

aniyomi-revived's anime loader is **deny-by-default**: an extension APK is trusted **iff** its
signing-cert SHA-256 (lowercase, no colons) equals a configured repo's `signingKeyFingerprint`
(`TrustAnimeExtension.kt`; `AnimeExtensionLoader.kt` — feature `tachiyomi.animeextension`,
LIB_VERSION 12–16, our APKs are `14.x`). The per-extension "trust anyway" override was removed,
so **only what this key signs can load**. The key lives only in CI secrets; the public
fingerprint lives in `repo.json` for users to verify.

The CI (`.github/workflows/build_push.yml`) is **build-from-source only**: it builds each
extension, signs with the reused key, derives `sources[]` with the in-repo static inspector
(`.github/scripts/inspector.py` — **no** prebuilt `Inspector.jar`), generates
`index.min.json` + `repo.json`, and publishes to this repo's `repo` branch.

## One-time: set the four CI secrets (reusing your offline `ci.keystore`)

These cannot be copied from the manga repo (GitHub secrets are write-only). Run on the machine
that holds your offline `ci.keystore` (alias `arext`), authenticated as the repo owner:

```bash
R=Blackyfi/aniyomi-revived-anime-extensions

# Keystore, base64-encoded (the build decodes it back to signingkey.jks):
base64 -w0 ci.keystore | gh secret set SIGNING_KEY -R "$R"      # macOS: base64 -i ci.keystore | ...

gh secret set ALIAS              -R "$R" --body 'arext'
gh secret set KEY_STORE_PASSWORD -R "$R"   # paste the manga keystore password when prompted
gh secret set KEY_PASSWORD       -R "$R"   # paste the manga key password when prompted
```

> Secret names differ from the manga repo (`SIGNING_KEY`/`ALIAS`/`KEY_STORE_PASSWORD`/`KEY_PASSWORD`
> vs `SIGNING_KEYSTORE_B64`/`SIGNING_KEY_ALIAS`/...). The **values are the same** — the names are
> what this repo's build-logic (`PluginExtensionLegacy.kt`) and workflow read.

### Optional per-source API secrets

A handful of sources read API endpoints at build time. They are optional — unset means an empty
`BuildConfig` value (that source's feature degrades; the build still succeeds):

```
MEGACLOUD_API  KISSKH_API  KISSKH_SUB_API  KAISVA  TMDB_API
```

## Trigger the first build

Push any commit to `master`, or run the **CI** workflow via *Actions → CI → Run workflow*. The
first run builds all ~260 extensions (chunked), then bootstraps the `repo` branch.

## Add the repo in the app

After the first successful publish, in aniyomi-revived:
**Settings → Browse → Anime extension repos → Add**, paste:

```
https://raw.githubusercontent.com/Blackyfi/aniyomi-revived-anime-extensions/repo/index.min.json
```

An extension that installs as **trusted** (not "Untrusted") confirms the fingerprint chain
(keystore → `repo.json` → APK signature) is correct end to end.

## Verify the published fingerprint

```bash
curl -s https://raw.githubusercontent.com/Blackyfi/aniyomi-revived-anime-extensions/repo/repo.json \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["meta"]["signingKeyFingerprint"])'
# must print: e4bbc0829bf2b1ef674b4772407c93898253620c5bebca3b3ddb372b6863ca9b
```

## Syncing future upstream (yuzono) changes

This is an independent fork; `upstream` points at yuzono for manual syncs:

```bash
git fetch upstream
git merge upstream/master      # resolve conflicts (esp. .github/workflows/, repo.json.template)
git push origin master         # CI rebuilds + republishes
```
