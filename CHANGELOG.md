## 0.1.0

* Updated the bridge to Nosmai Effects Flutter `2.0.2` and native SDK `3.0.7`.
* Added the Android shared-EGL GPU texture path with a bounded I420 fallback.
* Improved processed-frame delivery, ownership, and cleanup during streaming.
* Improved start, stop, reconnect, camera switching, and shutdown reliability.
* Updated the example and production integration guidance.

## 0.1.0-beta.2

* Fixed Android video-frame delivery stability when forwarding processed frames to Agora.
* Improved frame ownership and cleanup during streaming, reconnect, and shutdown.
* Continued using the same Nosmai Effects Flutter beta and Android native SDK artifacts.

## 0.1.0-beta.1

* Added a synchronized Android GPU texture path for Nosmai preview output.
* Added a bounded I420 fallback for devices where EGL context sharing is unavailable.
* Improved stream start, stop, reconnect, and native-resource lifecycle handling.
* Switched Android native SDK resolution to the official versioned GitHub release.
* Updated the example for the Nosmai Effects beta integration flow.

## 0.0.1

* TODO: Describe initial release.
