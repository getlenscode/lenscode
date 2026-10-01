# ProGuard/R8 rules for release builds.
#
# Before enabling isMinifyEnabled in build.gradle.kts, add and test keep rules
# for:
#   - kotlinx.serialization: @Serializable classes and their serializers
#     (see https://github.com/Kotlin/kotlinx.serialization#android)
#   - Koog / Ktor client internals used reflectively by ai.koog:koog-agents
#   - org.json usage is reflection-free and needs no rules
#
# Strip debug logging from release builds:
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
