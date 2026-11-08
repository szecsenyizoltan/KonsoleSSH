# JSch — reflection-driven SSH library
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Bouncy Castle — JSch's bc.* classes call it directly, so R8 traces what is
# used; some optional JDK-only references (LDAP, javax.naming) are absent here.
-dontwarn org.bouncycastle.**

# Gson — reflection-based (de)serialisation
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Project models serialised with Gson — keep field names so the JSON stays stable
-keep class hu.billman.konsolessh.model.** { *; }

# Preserve enum valueOf()/values() used via reflection
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Google Tink (pulled in by androidx.security.crypto) references these
# compile-time-only javax annotations which are not on the Android classpath.
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
