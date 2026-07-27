# Better FlorisBoard provider architecture

The release artifact is built by `:floris-autocorrect-provider`. Its Android manifest declares one
exported bound service and no activity, launcher entry, input method, receiver, content provider,
scheduled worker, or foreground service.

Better FlorisBoard owns:

- editor eligibility and privacy gating;
- suggestion, settings, confirmation, document-picker, and external-link UI;
- provider discovery, binding, timeouts, cancellation, and process-loss recovery;
- host-side glide enablement and activation sensitivity.

The provider owns:

- FUTO dictionary, transformer, emoji, and glide inference;
- model, dictionary, personal-history, and blacklist storage;
- bounded candidate ranking and autocorrection metadata;
- declarative settings content.

The provider process has no network permission. External HTTPS links are validated and launched by
Better FlorisBoard, never by the provider. The service is bound only for eligible typing sessions
or while a provider settings surface/document operation is active.

The APK uses per-ABI delivery, resource shrinking, a curated asset set, and a native target without
voice recognition, fine-tuning, or training code. Full upstream sources remain in Git solely for
fork provenance and future auditing; they are not equivalent to packaged APK contents.

For data compatibility, the provider retains the application ID
`org.futo.inputmethod.latin.unstable`. It therefore cannot coexist with the full FUTO Keyboard
unstable variant, and APKs signed by different keys cannot update each other in place.

See [README.md](README.md), [NOTICE](NOTICE), and [LICENSE.md](LICENSE.md).
