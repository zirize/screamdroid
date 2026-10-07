# R8 is on for the release build (app/build.gradle.kts): code shrinking, obfuscation and
# resource shrinking. This file is empty of keep rules on purpose - nothing here needed one, and
# that was measured rather than assumed (8.6 MB -> 1.5 MB, all 164 translated strings intact):
#
#   ℹ️ There is no reflection in this app - no Class.forName, no getIdentifier, no Parcelable,
#      no @Keep - and the activity and the two services are kept from the manifest by AGP.
#   ℹ️ DataStore and kotlinx-coroutines carry their own consumer rules, and zxing is called
#      through its encoder API directly (ui/QrCode.kt), so none of the three asks for a rule.
#
# 🔴 **The one thing to re-check whenever an enum moves.** Settings are written as `enum.name`
#    and read back by that name (settings/SettingsRepository.kt), so if R8 ever renames a
#    constant, a saved setting silently returns to its default the first time the app is
#    upgraded - and the unit tests stay green, because they never run through R8. The names
#    survive today; after a change, confirm it in the dex rather than trusting it:
#
#      unzip -p app/build/outputs/apk/release/app-release.apk classes.dex | strings | grep -c LOW_LATENCY
#
#    ⚠️ `grep -x` will not match - a dex string has its length byte in front of it.

# ℹ️ **Kept for a reason that did not hold up, and left in because it costs nothing.** Read
#    the three lines below in order - they are a measurement, an observation, and an inference,
#    and only the first two are ours to stand behind.
#
#    🔴 **Measured:** R8 inlines the enableEdgeToEdge() call at MainActivity.kt:66. The
#       behaviour stays, but every reference to androidx.activity.EdgeToEdge leaves the dex -
#       99 of them here, 5 in the sibling app, 0 in both afterwards. The rule brings them back.
#    ✅ **Observed** (console, 2026-09-19): no edge-to-edge item was ever raised for this app,
#       on any of the console screens, before or after the upload. So the rule was not needed to
#       make a warning go away - there was no warning. The fear it was written against did not
#       happen.
#    ❓ **Inferred, not stated by anyone:** targetSdk 36 means the system enforces edge-to-edge,
#       so there may be nothing left to recommend. Google has not said this. Do not rely on it.
#
#    🔑 So why keep it? Because the disappearance is real and free to undo, and the next
#    person to raise targetSdk or read the bytecode should not have to rediscover it. Delete it
#    if it ever gets in the way; nothing here depends on it.
#    ℹ️ The inlining was found by the droid_custom_vncviewer session and confirmed in both
#    repositories; the console screens were read by the play.google.com session.
-keep class androidx.activity.EdgeToEdge { *; }
