{
  description = "Karoo Saftladen - reports sensor battery status at the end of a ride";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      systems = [ "aarch64-darwin" "x86_64-darwin" "aarch64-linux" "x86_64-linux" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f (import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      }));

      # Keep in sync with app/build.gradle.kts (compileSdk / buildToolsVersion).
      buildToolsVersion = "35.0.1";

      # Platform 34 and build-tools 34.0.0 are what karoo-ext itself builds against, so
      # that `publishToMavenLocal` works in this shell as well, see README.
      buildToolsVersions = [ "34.0.0" buildToolsVersion ];
      platformVersions = [ "34" "35" ];
    in
    {
      devShells = forAllSystems (pkgs:
        let
          jdk = pkgs.jdk17;
          gradle = pkgs.gradle.override { java = jdk; };

          androidSdk = (pkgs.androidenv.composeAndroidPackages {
            cmdLineToolsVersion = "22.0";
            platformToolsVersion = "37.0.1";
            inherit buildToolsVersions platformVersions;
            includeEmulator = false;
            includeSystemImages = false;
            includeSources = false;
            includeNDK = false;
          }).androidsdk;

          sdkRoot = "${androidSdk}/libexec/android-sdk";
        in
        {
          default = pkgs.mkShell {
            packages = [
              jdk
              gradle
              androidSdk
              pkgs.android-tools # adb, for `adb install` onto the Karoo
              # Release signing material lives encrypted in secrets.yaml; CI
              # decrypts it with a dedicated age key.
              pkgs.sops
              pkgs.age
            ];

            JAVA_HOME = jdk.home;
            ANDROID_HOME = sdkRoot;
            ANDROID_SDK_ROOT = sdkRoot;

            # The Nix-provided SDK is read-only, so AGP cannot download its own aapt2.
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/${buildToolsVersion}/aapt2";

            shellHook = ''
              echo "karoo-saftladen dev shell"
              echo "  jdk      : $(java -version 2>&1 | head -1)"
              echo "  gradle   : $(gradle --version 2>/dev/null | grep -i '^Gradle' || echo unknown)"
              echo "  sdk      : $ANDROID_HOME"
              echo
              echo "  gradle assembleDebug    build the extension APK"
              echo "  gradle test             run unit tests"
              echo "  adb install -r app/build/outputs/apk/debug/app-debug.apk"
            '';
          };
        });

      formatter = forAllSystems (pkgs: pkgs.nixpkgs-fmt);
    };
}
