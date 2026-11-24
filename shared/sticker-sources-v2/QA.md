# Sticker variation QA

The v2 pack uses eight distinct, ordinary reaction-photo subjects/scenes, mapped in [prompts.json](prompts.json) and [references/sources.json](references/sources.json). Keep the eight existing sticker IDs and package only the final GIF files under `shared/resources/drawable/`. Keep downloaded reference images, their page/image URLs, provenance notes, and generation prompts in this source folder. Do not treat a source page as proof of a redistribution licence; record the licence status honestly.

For each source, record its sticker ID, subject/template, source page, direct image URL, downloaded reference filename, and any stated licence. Check that reference imagery is an ordinary nonsexual appearance. A recognisable meme name is not a reason to reuse explicit source footage.

The existing GIF contract remains 320×320 pixels, ten frames, 140 ms per frame, with the caption baked into the image. `tools/EncodeStickerGif.java` assembles existing generated six-frame PNG contact sheets into that format. Preserve the first pack's PNGs in `shared/sticker-sources/`.

## Visual checks

Compile the read-only contact-sheet helper and inspect the actual packaged first frames:

```powershell
javac -d build/sticker-tools tools/StickerVisualQa.java
$gifFiles = @(Get-ChildItem shared/resources/drawable/sticker_*.gif | ForEach-Object { $_.FullName })
java -cp build/sticker-tools StickerVisualQa build/sticker-previews/varied-pack-qa.png @gifFiles
```

The helper reports near-duplicate pairs after cropping off the bottom 30%, averaging into a 16×16 RGB grid, and removing channel-wide exposure/tint shifts. A pairwise normalized RMS distance below 0.07 suggests that a base photo was reused under another caption. In the original same-trio pack, 21 of the 28 pairs were below that conservative threshold, with a minimum distance of 0.0358.

`StickerGifTest.photoVariationsDoNotReuseTheSameBaseImageWithDifferentCaptions` applies the same check to the runtime-decoded bundled GIFs. It complements the existing timing, frame-change, preview, cache, and inactive-animation checks. It does not recognise people, prove that subjects differ, establish licensing, or replace visual inspection. Review every contact-sheet image and the source provenance before shipping.

After rebuilding or replacing the GIFs, run:

```powershell
.\gradlew.bat :desktop:test --tests app.tunes.StickerGifTest --tests app.tunes.SocialRenderTest :app:compileDebugKotlin
```

The variation test rejects the old reused-photo pack if it is accidentally restored.

The final v2 encoded GIF pack was visually inspected on 2026-11-13: eight distinct subjects/scenes, accurate captions, and readable first-frame previews. The Java helper measured a minimum caption-free distance of 0.1535, above the 0.07 near-duplicate threshold. The actual packaged GIF first-frame contact sheet is at `build/sticker-previews-v2/varied-pack-qa.png` after this build. All eight source sheets were inspected for sequential frame changes before encoding. The encoded files total 3,583,956 bytes.

Final build: `:desktop:test` (StickerGifTest and SocialRenderTest), `:app:testDebugUnitTest`, `:app:assembleRelease`, and `:desktop:createDistributable` passed. Five desktop tests and 34 Android JVM tests passed (39 total). Desktop render previews were inspected at both chat and pack sizes. Native Android device playback still needs device QA.
