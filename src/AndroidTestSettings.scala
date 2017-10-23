package android

import Keys._
import Keys.Internal._
import Tasks._
import sbt.{Def, _}
import sbt.Keys._
/**
  * @author pfnguyen
  */
trait AndroidTestSettings extends AutoPlugin {
  override def projectSettings: Seq[Def.Setting[_]] = super.projectSettings ++ inConfig(Compile)(List(
    sourceGenerators += debugTestsGenerator.taskValue
  )) ++ inConfig(Android)(List(
    // TODO actually implement support for AndroidLib/AndroidJar
    test                     := testTaskDef.value,
    test                     := (test dependsOn (compile in Android, install.?)).value,
    testOnly                 := testOnlyTaskDef.evaluated,
    testAggregate            := testAggregateTaskDef.value,
    instrumentTestTimeout    := 180000,
    instrumentTestRunner     := "android.test.InstrumentationTestRunner",
    debugTestsGenerator      := {
      val tests = debugIncludesTests.?.value.getOrElse(false)
      val layout = projectLayout.value
        if (tests)
          (layout.testScalaSource ** "*.scala").get ++
            (layout.testJavaSource ** "*.java").get
        else Nil
    }
  )) ++ inConfig(Android)(Defaults.compileAnalysisSettings ++ List(
    // stuff to support `android:compile`
    scalacOptions               := (scalacOptions in Compile).value,
    javacOptions                := (javacOptions in Compile).value,
    manipulateBytecode          := compileIncremental.value,
    TaskKey[Option[xsbti.Reporter]]("compilerReporter") := None,
    compileIncremental          := Defaults.compileIncrementalTask.value,
    compile := Def.taskDyn {
      if (executionRoots.value.size == 1) {
        val task = executionRoots.value.head
        val cfg: String = task.scope.config.toOption.map(_.name).getOrElse("")
        val tnme = task.key.label
        if (cfg == "android" && tnme == "compile")
          fail("'android:compile' should not be used directly\n" +
            "perhaps you mean 'compile' or 'android:test'")
      }
      if (debugIncludesTests.?.value.getOrElse(false)) Def.task {
        (compile in Compile).value
      } else Defaults.compileTask
    }.value,
    compileIncSetup := {
      Compiler.IncSetup(
        Defaults.analysisMap((dependencyClasspath in AndroidTestInternal).value),
        definesClass.value,
        (skip in compile).value,
        // TODO - this is kind of a bad way to grab the cache directory for streams...
        streams.value.cacheDirectory / compileAnalysisFilename.value,
        compilerCache.value,
        incOptions.value)
    },
    compileInputs in compile := {
      val cp = classDirectory.value +: Attributed.data((dependencyClasspath in AndroidTestInternal).value)
      Compiler.inputs(cp, sources.value, classDirectory.value, scalacOptions.value, javacOptions.value, maxErrors.value, sourcePositionMappers.value, compileOrder.value)(compilers.value, compileIncSetup.value, streams.value.log)
    },
    compileAnalysisFilename := {
      // Here, if the user wants cross-scala-versioning, we also append it
      // to the analysis cache, so we keep the scala versions separated.
      val extra =
      if (crossPaths.value) s"_${scalaBinaryVersion.value}"
      else ""
      s"inc_compile$extra"
    }

  )) ++ inConfig(AndroidTest)(List(
    aars in AndroidTest := Tasks.androidTestAarsTaskDef.value,
    managedClasspath := Classpaths.managedJars(AndroidTest, classpathTypes.value, update.value),
    externalDependencyClasspath := managedClasspath.value ++
      (aars in AndroidTest).value.map(a => Attributed.blank(a.getJarFile)),
    dependencyClasspath := externalDependencyClasspath.value ++ (internalDependencyClasspath in Runtime).value
  )) ++ List(
   dependencyClasspath in AndroidTestInternal := (dependencyClasspath in AndroidTest).value ++ (dependencyClasspath in Runtime).value
  )
}
