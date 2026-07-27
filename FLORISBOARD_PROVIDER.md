# FlorisBoard predictive-text provider

This fork preserves the full FUTO Keyboard application and its payment functionality, and adds a
bound service which exposes FUTO's on-device predictive-text stack to compatible FlorisBoard
builds. It is a modified build of [FUTO Keyboard](https://github.com/futo-org/android-keyboard) and
remains subject to the repository's [FUTO Source First License](LICENSE.md).

The integration uses the independently Apache-2.0-licensed
[FlorisBoard autocorrect provider API](https://github.com/aytekaksu/florisboard/tree/main/lib/autocorrect-api).
No FUTO source code is copied into FlorisBoard.

## Coverage

- binary dictionaries, transformer candidates, FUTO ranking, emoji candidates, and safe
  autocorrection metadata;
- FlorisBoard-provided key geometry for proximity-aware correction;
- dictionary-aware next-key hit testing without an extra IPC request;
- FUTO swipe decoding from bounded, normalized gesture paths;
- primary and secondary language selection;
- personal dictionary, user-history learning and unlearning, and suggestion blacklisting;
- model selection, validation, import, export, and deletion through FUTO-owned settings pages;
- common prediction controls in both FlorisBoard's app and keyboard surfaces;
- explicit provider lifetime: no started service, polling loop, wake lock, or recurring provider job.

Upstream's on-device model training worker is currently commented out and its public scheduling
functions are no-ops. The provider reports fine-tuning as unavailable instead of presenting a
nonfunctional control.

The swipe models are Git LFS files in the `java/assets/futo-swipe` submodule. Run `git lfs pull`
inside that submodule before building; an unexpanded LFS pointer is not a usable model.

## Privacy and lifecycle

FlorisBoard does not bind the provider for password, raw, no-suggestion, incognito, or otherwise
ineligible fields. Text context and keyboard geometry are bounded by the protocol. The FUTO process
receives no target application package name. Persistent history is disabled when Android requests
no personalized learning. The provider also rejects protocol messages from packages outside the
four FlorisBoard release, beta, debug, and benchmark application IDs.

The service exists only while FlorisBoard has an eligible typing session or provider settings page
open. Model preparation is lazy and scoped to that bound lifecycle; model imports and selection
changes invalidate the loaded model through an event flow rather than polling. Learned dictionary
changes are flushed when a session finishes; native models and dictionaries are released when
Android destroys the service.
