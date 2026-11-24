# Jomok and Jawir GIF stickers

The eight existing chat IDs use eight different reaction-photo subjects with Indonesian captions. The pack keeps the bold outlined caption style of the user-provided [sticker_4.png](../sticker_4.png), while giving each sticker its own face, setting, and gesture. The IDs and chat database payloads remain unchanged.

The web photographs are visual references. The **built-in image_gen.imagegen tool** created the six-frame motion from each reference using the exact [v2 prompts](sticker-sources-v2/prompts.json); these GIFs are generated animations, not excerpts of the original videos. Each source PNG is a six-frame, three-column/two-row contact sheet. The v2 contact sheets, downloaded reference photographs, and provenance records are kept in `shared/sticker-sources-v2/` and are not bundled in the apps. The first pack's PNGs and prompts remain in [sticker-sources/](sticker-sources/) for provenance; rebuild the current pack from v2.

| Chat ID | Caption | Reference subject | Bundled GIF |
| --- | --- | --- | --- |
| `jomok-waduh` | WADUH! | Steve Harvey, shocked studio reaction | [GIF](resources/drawable/sticker_jomok_waduh.gif) |
| `jomok-santuy` | SANTUY | Perrell Brown / Dreamy, relaxed candid-talk portrait | [GIF](resources/drawable/sticker_jomok_santuy.gif) |
| `jomok-wkwk` | WKWKWK | Eddie Murphy, laughing interview reaction | [GIF](resources/drawable/sticker_jomok_wkwk.gif) |
| `jomok-bruh` | BRUH... | Conceited, skeptical side-eye with red cup | [GIF](resources/drawable/sticker_jomok_bruh.gif) |
| `jawir-gas` | GASKEUN! | Khaby Lame, open-palmed gesture | [GIF](resources/drawable/sticker_jawir_gas.gif) |
| `jawir-ngopi` | NGOPI DULU | Morgan Freeman, coffee toast | [GIF](resources/drawable/sticker_jawir_ngopi.gif) |
| `jawir-siap` | SIAP, BOS! | Kayode Ewumi / Roll Safe, temple tap | [GIF](resources/drawable/sticker_jawir_siap.gif) |
| `jawir-mantap` | MANTAP! | Denzel Washington, relieved smile | [GIF](resources/drawable/sticker_jawir_mantap.gif) |

## Reference sources

References were retrieved on 2026-11-13. These are the exact page and image URLs used for the v2 pack. Public availability does not establish an open reuse license; no open reuse license was verified for these photographs. The consolidated [source records](sticker-sources-v2/references/sources.json) retain the provenance and license status.

| Reference file | Source page | Downloaded image | Selection |
| --- | --- | --- | --- |
| `steve-harvey.jpg` | [When you realize](https://imgflip.com/memetemplate/34449841/When-you-realize) | [JPEG](https://i.imgflip.com/kido1.jpg) | Burgundy suit and orange studio; crop black letterboxing. |
| `perrell-candid-talk-19.jpg` | [Dreamybull Candid Talk 19](https://www.youtube.com/watch?v=Y6xO_9tFo6w) | [YouTube thumbnail](https://i.ytimg.com/vi/Y6xO_9tFo6w/maxresdefault.jpg) | Fully clothed portrait on the right; crop promotional text. |
| `eddie-murphy-laugh.jpg` | [Nicki Swift article](https://www.nickiswift.com/343975/heres-how-much-eddie-murphy-is-really-worth/) | [JPEG](https://www.nickiswift.com/img/gallery/heres-how-much-eddie-murphy-is-really-worth/he-got-his-start-doing-stand-up-1661461036.jpg) | Laughing interview portrait in gray suit. |
| `conceited-reaction.jpg` | [Reaction-meme repost](https://www.pinterest.com/pin/577868195930631553/) | [JPEG](https://i.pinimg.com/736x/56/30/df/5630dfdcbfc9dff43552ed7f7fe903ec.jpg) | Foreground man with cap, tan vest, and red cup. |
| `khaby-lame.jpg` | [Khaby Lame template](https://www.meme-arsenal.com/create/template/11320744) | [JPEG](https://images.meme-arsenal.com/3c5cfc278afd91526388d752c46a6933.jpg) | Purple hoodie and open palms. |
| `morgan-freeman-coffee.jpg` | [Morgan Freeman fronter Evergood](https://www.kreativtforum.no/arbeider/morgan-freeman-fronter-evergood) | [JPEG](https://s3.eu-north-1.amazonaws.com/kreativt-forum/images/65263-50e66-original.jpg) | Coffee cup, blazer, blue shirt, and city backdrop. |
| `roll-safe.jpg` | [Smart Black Rapper template](https://imgflip.com/memetemplate/123433318/Smart-Black-Rapper) | [JPEG](https://i.imgflip.com/21hlra.jpg) | Smiling temple tap in black leather jacket. |
| `denzel-relieved.png` | [Dazel relieved template](https://imgflip.com/memetemplate/355463346/Dazel-relieved) | [PNG](https://i.imgflip.com/5vmt9u.png) | Only the lower-right smiling photograph; omit the other panel and blank area. |

The Perrell reference comes from an ordinary, fully clothed talk thumbnail on The Real Dreamy YouTube channel. The second retained candidate, `perrell-candid-talk-22.jpg`, is byte-identical to the selected thumbnail and is not counted as another variation. No sexual footage is used.

## Encoding and playback

Every GIF is 320x320 pixels, has ten frames at 140 milliseconds per frame, and repeats indefinitely. The eight encoded files total 3,583,956 bytes (about 3.6 MB). The deterministic [Java encoder](../tools/EncodeStickerGif.java) scales the generated frames and assembles the sequence `0,1,2,3,4,5,4,3,2,1`. It does not generate or repaint artwork. The loader rejects encoded GIFs larger than 2 MB, and the decoded-pixel cache is capped at 16 MB.

To rebuild the current GIF files with JDK 17 from the repository root:

```powershell
New-Item -ItemType Directory -Path build/sticker-tools -Force | Out-Null
javac -d build/sticker-tools tools/EncodeStickerGif.java
Get-ChildItem shared/sticker-sources-v2 -Filter '*.png' | ForEach-Object {
    $gifOutput = 'shared/resources/drawable/sticker_' + $_.BaseName.Replace('-','_') + '.gif'
    java -cp build/sticker-tools EncodeStickerGif $_.FullName $gifOutput 320 140
}
```

Android decodes the packaged GIF with `Movie`; Windows uses the bundled Skia GIF codec. Decoding runs away from the UI thread, and chat playback pauses when the sticker leaves the visible screen or the app becomes inactive. The sticker picker displays still previews. A fallback remains available if an asset cannot be decoded.

## Verification

Create a first-frame contact sheet of the actual bundled GIFs with the read-only [visual QA helper](../tools/StickerVisualQa.java):

```powershell
javac -d build/sticker-tools tools/StickerVisualQa.java
$gifFiles = @(Get-ChildItem shared/resources/drawable/sticker_*.gif | ForEach-Object { $_.FullName })
java -cp build/sticker-tools StickerVisualQa build/sticker-previews/varied-pack-qa.png @gifFiles
```

Inspect the sheet for eight distinct reactions, accurate captions, readable outlines, and consistent crops. The helper also compares the top 70% of each frame, excluding the caption area, and flags near-duplicate photo content. The matching regression test prevents the old same-photo/different-caption pattern from returning; this image-distance check complements human review and does not identify people or establish licensing. Details and the regression commands are in [v2 QA notes](sticker-sources-v2/QA.md).

`StickerGifTest` checks every packaged GIF, changing frame pixels, timing, photo variation, bounded decoding/cache behavior, and active-versus-background rendering. `SocialRenderTest` also renders the packs and chat bubbles. Compilation checks Android integration; real-device animation still needs device QA.
