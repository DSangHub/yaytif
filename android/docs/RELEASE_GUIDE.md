# Yaytif Android — Release Signing & Play Store Delivery Guide

This guide documents how to generate a Play Store upload keystore, configure local or CI signing without committing secrets, build and verify the signed R8-hardened Android App Bundle (`.aab`), and upload to Google Play Console.

---

## 1. Security Rules (Non-Negotiable)

- **NEVER** commit `android/keystore.properties`, `*.jks`, `*.keystore`, `*.p12`, or `*.pepk` files to git (all are ignored in `.gitignore` and `android/.gitignore` and checked by `android/scripts/verify-i18n.py`).
- Store the production upload keystore in an encrypted secrets vault (e.g., 1Password / GCP Secret Manager / GitHub Actions Encrypted Secrets) with an offline backup.
- Enroll in **Play App Signing** in Google Play Console so Google manages the final app signing key and your local key acts solely as a rotatable upload key.

---

## 2. Generate a Local Upload Keystore

Generate a 4096-bit RSA upload keystore **outside** the repository (for example in `~/.yaytif-keys/`):

```bash
mkdir -p ~/.yaytif-keys
chmod 700 ~/.yaytif-keys

keytool -genkeypair \
  -v \
  -keystore ~/.yaytif-keys/yaytif-upload.jks \
  -storetype PKCS12 \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -alias yaytif-upload \
  -dname "CN=Yaytif Release Upload, OU=Mobile Engineering, O=Yaytif, L=San Francisco, S=CA, C=US"
```

Record the keystore password, key alias (`yaytif-upload`), and key password in your team password manager immediately.

---

## 3. Configure Release Signing

`android/app/build.gradle.kts` resolves release signing credentials in the following priority order:

1. **Environment variables (recommended for CI/CD):**
   - `YAYTIF_KEYSTORE_PATH`
   - `YAYTIF_KEYSTORE_PASSWORD`
   - `YAYTIF_KEY_ALIAS`
   - `YAYTIF_KEY_PASSWORD`
2. **Untracked `android/keystore.properties` (for local release builds):**

```bash
cp android/keystore.properties.example android/keystore.properties
chmod 600 android/keystore.properties
```

Edit `android/keystore.properties`:

```properties
storeFile=/home/<user>/.yaytif-keys/yaytif-upload.jks
storePassword=<YOUR_STORE_PASSWORD>
keyAlias=yaytif-upload
keyPassword=<YOUR_KEY_PASSWORD>
```

### Configuring GitHub Actions Secrets
Base64-encode the upload keystore for CI storage (never paste into logs or issues):

```bash
base64 -w 0 ~/.yaytif-keys/yaytif-upload.jks
```

Configure the following repository/environment secrets in GitHub Settings → Secrets and variables → Actions:
- `YAYTIF_KEYSTORE_BASE64`
- `YAYTIF_KEYSTORE_PASSWORD`
- `YAYTIF_KEY_ALIAS`
- `YAYTIF_KEY_PASSWORD`

---

## 4. Build & Verify the Signed Release AAB

Run the full local verification pipeline from the repository root:

```bash
# 1. Verify 13-locale i18n parity, XML well-formedness, privacy rules, and zero TODO/FIXME
python3 android/scripts/verify-i18n.py

# 2. Run unit tests, Android Lint, debug APK, and signed R8-minified release AAB
cd android
YAYTIF_KEYSTORE_PATH=~/.yaytif-keys/yaytif-upload.jks \
YAYTIF_KEYSTORE_PASSWORD="<YOUR_STORE_PASSWORD>" \
YAYTIF_KEY_ALIAS="yaytif-upload" \
YAYTIF_KEY_PASSWORD="<YOUR_KEY_PASSWORD>" \
./gradlew testDebugUnitTest lintDebug assembleDebug bundleRelease --no-daemon
```

### Expected Release Artifacts
- Signed App Bundle: `android/app/build/outputs/bundle/release/app-release.aab`
- R8 Deobfuscation Mapping: `android/app/build/outputs/mapping/release/mapping.txt`

> **Note on AAB Language Splitting:** `bundle.language.enableSplit` is explicitly set to `false` in `android/app/build.gradle.kts` so all 13 bundled locales (`en`, `pt-BR`, `es`, `fr`, `sw`, `yo`, `ha`, `ig`, `zu`, `af`, `ar` RTL, `hi`, `id`) ship inside the base APK. This guarantees instant offline in-app language switching via `AppCompatDelegate.setApplicationLocales` on mobile connections in LATAM and Africa. Density and ABI splits remain enabled to keep download sizes compact.

---

## 5. Verify Signatures Before Upload

### Verify AAB Signature with `jarsigner`
```bash
jarsigner -verify -verbose -certs \
  android/app/build/outputs/bundle/release/app-release.aab
```
Confirm the output ends with `jar verified.` and lists your expected `CN=Yaytif Release Upload` certificate.

### Optional: Build & Verify Universal APK from the AAB (`bundletool` + `apksigner`)
```bash
bundletool build-apks \
  --bundle=android/app/build/outputs/bundle/release/app-release.aab \
  --output=/tmp/yaytif-release.apks \
  --mode=universal \
  --ks=~/.yaytif-keys/yaytif-upload.jks \
  --ks-key-alias=yaytif-upload

unzip -p /tmp/yaytif-release.apks universal.apk > /tmp/yaytif-universal.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs /tmp/yaytif-universal.apk
rm -f /tmp/yaytif-release.apks /tmp/yaytif-universal.apk
```

---

## 6. Upload to Google Play Console (Internal Testing Track)

1. Increment `versionCode` and `versionName` in `android/app/build.gradle.kts` for every new upload.
2. Open **Google Play Console → Yaytif → Testing → Internal testing → Create new release**.
3. Upload `android/app/build/outputs/bundle/release/app-release.aab`.
4. Upload `android/app/build/outputs/mapping/release/mapping.txt` under **App bundle explorer → Downloads → Deobfuscation file** (so crash reports and ANRs are automatically symbolicated).
5. Verify the pre-launch report and test on physical devices across LATAM/Africa locales and Arabic RTL before promoting from Internal testing → Closed testing → Production.
