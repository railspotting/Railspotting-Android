# The Rust core calls no Java, but the JVM looks Native's methods up by name.
-keep class com.railspotting.android.core.Native { *; }

# Credential Manager and Google ID tokens (their own consumer rules cover the rest).
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
