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

# 🔴 **Two store recommendations, and one of them deletes the evidence for the other.** R8
#    inlines the enableEdgeToEdge() call at MainActivity.kt:66 - the behaviour stays, but every
#    reference to androidx.activity.EdgeToEdge disappears from the dex (measured here: 99 -> 0).
#    Play looks for that call in the uploaded bytecode, so an app that does edge-to-edge properly
#    can still be told to do it. Keeping the class costs nothing measurable and keeps the two
#    recommendations from cancelling each other out.
#    ℹ️ Reported by the droid_custom_vncviewer session, which hit it first.
#    ❓ Still to confirm from the console after the next upload: that the panel does go away.
-keep class androidx.activity.EdgeToEdge { *; }
