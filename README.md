# Little Bird Tank Interpolation

Android tank-volume calculator for sounding or ullage measurements, trim interpolation, and heel correction.

## Stage 1
- Application ID: `com.littlebird.interpolation`
- Kotlin, Jetpack Compose and Material 3
- Interpolation across two measurement references and two trim references
- Linear heel-correction interpolation
- Calculation breakdown and corrected volume in m³
- Minimum Android 8.0 (API 26), compile SDK 36, JDK 17
- Target ABI: arm64-v8a only

## Calculation
1. Interpolate tank volume between the lower and upper sounding/ullage references at each trim reference.
2. Interpolate those two volumes at the actual trim.
3. Interpolate heel correction between the two heel references.
4. Add heel correction to the trim-interpolated volume.

Four corner volumes are required: two measurement references at each of two trim references. Enter values from the vessel's approved tank calibration table and verify results against the approved tables before operational use.

Open in Android Studio, sync Gradle, then build and run on an arm64-v8a device.
