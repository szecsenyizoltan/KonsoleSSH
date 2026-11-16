# JSch — reflection-driven SSH library
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Bouncy Castle — JSch's bc.* classes call it directly, so R8 traces what is
# used; some optional JDK-only references (LDAP, javax.naming) are absent here.
-dontwarn org.bouncycastle.**

# Preserve enum valueOf()/values() used via reflection
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Error Prone's compile-time annotations are referenced by Google libraries
# but not on the Android classpath.
-dontwarn com.google.errorprone.annotations.**
