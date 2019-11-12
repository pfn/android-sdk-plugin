lazy val root = project.in(file(".")).enablePlugins(AndroidApp)

lazy val flavor1 = android.flavorOf(root, "flavor1",
  debugIncludesTests in Android := true,
  libraryDependencies ++=
    "com.android.support.test" % "runner" % "0.2" % "androidTest" ::
      "com.android.support.test.espresso" % "espresso-core" % "2.1" % "androidTest" ::
      Nil,
  instrumentTestRunner in Android :=
    "android.support.test.runner.AndroidJUnitRunner",
  packagingOptions in Android := PackagingOptions(excludes = Seq("LICENSE.txt"))
)

debugIncludesTests in Android := false

autoScalaLibrary := false

showSdkProgress in Android := false

javacOptions in Compile ++= List("-source", "1.7", "-target", "1.7")
javacOptions in Compile in flavor1 ++= List("-source", "1.7", "-target", "1.7")
