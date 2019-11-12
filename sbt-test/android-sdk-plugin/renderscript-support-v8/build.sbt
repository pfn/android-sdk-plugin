import android.Keys._

enablePlugins(AndroidApp)

name := "renderscript-support-v8"

platformTarget in Android := "android-21"

// need to target different versions on 23.+
buildToolsVersion in Android := Some("25.0.2")

minSdkVersion := "8"

showSdkProgress in Android := false

javacOptions ++= List("-source", "1.7", "-target", "1.7")
