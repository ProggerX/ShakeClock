{
  description = "Shake Clock - Always-On Glyph Matrix toy for the Nothing Phone (4a) Pro";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];

      mkPkgs = system: import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f (mkPkgs system));
    in
    {
      devShells = forAllSystems (pkgs:
        let
          androidSdk = (pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "35" ];
            buildToolsVersions = [ "35.0.0" ];
            includeEmulator = false;
            includeNDK = false;
            includeCmake = false;
            includeSystemImages = false;
            includeSources = false;
            useGoogleAPIs = false;
            useGoogleTVAddOns = false;
          }).androidsdk;
        in
        {
          default = pkgs.mkShell {
            packages = [
              pkgs.jdk17
              pkgs.gradle_8
              androidSdk
            ];

            ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
            ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
            JAVA_HOME = pkgs.jdk17.home;
          };
        });

      formatter = forAllSystems (pkgs: pkgs.nixfmt);
    };
}
