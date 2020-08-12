let
  pkgs = import <nixpkgs> {
    overlays = [(import ./overlays.nix)];
  };
in
  with pkgs;
  mkShell {
    name = "earShell";
    buildInputs = [
      clojure
      clj-kondo
      openjdk
    ];
  }
