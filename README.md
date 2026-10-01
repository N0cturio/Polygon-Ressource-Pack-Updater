# PolygonPackUPD

A small client-side Fabric mod for Minecraft 1.21.1 that keeps a resource pack up to date on players' machines. At each launch it checks a small manifest file, and if the pack changed, it downloads the new one and enables it.

## How to use

### 1. Prepare your pack

Zip the **contents** of your pack folder, so `pack.mcmeta` is at the root of the zip. Don't zip the folder itself.

### 2. Generate the manifest

Drag your pack zip onto `make-manifest.bat`. It creates `<name>.manifest.json` next to the zip. The file holds the pack's SHA-1 and size.

You can also put the zip next to the script and name it `ResourcePack.zip`, then double-click the `.bat`. Or run `.\make-manifest.ps1 -Zip "C:\path\to\MyPack.zip"` from PowerShell.

### 3. Upload both files

Upload the zip and the manifest to a host that serves files to anonymous clients. Google Drive works ("Anyone with the link"), and its share links are converted automatically. Any other host works too, as long as you give it a direct HTTPS download link.

When the pack changes, regenerate the manifest and upload both files over the old ones with the same names, so the links stay the same. Upload the zip first, then the manifest.

### 4. Configure and build

Edit `gradle.properties`:

```
packShareUrl=<link to your pack zip>
packManifestUrl=<link to your manifest file>
packFileName=ResourcePack.zip
```

`packFileName` is the name the pack gets in each player's `resourcepacks` folder. Then build:

```
gradlew.bat build
```

The jar is in `build/libs/`. The links are built into it, so players configure nothing. Put the jar in your modpack's `mods` folder. Requires Minecraft 1.21.1 with Fabric.
