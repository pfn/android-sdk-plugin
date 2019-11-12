import android.Keys._

enablePlugins(AndroidApp)

platformTarget in Android := "android-17"

libraryDependencies += "com.google.android.gms" % "play-services" % "4.3.23"

resValues in Android += ("string", "test_resource", "test value")

showSdkProgress in Android := false
