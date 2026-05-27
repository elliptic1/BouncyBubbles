# BouncyBubbles

Glossy gradient bubbles that float on top of every app on your phone — like Facebook Messenger chat heads, but as a fidget toy. Tilt the phone, the bubbles roll. Flick them, they ricochet. Drag one down to the dismiss zone, it pops.

## What it is

BouncyBubbles is a tiny native Android app that draws a system-wide overlay full of bouncy marble-like bubbles. Everything you tap on outside a bubble still goes to the app underneath — the bubbles are a playful layer on top. It's a toy, an experiment, a procrastination device.

## What it needs from you

- **Draw over other apps** — required, because the whole point is that the bubbles sit on top of every other app on your phone. The app will prompt you for this on first launch.
- **Notifications** — when the bubbles are running you get a small "Bouncy Bubbles is running" notification with a one-tap stop. Required on Android 13 and later.
- **Background service** — the bubbles keep running while you use other apps, so the app runs a foreground service while they're active.

No internet, no analytics, no accounts, no data leaves the phone.

## How to use it

1. Open BouncyBubbles.
2. Tap **Grant overlay permission** and turn it on for BouncyBubbles in system settings.
3. Pick how many bubbles, how big, how heavy, how bouncy.
4. Tap **Launch Bubbles**.
5. Switch to any other app — the bubbles are floating on top.

### Controls

- **Tilt your phone** — bubbles roll downhill.
- **Tap empty space** — spawns a new bubble (up to 25).
- **Drag a bubble** — moves it; let go to fling.
- **Drag a bubble to the X at the bottom** — pops it.
- **Notification tap or "Stop" action** — stops the overlay.

## How to build

Open the project in Android Studio (Hedgehog or later). Let Gradle sync, then run **app** on a device or emulator running Android 8.0 (API 26) or higher. The accelerometer-driven gravity only works on a real device — emulators usually report a static gravity vector.

Or from the command line:

```
./gradlew :app:assembleDebug
```

The Gradle wrapper jar is created by Android Studio on first sync — if `gradlew` fails because the jar is missing, open the project in Android Studio once and it will fill it in.

## Project layout

```
app/
  src/main/java/app/toddbsmith/bouncybubbles/
    MainActivity.kt
    ui/LauncherScreen.kt
    overlay/
      BubbleOverlayService.kt    # foreground service + WindowManager glue
      BubbleCanvasView.kt        # the actual overlay surface — touch, sensor, draw
    physics/
      PhysicsEngine.kt           # hand-rolled 2D physics
      Bubble.kt
      BubblePalette.kt           # 12 curated gradient pairs
  src/test/java/.../physics/
    PhysicsEngineTest.kt         # unit test for the elastic-collision math
```

## License

MIT, do whatever you want.
