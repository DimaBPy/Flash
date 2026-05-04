# Flash NFC App — Claude Development Guide

## Project Overview

**Flash** is a premium NFC file-sharing Android application with advanced physics-based animations and liquid glass rendering. The app leverages NFC for zero-click device pairing and Wi-Fi for fast local file transfers, with a focus on visual excellence through 120Hz-optimized animations and fluid morphing effects.

### Key Tech Stack
- **Language**: Kotlin 2.3.0
- **UI Framework**: Jetpack Compose (BOM 2024.12.01)
- **Material Design**: Material3 1.4.0-alpha11
- **Glass Effects**: Backdrop library 1.0.6 (SDF rendering)
- **Networking**: Ktor CIO (3.1.2)
- **Build System**: AGP 8.6.0, Gradle 8.14.4
- **Min SDK**: 26, Target SDK: 35

## Build Environment

### Android SDK Setup
The project requires Android SDK 36 and build-tools 34+. If you see "SDK location not found" errors:

1. Install Android Command Line Tools from android.com/studio
2. Create `local.properties` in project root:
   ```
   sdk.dir=/path/to/Android/SDK
   ```
3. Verify: `./gradlew assembleDebug` should complete in ~90 seconds

### AGP Compatibility
- Current: AGP 8.6.0 (pinned in `gradle/libs.versions.toml`)
- Do NOT downgrade without checking Material3 and Compose BOM compatibility
- Kotlin 2.3.0 requires AGP 8.6.0 minimum

### Gradle Properties
- `org.gradle.jvmargs=-Xmx4096m` — required for compilation
- `android.useAndroidX=true` — mandatory
- `kotlin.code.style=official` — follow official Kotlin style

Verify build succeeds: `./gradlew clean assembleDebug`

## Git Workflow & PR Management

### Working Branches (ONLY)
The **only** branches you should use are:
1. **`claude-code`** — Your feature/development branch (long-lived)
2. **`develop`** — Integration branch where features merge after testing
3. **`main`** — Production branch (protected; direct pushes prohibited)

**Important**: Do NOT create temporary feature branches like `claude/resume-after-network-issues`. All work goes on `claude-code`.

### Workflow
```
1. Work on claude-code branch locally
2. Push to origin/claude-code: git push -u origin claude-code
3. Create PR: claude-code → develop (automated merge after build passes)
4. Develop automatically merges to main when ready (CI gates this)
5. CodeRabbit reviews only happen on PRs targeting main
```

### Critical Rules
- **Only 4 branches exist** — anything else is abandoned/ignored
- **Never create draft PRs** — they bypass CodeRabbit entirely
- **Never target develop for CodeRabbit review** — CodeRabbit is configured for `main` only
- **All PR descriptions must be clean** — no auto-generated footers; they interfere with CodeRabbit command parsing
- **Commit messages should explain WHY**, not just WHAT
- **Version management** (CRITICAL — applies to EVERY app file change):
  - **Always check the current date** before updating versions (system context provides this)
  - **Increment `versionCode` by 1** for every file change that goes into the APK
  - **Update `versionName` format**: `"X.Y.Z, Month Day"` (e.g., "0.5.1, May 4")
  - **Date in versionName is REQUIRED** — it documents when the change was made
  - **Never commit version changes without the date** — discuss with user if uncertain about version number
  - Example: If you change `MotherCore.kt`, you MUST increment versionCode AND update versionName with today's date

### CodeRabbit Configuration
- Auto-reviews trigger only on PRs targeting `main`
- Rate limit: 1 review per hour per organization
- Review profile: CHILL (less strict, suitable for rapid iteration)

## Animation Architecture

### Core Concept: RepeatMode
Jetpack Compose has two repeat modes for infinite animations:

#### RepeatMode.Restart
```kotlin
infiniteRepeatable(
    animation = tween(duration, easing),
    repeatMode = RepeatMode.Restart  // Jump back to start, play forward again
)
```
- Animation goes: 0 → targetValue → 0 → targetValue → ...
- **Use for**: Cyclic patterns where start state = end state (Perlin noise, orbits, rotating elements)
- **Visual result**: Smooth, continuous looping with no artificial bounce

#### RepeatMode.Reverse
```kotlin
infiniteRepeatable(
    animation = tween(duration, easing),
    repeatMode = RepeatMode.Reverse  // Play forward, then backward
)
```
- Animation goes: 0 → targetValue → 0 → targetValue → ...
- **Use for**: Genuine oscillating/pulsing motion (breathing, gentle expansion/contraction)
- **Visual result**: Back-and-forth motion; visible slowdown/reversal at midpoint
- **Gotcha**: Looks bouncy if the motion is directional; causes artificial "breathing" artifacts

**Rule for Flash**: Use `Restart` for Perlin noise morphing (blob, photo edges) and `Reverse` only for intentional oscillations.

### Mother Core (Liquid Glass Blob)

**File**: `app/src/main/java/com/example/flash/ui/core/MotherCore.kt`

#### Constants
```kotlin
private const val CONTROL_POINTS  = 16      // Bézier control points around the blob perimeter
private const val BASE_RADIUS_DP  = 60f     // Base radius from center to edge
private const val NOISE_OFFSET_DP = 20f     // ±20dp Perlin noise variation (max bulge)
```

#### Sizing & Space
- Base blob diameter: `(60 * 2) + (20 * 2) + 32 = 172dp` (container size)
- Max reach from center: 60 + 20 = 80dp
- Available space before hitting boundary: ~90 - 80 = 10dp
- Current usage: ~80% of available space (conservative for safety)

#### Animation Loop
```kotlin
val time by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1000f,
    animationSpec = infiniteRepeatable(
        animation = tween(2_700, easing = LinearEasing),
        repeatMode = RepeatMode.Restart  // Smooth loop; 2.7s per cycle
    )
)
```
- `time` drives Perlin noise via `updateBlobPath()`
- Perlin noise is deterministic: `noise(cos(angle)*1.5 + time*0.0004, sin(angle)*1.5 + time*0.0004)` produces smooth morphing
- At `time=1000`, the noise state returns to `time=0` state, so `Restart` loops seamlessly

#### Specular Highlights
```kotlin
val specularShiftX = (sin(time * 0.00063 * PI) * baseRPx * 0.12f).toFloat()
val specularShiftY = (cos(time * 0.00047 * PI) * baseRPx * 0.09f).toFloat()
```
- Two highlights (large soft + small pinpoint) drift slowly across the blob surface
- Creates illusion of liquid depth and movement
- Frequencies slightly different to avoid static patterns

### Photo Orbiting

**File**: `app/src/main/java/com/example/flash/ui/workbench/PhotoOrbit.kt`

#### Golden Angle Spacing
```kotlin
private val GOLDEN_ANGLE = (PI * (3.0 - sqrt(5.0))).toFloat()  // ≈ 137.5°
```
- Each new photo lands at `nextPhaseIndex * GOLDEN_ANGLE` radians
- Guarantees photos never bunch together regardless of count
- Phase is assigned once on entry; adding photos doesn't shift existing ones

#### Orbit Animations

**1. Orbit Ring** (photos circling the core)
```kotlin
val time by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = (2 * PI).toFloat(),
    animationSpec = infiniteRepeatable(
        animation = tween(8000, easing = LinearEasing),
        repeatMode = RepeatMode.Restart
    )
)
```
- 8-second full rotation, then jump back and rotate again
- Photos position: `x = coreCenter.x + orbitR * cos(time + phaseOffset)`

**2. Photo Blob Morphing** (edge distortion on each photo)
```kotlin
val blobTime by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1000f,
    animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing))
)
```
- 12-second cycle for visible edge morphing on each thumbnail
- Uses same Perlin noise logic as Mother Core
- Each photo gets unique morphing based on: `photoBlobTime = (blobTime + phaseOffset * 137f)`

**3. Radial Drift** (photos drift slightly in/out)
```kotlin
val radialDrift by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = with(density) { 8.dp.toPx() },
    animationSpec = infiniteRepeatable(
        animation = tween(4000, easing = LinearEasing),
        repeatMode = RepeatMode.Restart
    )
)
```
- 4-second cycle, drifts ±8dp from base orbit radius
- Changed to `Restart` to avoid bounce at reversal

#### Photo Lifecycle
- **Entry**: Spring animation (600ms, dampingRatio 0.6, stiffness 150)
- **Orbit**: Continuous, driven by `time`
- **Success Pulse**: 400ms bloom + collapse to center when transfer completes
- **Exit**: 400ms fade to center with FastOutSlowInEasing

### Onboarding Animation

**File**: `app/src/main/java/com/example/flash/ui/onboarding/OnboardingScreen.kt`

Two phones animating in TwoPhonesAnimation:
- Phone offset: 1200ms `Restart` cycle (60dp → 8dp → 60dp)
- Tap pulse: 2400ms `Restart` cycle (full circle wave)

## Common Mistakes & How to Avoid Them

### Mistake 1: Creating Draft PRs
**Problem**: CodeRabbit skips reviewing draft PRs.
**Solution**: Always mark `draft = false` when creating PRs. Use `update_pull_request` if you created one as draft.

### Mistake 2: Forgetting the Develop→Main Step
**Problem**: You finish work, create a PR to develop, and wait for CodeRabbit review. CodeRabbit never reviews because it's not targeting `main`.
**Solution**: 
1. Create feature→develop PR, merge immediately
2. Then create develop→main PR for CodeRabbit review

### Mistake 3: Adding "Generated by Claude Code" Footers
**Problem**: CodeRabbit treats footers as conversation text, not command markers. `@coderabbitai review` with a footer becomes a chat message instead of a trigger.
**Solution**: Never include auto-generated footers in PR descriptions or bot command comments.

### Mistake 4: RepeatMode.Reverse for Perlin Morphing
**Problem**: Animation bounces at the midpoint, looks unnatural.
**Solution**: Use `Restart` for any animation where start state = end state.

## Key Files & Their Purposes

- `MotherCore.kt` — Liquid glass blob rendering and morphing
- `PhotoOrbit.kt` — Photo orbiting, Perlin noise edge distortion, golden angle spacing
- `LiquidButton.kt` — Interactive button with backdrop effects
- `WorkbenchScreen.kt` — Main UI layout, orchestrates core, photos, and buttons
- `RippleShader.kt` — AGSL shader for ripple effect on file transfer
- `gradle/libs.versions.toml` — Dependency versions (AGP, Kotlin, Compose, Ktor, etc.)

## Versioning

### Version Code
- Located in `app/build.gradle.kts` (line 16)
- **Increment by 1** whenever ANY file changes that will be included in the APK (essentially any code or resource change)
- This applies to all branches—increment before committing

### Version Name
- Located in `app/build.gradle.kts` (line 17)
- Format: `"x.x, Month Date"` (e.g., `"0.5, May 3"`)
- **Always discuss version name changes with the user first**—ask what the current version is, then they'll tell you the next one
- Version code increments automatically; version name is a deliberate decision

## Performance Notes

- **120Hz target**: Animations use `LinearEasing` for consistent frame rates
- **Perlin noise**: 3 octaves with 0.55 persistence for smooth variation
- **Canvas operations**: All morphing done in `Canvas` with cached paths where possible
- **Memory**: Base APK ~24MB (debug); optimize with proguard/R8 for release

## Future Enhancements (from README)

- [ ] Simultaneous receiving and sending (currently sender rejects incoming handshakes)
- [ ] Gallery flight destination from real `LazyVerticalGrid` positions (currently uses fixed dp constants)

## Questions for Future Me

**Q: Why RepeatMode.Restart for blob morphing?**
A: Perlin noise is cyclic. At time=0 and time=1000, the noise state is identical. Restart loops cleanly; Reverse creates a bounce at the turnaround that looks unnatural.

**Q: Why NOISE_OFFSET_DP = 20f?**
A: Gives ~16dp unused space before hitting container boundary. Conservative for safety while providing visible morphing variety.

**Q: Why separate develop→main PR step?**
A: CodeRabbit is configured to only auto-review `main` branch PRs. Develop PRs skip review entirely unless manually triggered.

**Q: How do photos not bunch together?**
A: Golden angle (137.5°) ensures each new photo lands in the largest empty gap, maintaining even spacing regardless of count.
