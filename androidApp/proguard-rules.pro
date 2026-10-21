# Règles R8 de la version publiée.

# libsignal : la bibliothèque native appelle des classes et méthodes Java par
# leur nom (JNI). Renommées ou retirées, le chiffrement plante au premier appel.
-keep class org.signal.libsignal.** { *; }
-keepclassmembers class org.signal.libsignal.** { native <methods>; }
-dontwarn org.signal.libsignal.**

# Garder les noms de fichiers et numéros de ligne : les rapports de plantage
# (écran « Relay s'est arrêté ») restent lisibles.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
