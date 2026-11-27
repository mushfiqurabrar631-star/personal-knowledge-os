# Reproducible build

Goal: anyone with the same source commit and the same toolchain gets the same unsigned release APK contents, so a
signed APK can be checked against the public source.

## Pinned toolchain
| Item | Version | Where pinned |
|---|---|---|
| JDK | Temurin 21 (CI); JDK 17+ works | `.github/workflows/android.yml` |
| Gradle | 9.9.2 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.5.1 | `gradle/libs.versions.toml` |
| Kotlin | 2.4.21 | `gradle/libs.versions.toml` |
| compileSdk / targetSdk | 37 (platform `android-37.2`), minSdk 26 | `app/build.gradle.kts` |
| Build tools | 37.0.0 | Android SDK |
| All libraries | exact versions (no `+` ranges) | `gradle/libs.versions.toml` |
| Tesseract Bengali model | tessdata 3.04.00 `ben.traineddata`, 15,552,533 bytes, SHA-256 `157826baf48063547b7793e1bde0c9f82d4843488fb5c7e02155f662a708e860` | checked by the app at first use |

## Steps
```bash
git clone https://github.com/mushfiqurabrar631-star/personal-knowledge-os && cd personal-knowledge-os
git checkout <release tag or commit>
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew --no-daemon clean :app:assembleRelease      # unsigned APKs (no PKOS_RELEASE_* variables set)
./gradlew --no-daemon :app:bundleRelease              # unsigned AAB, separate run
sha256sum app/build/outputs/apk/release/*.apk
```

## Comparing with a signed APK
A signature changes the file hash, so compare the **contents** without the signature:
```bash
mkdir a b && unzip -q app-arm64-v8a-release-unsigned.apk -d a && unzip -q signed-app-arm64-v8a-release.apk -d b
rm -rf a/META-INF b/META-INF && diff -r a b && echo "same contents"
```
(`diffoscope` gives a more detailed report if installed.)

## Known limits
- The Gradle wrapper has no `distributionSha256Sum` yet. Owner step: copy the official checksum for
  `gradle-9.9.2-bin.zip` from gradle.org/release-checksums into `gradle-wrapper.properties`
  (`distributionSha256Sum=<hex>`). It could not be fetched from the build sandbox, and a guessed value is not added.
- Reproducibility was checked by building the release twice from a clean tree on the build machine
  (result recorded in `docs/phase2-report.md`); it is not yet checked across different machines/OSes.
- Different JDK vendors or versions can change R8 output; use Temurin 21 to compare.
