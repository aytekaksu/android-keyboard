# FUTO Predictive Text Provider for Better FlorisBoard

This is an unofficial, modified fork of
[FUTO Keyboard](https://github.com/futo-org/android-keyboard). Its Git history and GitHub fork
relationship are intentionally retained for attribution.

The repository now builds a UI-less predictive-text plugin for
[Better FlorisBoard](https://github.com/aytekaksu/better-florisboard). Better FlorisBoard remains
the active keyboard and renders every suggestion, control, settings page, file picker, and
confirmation. This package supplies the private, on-device FUTO prediction engine and its
declarative settings content.

> [!NOTE]
> The plugin has no launcher activity and cannot be opened or selected as a keyboard. Android will
> still show any separately installed APK under system Settings → Apps; ordinary apps cannot opt
> out of that system inventory.

## Included capabilities

- dictionary correction, completion, next-word prediction, and ranking;
- local transformer inference and model management;
- neural and legacy glide decoding;
- emoji suggestions;
- personal dictionary, learned history, and suggestion filtering;
- app and keyboard settings rendered entirely by Better FlorisBoard.

The release APK is ABI-specific and contains no FUTO keyboard activity, IME, setup flow, updater,
voice input, Mozc, Rime, training worker, theme editor, or other standalone-app surface. The
upstream source remains in this fork for provenance; R8, resource shrinking, curated assets, and a
provider-only native target keep unreachable upstream code and data out of the plugin artifact.
Release builds are intentionally unsigned so the public repository never contains a distributable
private key. Distributors must sign the generated APK with their own private release key.

## Privacy and lifecycle

The provider has no network permission. It is a bound service which normally runs only while a
compatible host is using prediction or provider settings, and has no polling loop, scheduled job,
alarm, foreground service, or wake lock. Better FlorisBoard does not bind it for passwords, PINs,
incognito fields, raw editors, or fields which prohibit suggestions. Learning is disabled when
Android requests no personalized learning.

Protocol calls are accepted only from the selected Better FlorisBoard signing identity; the
verified identity is cached for the lifetime of that service binding to avoid work on every
keystroke. Provider settings and files stay in the provider's private storage; all visible UI and
browser/file launches are owned and validated by Better FlorisBoard.

## Contributing

Focused fixes and engine improvements are welcome. Pull requests must remain compatible with the
UI-less provider boundary and preserve upstream attribution. Only
[@aytekaksu](https://github.com/aytekaksu) may approve and merge repository changes.

## Building the minimal provider

Clone recursively, initialize Git LFS in the swipe-model submodule, and build the ABI you need:

```sh
git clone --recursive https://github.com/aytekaksu/android-keyboard.git
cd android-keyboard
git -C java/assets/futo-swipe lfs install --local
git -C java/assets/futo-swipe lfs pull
./gradlew :floris-autocorrect-provider:assembleRelease -PproviderAbis=arm64-v8a
```

Omit `-PproviderAbis` to produce separate APKs for ARMv7, ARM64, x86, and x86-64. The build does not
produce a wasteful universal APK.

## Upstream, modifications, and licenses

This fork is not affiliated with or endorsed by FUTO or the upstream FlorisBoard maintainers. It
is distributed free of charge for non-commercial purposes.

FUTO-derived code remains under the
[FUTO Source First License 1.1-kb](LICENSE.md). The independent provider protocol module is under
Apache License 2.0; see [autocorrect-api/NOTICE](autocorrect-api/NOTICE). FUTO Swipe model weights
remain under their [model weights license](java/assets/futo-swipe/LICENSE.md).

This is a prominently modified distribution: the original keyboard application has been replaced
in the provider artifact by a service-only integration for Better FlorisBoard. FUTO payment and
support options remain visible in the provider settings rendered by Better FlorisBoard.

**Powered by FUTO Swipe technology.**

See [NOTICE](NOTICE) and [FLORISBOARD_PROVIDER.md](FLORISBOARD_PROVIDER.md) for attribution and
technical boundaries.

## Original FUTO Keyboard README

<details>
<summary>Show the original upstream README</summary>

# FUTO Keyboard

The goal is to make a good modern keyboard that stays offline and doesn't spy on you. This keyboard is a fork of [LatinIME, The Android Open-Source Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME), with significant changes made to it.

Check out the [FUTO Keyboard website](https://keyboard.futo.tech/) for downloads and more information.

The code is licensed under the [FUTO Source First License 1.1](LICENSE.md).

## Issue tracking and contributing

Please check the GitHub repository to report issues: [https://github.com/futo-org/android-keyboard/](https://github.com/futo-org/android-keyboard/)

The source code is hosted on our [internal GitLab](https://gitlab.futo.org/keyboard/latinime) and mirrored to [GitHub](https://github.com/futo-org/android-keyboard/). As registration is closed on our internal GitLab, we use GitHub instead for issues and pull requests.

Due to custom license, pull requests to this repository require signing a [CLA](https://cla.futo.org/) which you can do after opening a PR. Contributions to the [layouts repo](https://github.com/futo-org/futo-keyboard-layouts) don't require CLA as they're Apache-2.0

If you want to help translate the app, please do so via our Pontoon instance: https://i18n-keyboard.futo.org/

## Layouts

If you want to contribute layouts, check out the [layouts repo](https://github.com/futo-org/futo-keyboard-layouts).

## Building

When cloning the repository, you must perform a recursive clone to fetch all dependencies:
```
git clone --recursive https://gitlab.futo.org/keyboard/latinime.git
```

If you forgot to specify recursive clone, use this to fetch submodules:
```
git submodule update --init --recursive
```

You can then open the project in Android Studio and build it that way, or use gradle commands:
```
./gradlew assembleUnstableDebug
./gradlew assembleStableRelease
```

</details>
