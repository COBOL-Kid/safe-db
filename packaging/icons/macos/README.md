# macOS icon

`SafeDB.icon` is the Icon Composer source (Xcode 27.2). The other files are built from it on a Mac and committed, so CI needs no Xcode.

- `Assets.car`: the Liquid Glass icon for macOS 26 and later. `conveyor.conf` copies it to `Contents/Resources` and sets `CFBundleIconName = SafeDB`.
- `icon-*.png`: flat renders with the macOS squircle and margin. Conveyor builds the `.icns` that older macOS versions show from these.

Windows and Linux keep using `packaging/icons/icon-*.png`.

## Regenerate

After editing `SafeDB.icon` in Icon Composer:

```sh
cd packaging/icons/macos
tmp=$(mktemp -d)
xcrun actool SafeDB.icon --compile "$tmp" --platform macosx --minimum-deployment-target 12.0 \
  --app-icon SafeDB --output-partial-info-plist "$tmp/partial.plist"
cp "$tmp/Assets.car" .
ictool="$(xcode-select -p)/../Applications/Icon Composer.app/Contents/Executables/ictool"
for s in 16 32 64 128 256 512 1024; do
  "$ictool" SafeDB.icon --export-image --output-file "icon-$s.png" --platform macOS \
    --rendition Default --width "$s" --height "$s" --scale 1 --design-generation 26
done
```

`ictool` writes 16-bit Display P3 PNGs, and Conveyor rejects those. Convert them to 8-bit sRGB RGBA before committing, for example with Pillow:

```sh
python3 - <<'PY'
import glob, io
from PIL import Image, ImageCms
srgb = ImageCms.createProfile("sRGB")
for f in glob.glob("icon-*.png"):
    im = Image.open(f)
    src = ImageCms.ImageCmsProfile(io.BytesIO(im.info["icc_profile"]))
    ImageCms.profileToProfile(im.convert("RGBA"), src, srgb, outputMode="RGBA").save(f, optimize=True)
PY
```
