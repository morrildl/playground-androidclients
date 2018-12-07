# Warden Clients

This is a collection of client scripts that replace parts of the Android (AOSP) build system tools that
generate and sign flashable system images and OTA images. These scripts implement support for
remote-signing of binaries via the Playground Warden signing service.

Generally they function by being command-line-flag compatible with the standard AOSP counterparts,
but instead of using private keys locally on disk they upload binaries to Warden over HTTPS to be
signed. You instruct the AOSP top-level build/OTA scripts to use these alternatives via command-line
flags to those tools.

Currently only A/B (i.e. Android Nougat and later) builds are supported. 

Special thanks to Playground Global, LLC for open-sourcing this software. See `LICENSE` for details.

# Files

Three of these scripts are in Python 2.7:

* `boot_signer` replaces `system/extras/verity/boot_signer` in the AOSP tree. It signs boot images
  per the [https://source.android.com/security/verifiedboot/](Android Verified Boot spec.)
* `payload_signer` replaces a direct call to `openssl pkeyutl` in the
  `build/tools/releasetools/ota_from_target_files` script.
* `verity_signer` replaces `system/extras/verity/verity_signer` in the AOSP tree. It signs Linux
  `dm-verity` [https://source.android.com/security/verifiedboot/dm-verity](non-boot verified
  partition images.)

These require no compilation to use, but do require the `python-requests` package to be installed on
the system where you sign builds.

The fourth script is a Java program that replaces `build/tools/signapk`:

* `java/global/playground/warden/SignAPK.jar`

This file is compiled via its `Android.mk` file, and packaged as a JAR file. Due to the way the
scripts invoke it, it must be installed underneath the `out/host/<os>/` tree, which its `Android.mk`
build configuration handles.

# Configuration

The tools are configured via environment variables.  It is possible to pass custom command-line
arguments to these tools down through the top-level tools strictly via command-line, but doing so
becomes awkward enough that for simplicity these tools are configured via environment variables. See
`sample-env.sh` for an example.

The variables are:

* `WARDEN_HOSTNAME` - hostname or IP address of where you have Warden running
* `WARDEN_PORT` - the port where Warden is running. This parameter is required; there is no default.
* `WARDEN_PRODUCT` - the product ID (in Android parlance) -- basically device name -- you are building. e.g. "sailfish" for Google Pixel
* `WARDEN_KEYSET` - the set of keys to use, e.g. "devkeys" or "releasekeys"
* `WARDEN_CLIENT_CERT` - the PEM-encoded X.509 client certificate registered with Warden that identifies this machine/user
* `WARDEN_CLIENT_KEY` - the PEM-encoded PKCS#8 private key corresponding to the above certificate (note: this is not the default `openssl` format)
* `WARDEN_SERVER_CERT` - the PEM-encoded X.509 certificate that the Warden server will present; used to verify you are talking to the correct server

The first 4 vars are used to construct the base URL to use for a given signing operation:

    https://${WARDEN_HOSTNAME}:${WARDEN_PORT}/sign/${WARDEN_PRODUCT}-${WARDEN_KEYSET}-

For a given signable object, the scripts append an additional identifier. When doing a `dm-verity`
signature, the string "verity" is applied; boot images get "boot"; Brillo payload operations get
"payload"; and APK signature operations append the specific key class used for the app: one of
"media", "shared", "platform", or "releasekey".

The purpose of appending the identifier is to allow each to be configured as an endpoint on Warden
using a separate key, without requiring a ton of URL query parsing. The Android build and signing
scripts do not allow for much control over arguments passed to these scripts, so these clients infer
what they need to know and apply standard patterns.

As an example, if your device name is `sailfish`, you are signing your `dev` configuration, and your
Warden instance is running locally (which you should not do except for testing), you will need to
configure your Warden instance with these endpoints:

    https://localhost:9000/sign/sailfish-dev-boot
    https://localhost:9000/sign/sailfish-dev-verity
    https://localhost:9000/sign/sailfish-dev-payload
    https://localhost:9000/sign/sailfish-dev-media
    https://localhost:9000/sign/sailfish-dev-shared
    https://localhost:9000/sign/sailfish-dev-platform
    https://localhost:9000/sign/sailfish-dev-releasekey

These 7 endpoints represent a typical key configuration for a single build config for a particular
product. Note that each can (and should) have a separate key (although typically boot, verity,
payload and releasekey are all the same key.)

The `WARDEN_PRODUCT` parameter allows you to have separate sets of keys for each device. That is,
you should not reuse signing keys across multiple devices; you should instead generate a set of 5
keys and 7 endpoints for each device.

The `WARDEN_KEYSET` parameter allows you to separate your production images from development images.
For instance, you typically have internal dogfood users using non-public builds. If one of these
builds were to leak, you don't want it to be immediately installable on top of your real users'
production builds. To achieve this, you can define 2 (or more) sets of keys -- `dev` and `prod` for
instance. In this way you can OTA builds to your dogfood users in the usual way with minimal risk that
these will accidentally or intentionally expose real users to dogfood bugs.

The upshot is that typically you will configure your Warden server with 14 endpoints for each
device.

As a final note, if your build includes `.apk` files signed with additional keys beyond the standard
4 (such as a key you use for a particular app you normally distribute via Play Store but are
preloading on a device) you can simply add an additional endpoint for it in the Warden config, place
its certificate in the directory with the others, and it will be transparently handled.

# Usage

Per [https://source.android.com/devices/tech/ota/sign_builds](the build signing instructions), there
are three scripts involved in generating an image:

1. `build/tools/releasetools/sign_target_files_apks` which takes as input the build artifacts
   generated by a `make dist` target, signs all the system image's APK files, and (despite its name)
   also signs boot images and `dm-verity` partitions. This script requires these Warden-alternate
   scripts for `boot_signer`, `verity_signer`, and `signapk.jar`.
2. `build/tools/releasetools/img_from_target_files` which generates a `fastboot update`-compatible
   package for direct flashing via unlocked booloader. This takes as input the processed/signed output
   of `sign_target_files_apks`. It is a fairly simple script that does not require any alternate
   sub-scripts.
3. `build/tools/releasetools/ota_from_target_files` which generates an OTA image for a device, using
   the signed output of `sign_target_files_apks`. It requires the alternate `payload_signer` and
   `signapk.jar` scripts.

These scripts require the _public keys_ -- specifically, the X.509 certificates -- that you use to
sign to be on the local disk. This is because these are typically embedded into the image files
being generated, prior to being signed. Since the scripts will error out without them, you need to
copy the certificate files (but, naturally, *not* the private keys) to the local machine.

The command line arguments to specify the alternate scripts are simply `--signapk_path`,
`--boot_signer_path`, `--verity_signer_path`, and `payload_signer_path`.

## Usage Example

Here is an example, assuming a build for the device `aosp_sailfish` (i.e. Google Pixel).

First, copy the certificate files to a directory:

    mkdir -p ~/signing-certs
    # copy certs via scp, unzip a .zip file, etc.
    # rename files to ".x509.pem" if they are not already so named

Note again that `.pk8` files as mentioned in the AOSP docs are *not required* when using the Warden
replacement scripts. It is entirely acceptable to store your signing certificates on the build
machine, store them in a zip file, etc.

Then, build the Android `dist` target to obtain signable build artifacts, and then build a few tools
required by modern A/B devices:

    make dist
    make otatools

Before using the Warden clients, load environment variables from a file (or set them via some other
means, e.g. in the execution context of your continuous build server.)

    . ~/warden-config.sh

Next, sign the system image APK files, rebuild the system image with the new versions, and resign
the boot image and `dm-verity` partitions:

    ./build/tools/releasetools/sign_target_files_apks.py \
      --signapk_path warden/signapk.jar \
      --boot_signer_path build/warden/boot_signer \
      --verity_signer_path build/warden/verity_signer \
      -d ~/signing-certs \
      -o \
      out/dist/aosp_sailfish-target_files-*.zip ~/signed.zip

If you need a `fastboot`-flashable image, build that:

    ./build/tools/releasetools/img_from_target_files ~/signed.zip ~/fastboot-ready.zip

Finally, build the OTA image:

    ./build/tools/releasetools/ota_from_target_files.py \
      --payload_signer /home/morrildl/warden-client/payload_signer \
      --signapk_path warden/signapk.jar \
      -k ~/signing-certs \
      ~/signed.zip ~/signed-ota.zip

# Useful Recipes

For full docs and more recipes, see the Warden server docs. However, here is a quick summary of how
to generate a TLS client certificate suitable for use to authenticate these scripts with your Warden
server.

    openssl genrsa -3 -out ~/tmp.key 2048
    openssl req -new -out ~/tmp.csr -days 10950 -key ~/tmp.key
    openssl x509 -in ~/tmp.csr -out /path/to/certs/this-machine.crt -req -signkey ~/tmp.key -days 10950
    openssl pkcs8 -topk8 -in ~/tmp.key -out /path/to/certs/this-machine.pk8 -outform PEM -nocrypt
    rm ~/tmp.csr ~/tmp.key

Note that releasekey in particular must be exactly a 2048-bit RSA key using exponent 3; this is a
current limitation of the Android device-side OTA infrastructure. The other keys (in particular, the
other APK keys) can use stronger keys. It is Playground's understanding that Google is working to
remove the key limitations in a future version.

Similarly, server keys for Warden must be generated with three Key Usages defined: KeyEncipherment,
DigitalSignature, and CertificateSigning. Omitting these will cause them to be rejected by Python
clients, such as those in this project.
