# Metronome

A metronome and a chromatic tuner for Android. Free, open source (MIT), with **no ads,
no tracking, no analytics and no account**. The app does not declare the `INTERNET`
permission, so it has no way to send anything off the device.

It exists because the metronome and tuner apps on the Play Store are mostly ad-supported,
and a full-screen ad between songs is no use to someone practising.

## Features

**Metronome**

- 20–300 BPM: drag around the dial (it works like a jog wheel, 3° per BPM), ±1 buttons,
  tap the number to type a tempo, or tap tempo (averages the last 6 taps; a pause of 2 s
  starts a new measurement).
- Time signatures 2/4, 3/4, 4/4, 5/4, 6/8, 7/8, 9/8, 12/8, or custom (1–16 beats over a
  2, 4, 8 or 16 note). The BPM always counts the bottom note, so 6/8 at 120 plays six
  eighth-note clicks at 120 per minute.
- Per-beat accents: tap a beat dot to cycle accent → normal → mute. Changing the metre
  keeps the accents you already set on the beats that remain.
- Subdivisions: eighths, triplets, sixteenths (softer click). Muting a beat silences its
  main click only; its subdivisions still play.
- Four synthesised sounds: click, woodblock, beep, cowbell.
- Visual beat indicator: the current beat dot and the dial centre flash in time with the audio.
- Tempo trainer: raise the tempo by N BPM every M bars, up to a target.
- Presets: save the current set-up under a name. The list keeps your order (reorder with
  the arrows), so it doubles as a setlist.
- Keeps playing with the screen off. A media-style notification (and the media controls /
  a headset button) stops it.

**Tuner**

- Note name, octave, frequency and a ±50 cent needle with smoothing. Within ±5 cents
  counts as in tune.
- A4 calibration from 430 to 450 Hz.
- Instrument modes: guitar (EADGBE), bass (EADG), ukulele (GCEA, re-entrant) and violin
  (GDAE). The nearest string is highlighted and the needle shows how far you are from
  _that string_. A string tuned a semitone flat still points at the string it belongs to,
  not at the wrong chromatic note.
- Range roughly 38 Hz – 2.1 kHz (bass low E up to the top of a violin's E string).
- Keeps the screen on while the tuner is open.

## Permissions

| Permission                                                | Why                                                                                                                                                                       |
| --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `RECORD_AUDIO`                                            | Tuner only. Asked the first time you open the tuner, with an explanation. Audio is analysed in memory and never recorded, stored or sent. The metronome works without it. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keep the metronome playing while the screen is off or another app is in front.                                                                                            |
| `POST_NOTIFICATIONS`                                      | Show the playback notification with its stop control. Asked once, the first time you press Start. If you refuse, the metronome still plays.                               |

No `INTERNET`, no storage, no location.

## Building

Requires JDK 17+. This machine has no system JDK, but Android Studio's bundled JBR works:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug testDebugUnitTest
./gradlew assembleRelease   # unsigned unless keystore.properties exists
```

To sign a release, create `keystore.properties` in the project root (it is git-ignored):

```properties
storeFile=/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Toolchain: Gradle 9.9, AGP 9.5 (built-in Kotlin), Kotlin 2.4, Compose BOM 2026.09,
compileSdk/targetSdk 37, minSdk 31 (Android 12: dynamic colour is always available).

## How it is built, and why

- **Timing comes from counting audio frames, not timers.** `ClickRenderer` (plain Kotlin)
  writes each click at its exact frame offset inside the buffer being rendered. A dedicated
  `THREAD_PRIORITY_URGENT_AUDIO` thread feeds those buffers to a streaming, low-latency
  float `AudioTrack` and blocks on `write`, so the beat is as steady as the audio
  hardware's clock. `Handler`/`delay` scheduling was ruled out because its wake-up jitter
  (several ms, worse under load) is audible in a metronome.
- **No drift.** Tick _n_ after a tempo change is computed as `anchor + n × period` rather
  than by adding the period over and over. Periods are rarely whole frames (113 BPM at
  44.1 kHz is 23 415.9… frames), and repeated addition would let rounding pile up. A unit
  test checks that tick 100 000 is still within half a frame of its exact time.
- **The visual beat follows the audio actually playing.** Each rendered tick is queued with
  its frame number. On every display frame the UI asks the engine which ticks the hardware
  has reached, using `AudioTrack.getTimestamp`. The flash therefore lines up with what you
  hear, output latency included, and is not driven by the time the click was written.
- **Click sounds are synthesised** at the device's native output rate when playback
  starts. That means no sample files, no resampling, no decoding on the audio thread, and
  nothing to license.
- **No audio focus request.** People play along with recordings, so the metronome mixes
  with other audio instead of pausing it.
- **Playback lives in an app-level controller**, not the Activity. A `mediaPlayback`
  foreground service keeps the process alive with the screen off and carries the
  notification. A platform `MediaSession` (no Media3 dependency needed) puts the
  notification in the media controls and lets a headset button stop it.
- **Pitch detection is YIN** (de Cheveigné & Kawahara 2002) in pure Kotlin.
  Its cumulative-mean-normalised difference with a "first dip under the threshold" rule
  resists octave errors on harmonic-rich sounds such as plucked strings. The period is
  refined by parabolic interpolation on the _raw_ difference function, because the
  normalised one skews the minimum. Unit tests check sines, sawtooth, square and
  weak-fundamental waves from 41 Hz to 1.3 kHz, which all come out within ±2 cents.
  The microphone is read at 44.1 kHz (the one rate every device must support) using the
  `UNPROCESSED` source where available. That source skips noise suppression and AGC.
  Elsewhere the app uses `VOICE_RECOGNITION`.
- **Steady display.** A median of the last three estimates removes single-frame glitches,
  such as an octave jump on a pluck's attack. Exponential smoothing then calms the needle
  and restarts whenever the target note changes, so it never glides across notes. The last
  reading is held for 1.2 s so a decaying note can still be read.
- **Settings and presets** are stored in a private `SharedPreferences` file. They use a tiny
  line-based format (`PresetCodec`) that is unit-tested on the JVM. A malformed record is
  skipped rather than wiping the list.

Code layout: `core/` holds the plain-Kotlin logic with JVM tests (renderer, tick clock,
tap tempo, trainer, YIN, note maths, tuner smoothing, preset codec). `audio/` holds the
Android audio, service and controller code. `ui/` holds the Compose screens.

## Not in v1

- No count-in, no per-preset "next song" button on the main screen (open Presets to step
  through the setlist), no polyrhythms.
- The tempo trainer only speeds up; it does not slow down or loop.

## Privacy

See [PRIVACY.md](PRIVACY.md). Short version: nothing leaves your device.

## License

MIT. See [LICENSE](LICENSE).
