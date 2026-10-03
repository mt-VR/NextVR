PhoneXR SDK
===========

A kit for porting and developing OpenXR games for PhoneXR — VR on an ordinary phone
(Cardboard + the Monado runtime + camera hand tracking + a Joy-Con instead of controllers).

Contents:

  tools/phonexr_port.py   Prepares someone else's APK to run on PhoneXR: fixes the manifest,
                          swaps the OpenXR loader, aligns and signs it.
  src/                    The PhoneXRInput library (Kotlin/JVM): raw hand and Joy-Con data.
  runtime/phonexr_input.h The same thing for C/C++ games, a single header.
  docs/porting.txt        How to port a game and what cannot be ported.
  docs/protocol.txt       The format of the PhoneXR data stream.
  docs/manifest.txt       What your own game needs in its manifest to avoid a black screen.
  docs/engines.txt        Hooking up Unity, Godot 4 and Lua engines.
  docs/api.txt            The PhoneXR Developer API: OpenXR, PH5, lifecycle and publishing.
  docs/ai-prompts.txt     Full prompts for generating PhoneXR projects.

Building the library and running the tests:

  ./gradlew :sdk:build

What may be ported
------------------
Only builds you are allowed to run: your own, open projects, games
distributed outside the Meta store, and purchased copies obtained lawfully.

Games from the Quest store (Beat Saber, Job Simulator and the like) cannot be ported:
they verify the purchase through Meta services and run on Meta's closed runtime.
The tool recognizes such builds and refuses to prepare them.
