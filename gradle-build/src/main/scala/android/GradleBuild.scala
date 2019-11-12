package android

import java.io.{ File, OutputStream }
import java.util.concurrent.TimeUnit

import android.Keys._
import com.android.builder.model.{ PackagingOptions => _, _ }
import com.hanhuy.gradle.discovery.GradleBuildModel
import org.gradle.tooling.internal.consumer.DefaultGradleConnector
import org.gradle.tooling.{ GradleConnector, ModelBuilder, ProjectConnection }
import sbt.Keys._
import sbt._

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.language.postfixOps
import scala.util.Try
import Serializer._
import com.android.tools.lint.LintCliFlags
import com.android.tools.lint.detector.api.Severity
import sbt.librarymanagement.MavenRepository

import scala.util.matching.Regex
/**
 * @author pfnguyen
 */
object AndroidGradlePlugin extends AutoPlugin {

  val Gradle = sbt.config("gradle")

  override def trigger = allRequirements
  override def requires: AndroidGlobalPlugin.type = android.AndroidGlobalPlugin

  override def buildSettings = List(
    onLoad in Global := (onLoad in Global).value andThen { s =>
      Project.runTask(updateCheck in Gradle, s).fold(s)(_._1)
    },
    updateCheck in Gradle := {
      val log = streams.value.log
      UpdateChecker("pfn", "sbt-plugins", "sbt-android-gradle") {
        case Left(t) =>
          log.debug("Failed to load version info: " + t)
        case Right((versions, current)) =>
          log.debug("available versions: " + versions)
          log.debug("current version: " + android.gradle.BuildInfo.version)
          log.debug("latest version: " + current)
          if (versions(android.gradle.BuildInfo.version)) {
            if (android.gradle.BuildInfo.version != current) {
              log.warn(
                s"UPDATE: A newer sbt-android-gradle is available:" +
                  s" $current, currently running: ${android.gradle.BuildInfo.version}")
            }
          }
      }
    }
  )

  object autoImport {
    implicit class AndroidGradleProject(val project: Project) extends AnyVal {
      def withExtraProperties: Project = {
        val properties = android.Tasks.loadProperties(project.base)
        val flavor = Option(properties.getProperty("build.flavor"))
        val buildType = Option(properties.getProperty("build.type"))
        if (flavor.nonEmpty || buildType.nonEmpty) {
          project.settings(android.withVariant(project.id, buildType, flavor))
        } else
          project
      }
    }
  }

  val generatedScript: File = file(".") / "00-gradle-generated.sbt"

  def inGradleProject(project: String)(ss: Seq[Setting[_]]): Seq[Setting[_]] =
    ss map VariantSettings.fixProjectScope(ProjectRef(file(".").getCanonicalFile, project))

  def importFromGradle(): Unit = {
    val start = System.currentTimeMillis
    val initgradle = IO.readLinesURL(Resources.resourceUrl("plugin-init.gradle"))
    val f = File.createTempFile("plugin-init", ".gradle")
    IO.writeLines(f, initgradle)
    f.deleteOnExit()

    if (generatedScript.exists) {
      println("Updating project because gradle scripts have changed")
    }

    println("Searching for android gradle projects...")
    val gconnection = GradleConnector.newConnector.asInstanceOf[DefaultGradleConnector]
    gconnection.daemonMaxIdleTime(60, TimeUnit.SECONDS)
    gconnection.setVerboseLogging(false)

    try {
      val rawdiscovered = GradleBuildSerializer.toposort(processDirectoryAt(file("."), f, gconnection)._2)
      val existing = rawdiscovered.map(_.id).toSet
      val discovered = rawdiscovered.map(d => d.copy(dependencies = d.dependencies.filter(existing)))
      f.delete()

      val end = System.currentTimeMillis
      val elapsed = (end - start) / 1000.0f
      println(f"Discovered gradle projects (in $elapsed%.02fs):")
      println(discovered.map(p => f"${p.id}%20s at ${p.base}").mkString("\n"))
      IO.write(generatedScript,
        "// AUTO-GENERATED SBT FILE, DO NOT MODIFY" ::
          (discovered map (_.serialized)) mkString "\n" replace ("\r", ""))
    } catch {
      case ex: Exception =>
        @tailrec
        def collectMessages(e: Throwable, msgs: List[String] = Nil, seen: Set[Throwable] = Set.empty): String = {
          if (e == null || seen(e))
            msgs.mkString("\n")
          else
            collectMessages(e.getCause, e.getMessage :: msgs, seen + e)
        }
        if (ex.getCause == null || ex == ex.getCause)
          throw ex
        else
          throw new MessageOnlyException(collectMessages(ex))
    }
  }

  def checkGeneratedScript(): Unit = {
    val base = file(".")
    val gradles = base ** "*.gradle" get
    val lastModified = generatedScript.lastModified
    if (gradles exists (_.lastModified > lastModified))
      importFromGradle()
    else {
      println("Loading cached gradle project definitions")
    }
  }

  checkGeneratedScript()

  val nullsink: OutputStream = new OutputStream {
    override def write(b: Int): Unit = ()
  }

  /** load gradle options from properties file, quoted/spaced options are not supported */
  def gradleOptions: List[String] = {
    val properties = android.Tasks.loadProperties(file("."))
    Option(properties.getProperty("gradle.options")).fold(List.empty[String])(_.split("\\s+").toList)
  }

  def modelBuilder[A](c: ProjectConnection, model: Class[A]): ModelBuilder[A] = {
    c.model(model)
      .setStandardOutput(nullsink)
      .setStandardError(nullsink)
  }
  def initScriptModelBuilder[A](c: ProjectConnection, model: Class[A], initscript: File): ModelBuilder[A] = {
    val options = "--init-script" :: initscript.getAbsolutePath :: gradleOptions
    modelBuilder(c, model).withArguments(options: _*)
  }

  def gradleBuildModel(c: ProjectConnection, initscript: File): GradleBuildModel =
    initScriptModelBuilder(c, classOf[GradleBuildModel], initscript).get()

  import GradleBuildSerializer._
  def processBaseConfig(config: BaseConfig): List[SbtSetting] = {
    List(
      buildConfigOptions /++= config.getBuildConfigFields.asScala.toList map { case (key, field) =>
        (field.getType, key, field.getValue)
      },
      resValues /++= config.getResValues.asScala.toList map { case (key, field) =>
          (field.getType, key, field.getValue)
      },
      proguardOptions /++= config.getProguardFiles.asScala.toList.flatMap(IO.readLines(_, IO.utf8)),
      manifestPlaceholders /++= config.getManifestPlaceholders.asScala.toMap map { case (k,o) => (k,o.toString) }
    ) ++ Option(config.getMultiDexEnabled).toList.map { b =>
      dexMulti /:= Literal("dexMulti.?.value.getOrElse(false) || " + b.toString)
    } ++ Option(config.getMultiDexKeepFile).toList.map {
      dexMainClasses /++= IO.readLines(_, IO.utf8)
    }
  }

  def processBuildType(buildType: BuildType): SbtBuildType = {
    val debuggable = buildType.isDebuggable
    val minify = buildType.isMinifyEnabled
    SbtBuildType(buildType.getName, processBaseConfig(buildType) ++ List(
      apkbuildDebug /:= Literal(
        s"""{
          |      val debug = apkbuildDebug.value
          |      debug($debuggable)
          |      debug
          |    }""".stripMargin),
      rsOptimLevel /:= buildType.getRenderscriptOptimLevel,
      if (debuggable)
        useProguardInDebug /:= minify
      else
        useProguard /:= minify
    ) ++ Option(buildType.getApplicationIdSuffix).toList.map { suf =>
      applicationId /:= Literal("applicationId.value + " + enc(suf))
    } ++ Option(buildType.getVersionNameSuffix).toList.map { suf =>
      versionName /:= Literal(s"versionName.value map (_ + ${enc(suf)})")
    } ++ Option(buildType.getSigningConfig).toList.flatMap { sc =>
      val store = Option(sc.getStoreFile)
      val storePass = Option(sc.getStorePassword)
      val alias = Option(sc.getKeyAlias)
      val storeType = Option(sc.getStoreType)
      val keyPass = Option(sc.getKeyPassword)
      if (store.nonEmpty && alias.nonEmpty) {
        val sp = storePass orElse keyPass
        val cfg = if (sp.isEmpty)
          PromptPasswordsSigningConfig(store.get, alias.get, storeType getOrElse "jks")
        else
          PlainSigningConfig(store.get, storePass.get, alias.get, keyPass, storeType getOrElse "jks")
        List(
          apkSigningConfig /:= Option(cfg)
        )
      } else {
        Nil
      }
    })
  }
  def processFlavor(flavor: ProductFlavor): SbtFlavor = {
    SbtFlavor(flavor.getName, processBaseConfig(flavor) ++ List(
    ) ++ Option(flavor.getApplicationId).toList.map {
      applicationId /:= _
    } ++ Option(flavor.getVersionCode).toList.map { i =>
      versionCode /:= Some(i.toInt)
    } ++ Option(flavor.getVersionName).toList.map {
      versionName /:= Some(_)
    } ++ Option(flavor.getMinSdkVersion).toList.map {
      minSdkVersion /:= _.getApiString
    } ++ Option(flavor.getTargetSdkVersion).toList.map {
      targetSdkVersion /:= _.getApiString
    } ++ Option(flavor.getRenderscriptTargetApi).toList.map {
      rsTargetApi /:= _.toString
    } ++ Option(flavor.getRenderscriptSupportModeEnabled).toList.map { b =>
      rsSupportMode /:= Literal("rsSupportMode.value || " + b.toString)
    })
  }

  def processSourceProvider(provider: SourceProvider): List[SbtSetting] = {
    def extraDirectories(dirs: java.util.Collection[File], key: SettingKey[Seq[File]]): List[SbtSetting] =
      dirs.asScala.map(d => key /+= d).toList

    extraDirectories(provider.getJavaDirectories, unmanagedSourceDirectories in Compile) ++
      extraDirectories(provider.getResDirectories, extraResDirectories) ++
      extraDirectories(provider.getResourcesDirectories, resourceDirectories in Compile) ++
      extraDirectories(provider.getAssetsDirectories, extraAssetDirectories)
  }
  def processDirectoryAt(base: File, initscript: File,
                         connector: GradleConnector,
                         repositories: List[Resolver] = Nil, seen: Set[File] = Set.empty,
                         origin: Option[File] = None,
                         path: Option[String] = None): (Set[File],List[SbtProject]) = {
    val c = connector.forProjectDirectory(base).connect()
    val model = gradleBuildModel(c, initscript)
    val prj = model.getGradleProject
    val discovery = model.getDiscovery
    val repos = repositories ++ (
      model.getRepositories.getResolvers.asScala.toList map (r =>
        r.getUrl.toString at r.getUrl.toString))

    val (visited,subprojects) = prj.getChildren.asScala.toList.foldLeft((seen + base.getCanonicalFile,List.empty[SbtProject])) { case ((saw,acc),child) =>
      // gradle 2.4 added getProjectDirectory
      val childDir = Try(child.getProjectDirectory).getOrElse(file(child.getPath.replace(":", ""))).getCanonicalFile
      if (!saw(childDir)) {
        println("Processing gradle sub-project at: " + childDir.getCanonicalPath)
        val (visited, subs) = processDirectoryAt(childDir,
          initscript, connector, repos, saw + childDir,
          origin orElse Some(base),
          origin flatMap { o => childDir.getParentFile.getCanonicalFile relativeTo
            o.getCanonicalFile } map (_.getPath))
        (visited ++ saw, subs ++ acc)
      } else
        (saw,acc)
    }

    try {
      if (discovery.isApplication || discovery.isLibrary) {
        val ap = model.getAndroidProject
        // Try, this was added fairly recently (2.0.0?)
        val buildTools = Try(ap.getBuildToolsVersion).toOption.map(v => buildToolsVersion /:= Some(v))
        val aaptOptions = aaptAdditionalParams /++= Try(ap.getAaptOptions).toOption.flatMap(a => Try(a.getAdditionalParameters).toOption.filter(_ != null)).fold(List.empty[String])(_.asScala.toList)
        val lintOptions = ap.getLintOptions

        val lintAndAapt = List(
          aaptOptions,
          lintStrict /:= lintOptions.isAbortOnError,
          lintFlags /:= {
            val severities = Map(
              LintOptions.SEVERITY_FATAL -> Severity.FATAL,
              LintOptions.SEVERITY_ERROR -> Severity.ERROR,
              LintOptions.SEVERITY_WARNING -> Severity.WARNING,
              LintOptions.SEVERITY_INFORMATIONAL -> Severity.INFORMATIONAL,
              LintOptions.SEVERITY_IGNORE -> Severity.IGNORE)
            val flags = new LintCliFlags
            flags.getSuppressedIds.addAll(lintOptions.getDisable)
            flags.getEnabledIds.addAll(lintOptions.getEnable)
            if (lintOptions.getCheck != null)
              flags.setExactCheckedIds(lintOptions.getCheck)
            flags.setFullPath(lintOptions.isAbsolutePaths)
            flags.setShowSourceLines(!lintOptions.isNoLines)
            flags.setQuiet(lintOptions.isQuiet)
            flags.setCheckAllWarnings(lintOptions.isCheckAllWarnings)
            flags.setIgnoreWarnings(lintOptions.isIgnoreWarnings)
            flags.setWarningsAsErrors(lintOptions.isWarningsAsErrors)
            flags.setExplainIssues(lintOptions.isExplainIssues)
            flags.setShowEverything(lintOptions.isShowAll)
            flags.setDefaultConfiguration(lintOptions.getLintConfig)
            flags.setSeverityOverrides(Option(lintOptions.getSeverityOverrides).fold(Map.empty[String,Severity])(_.asScala.toMap.mapValues(severities(_))).asJava)
            flags
          }
        )

        val sourceVersion = ap.getJavaCompileOptions.getSourceCompatibility
        val targetVersion = ap.getJavaCompileOptions.getTargetCompatibility

        val default = ap.getDefaultConfig
        val flavors = ap.getProductFlavors.asScala.toList map { flavorContainer =>
          val flavor = flavorContainer.getProductFlavor
          val sources = flavorContainer.getSourceProvider

          val f = processFlavor(flavor)
          f.copy(settings = f.settings ++ processSourceProvider(sources))
        }
        val buildTypes = ap.getBuildTypes.asScala.toList map { buildContainer =>
          val buildType = buildContainer.getBuildType
          val sources = buildContainer.getSourceProvider
          val bt = processBuildType(buildType)
          bt.copy(settings = bt.settings ++ processSourceProvider(sources))
        }
        val sourceProvider = default.getSourceProvider
        val defaultConfig = processFlavor(default.getProductFlavor)
        val optional: List[SbtSetting] = Option(model.getPackagingOptions).toList.map {
          p => packagingOptions /:= PackagingOptions(
            p.getExcludes.asScala.toList, p.getPickFirsts.asScala.toList, p.getMerges.asScala.toList
          )
        }
        val v = ap.getVariants.asScala.head
        val art = v.getMainArtifact
        def libraryDependency(m: MavenCoordinates, isProvided: Boolean): ModuleID = {
          val module = m.getGroupId % m.getArtifactId % m.getVersion intransitive()
          val mID = if (m.getPackaging == "jar") module else module.artifacts(
            Artifact(m.getArtifactId, m.getPackaging, m.getPackaging, Option(m.getClassifier), Vector.empty, None))
          if (isProvided) mID.withConfigurations(configurations = Some("provided")) else mID
        }

        val androidLibraries = art.getDependencies.getLibraries.asScala.toList
        val (aars,projects) = androidLibraries.partition(_.getProject == null)
        val (resolved,localAars) = aars.partition(a => Option(a.getResolvedCoordinates.getGroupId).exists(_.nonEmpty))
        val localAar = localAars map {
          android.Keys.localAars /+= _.getBundle
        }
        def dependenciesOf[A](l: A, seen: Set[File] = Set.empty)(children: A => List[A])(fileOf: A => File): List[A] = {
          val deps = children(l) filterNot(l => seen(fileOf(l)))
          deps ++ deps.flatMap(d => dependenciesOf(d, seen ++ deps.map(fileOf))(children)(fileOf))
        }
        def aarDependencies(l: AndroidLibrary): List[AndroidLibrary] =
          dependenciesOf(l)(_.getLibraryDependencies.asScala.toList)(_.getBundle)
        def javaDependencies(l: JavaLibrary): List[JavaLibrary] =
          dependenciesOf(l)(_.getDependencies.asScala.toList)(_.getJarFile)
        val allAar = (resolved ++ resolved.flatMap(aarDependencies)).groupBy(
            _.getBundle.getCanonicalFile).map(_._2.head).toList

        val javalibs = art.getDependencies.getJavaLibraries.asScala.toList
        val allJar = (javalibs ++ javalibs.flatMap(javaDependencies)).groupBy(
          _.getJarFile.getCanonicalFile).map(_._2.head).toList

        val libs = List(libraryDependencies /++= (allAar ++ allJar filter { j =>
          Option(j.getResolvedCoordinates).exists { c =>
            val g = c.getGroupId
            val n = c.getArtifactId
            g.nonEmpty && g != "__local_jars__" && (g != "com.google.android" || !n.startsWith("support-"))
          }
        } map { j =>
          libraryDependency(j.getResolvedCoordinates, Try(j.isProvided).getOrElse(false))
        }))

        val unmanaged = allJar filter (_.getResolvedCoordinates == null) map { j =>
          unmanagedJars in Compile /+= Attributed.blank(j.getJarFile)
        }

        def extraDirectories(dirs: java.util.Collection[File], key: SettingKey[Seq[File]]): Seq[SbtSetting] =
          dirs.asScala.tail.map(d => key /+= d).toSeq

        val standard = List(
          resolvers /++= repos,
          publishMavenStyle /:= false,
          platformTarget /:= ap.getCompileTarget,
          name /:= ap.getName,
          javacOptions in Compile /++= "-source" :: sourceVersion :: "-target" :: targetVersion :: Nil,
          debugIncludesTests /:= false, // default because can't express it easily otherwise
          projectLayout /:= new ProjectLayout.Wrapped(ProjectLayout(base)) {
            override def manifest: File = sourceProvider.getManifestFile
            override def javaSource: File = sourceProvider.getJavaDirectories.asScala.head
            override def resources: File = sourceProvider.getResourcesDirectories.asScala.head
            override def res: File = sourceProvider.getResDirectories.asScala.head
            override def renderscript: File = sourceProvider.getRenderscriptDirectories.asScala.head
            override def aidl: File = sourceProvider.getAidlDirectories.asScala.head
            override def assets: File = sourceProvider.getAssetsDirectories.asScala.head
            override def jniLibs: File = sourceProvider.getJniLibsDirectories.asScala.head
          }
        ) ++ extraDirectories(sourceProvider.getJavaDirectories, unmanagedSourceDirectories in Compile) ++
          extraDirectories(sourceProvider.getResDirectories, extraResDirectories) ++
          extraDirectories(sourceProvider.getResourcesDirectories, resourceDirectories in Compile) ++
          extraDirectories(sourceProvider.getAssetsDirectories, extraAssetDirectories)

        val sp = SbtProject(
          ap.getName, base, discovery.isApplication,
          projects.map(_.getProject.replace(":","")).toSet, buildTypes, flavors,
          optional ++ buildTools ++ lintAndAapt ++ libs ++ localAar ++ standard ++ unmanaged ++ defaultConfig.settings)
        (visited, sp :: subprojects)
      } else
        (visited, subprojects)
    } finally {
      c.close()
    }
  }
}

case class Literal(value: String)
object Serializer {
  def enc[T : Encoder](t: T): String = implicitly[Encoder[T]].encode(t)

  trait Encoder[T] {
    def encode(t: T): String
  }

  implicit val stringEncoder: Encoder[String] = new Encoder[String] {
    // broke ass """ won't handle \ u properly, SI-4706
//    def encode(s: String) = "raw\"\"\"" + s + "\"\"\""
    def quote(s: String): String = s.map {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\b' => "\\b"
      case '\f' => "\\f"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c => c
    }.mkString("\"", "", "\"")
    def encode(s: String): String = quote(s)
  }
  implicit val moduleEncoder: Encoder[sbt.ModuleID] = new Encoder[ModuleID] {
    def encode(m: ModuleID): String = {
      val base = s""""${m.organization}" % "${m.name}" % "${m.revision}""""
      base + m.configurations.fold("")(c => s""" % "$c"""") + m.explicitArtifacts.map(
        a =>
          if (a.classifier.isDefined) {
            s""" artifacts(Artifact(${enc(a.name)}, ${enc(a.`type`)}, ${enc(a.extension)}, ${enc(a.classifier.get)}))"""
          } else
            s""" artifacts(Artifact(${enc(a.name)}, ${enc(a.`type`)}, ${enc(a.extension)}))"""
      ).mkString("") + (if (m.isTransitive) "" else " intransitive()")
    }
  }
  implicit def seqEncoder[T : Encoder]: Encoder[Seq[T]] = new Encoder[Seq[T]] {
    def encode(l: Seq[T]): String = if (l.isEmpty) "Nil" else "Seq(" + l.map(i => enc(i)).mkString(",") + ")"
  }
  implicit def setEncoder[T : Encoder]: Encoder[Set[T]] = new Encoder[Set[T]] {
    def encode(l: Set[T]): String = if (l.isEmpty) "Set.empty" else "Set(" + l.map(i => enc(i)).mkString(",") + ")"
  }
  implicit val packagingOptionsEncoding: Encoder[PackagingOptions] = new Encoder[PackagingOptions] {
    def encode(p: PackagingOptions) =
      s"android.Keys.PackagingOptions(${enc(p.excludes)}, ${enc(p.pickFirsts)}, ${enc(p.merges)})"
  }
  implicit val fileEncoder: Encoder[File] = new Encoder[File] {
    def encode(f: File) = s"file(${enc(f.getCanonicalPath)})"
  }
  implicit def mutableSettingEncoder[T]: Encoder[MutableSetting[T]] = new Encoder[MutableSetting[T]] {
    def encode(m: MutableSetting[T]) = "NO OP THIS SHOULD NOT HAPPEN"
  }
  implicit def optionEncoder[T : Encoder]: Encoder[Option[T]] = new Encoder[Option[T]] {
    def encode(l: Option[T]): String = if (l.isEmpty) "None" else "Some(" + enc(l.get) + ")"
  }
  implicit def someEncoder[T : Encoder]: Encoder[Some[T]] = new Encoder[Some[T]] {
    def encode(l: Some[T]): String = "Some(" + enc(l.get) + ")"
  }
  implicit val antProjectLayoutEncoding: Encoder[ProjectLayout.Ant] = new Encoder[ProjectLayout.Ant] {
    def encode(p: ProjectLayout.Ant) =
      s"ProjectLayout.Ant(${enc(p.base)})"
  }
  implicit val gradleProjectLayoutEncoding: Encoder[ProjectLayout.Gradle] = new Encoder[ProjectLayout.Gradle] {
    def encode(p: ProjectLayout.Gradle) =
      s"ProjectLayout.Gradle(${enc(p.base)})"
  }

  implicit val ProjectLayoutEncoding: Encoder[ProjectLayout] = new Encoder[ProjectLayout] {
    def encode(p: ProjectLayout): String = p match {
      case x: ProjectLayout.Ant => enc(x)
      case x: ProjectLayout.Gradle => enc(x)
      case x: ProjectLayout.Wrapped =>
        s"""
           |    new ProjectLayout.Wrapped(ProjectLayout(baseDirectory.value)) {
           |      override def base = ${enc(x.base)}
           |      override def resources = ${enc(x.resources)}
           |      override def testSources = ${enc(x.testSources)}
           |      override def sources = ${enc(x.sources)}
           |      override def javaSource = ${enc(x.javaSource)}
           |      override def libs = ${enc(x.libs)}
           |      override def gen = ${enc(x.gen)}
           |      override def testRes = ${enc(x.testRes)}
           |      override def manifest = ${enc(x.manifest)}
           |      override def testManifest = ${enc(x.testManifest)}
           |      override def scalaSource = ${enc(x.scalaSource)}
           |      override def aidl = ${enc(x.aidl)}
           |      override def bin = ${enc(x.bin)}
           |      override def renderscript = ${enc(x.renderscript)}
           |      override def testScalaSource = ${enc(x.testScalaSource)}
           |      override def testAssets = ${enc(x.testAssets)}
           |      override def jni = ${enc(x.jni)}
           |      override def assets = ${enc(x.assets)}
           |      override def testJavaSource = ${enc(x.testJavaSource)}
           |      override def jniLibs = ${enc(x.jniLibs)}
           |      override def res = ${enc(x.res)}
           |    }
           |""".stripMargin
    }
  }
  implicit val boolEncoder: Encoder[Boolean] = new Encoder[Boolean] {
    def encode(b: Boolean): String = b.toString
  }
  implicit val signingConfigEncoding: Encoder[ApkSigningConfig] = new Encoder[ApkSigningConfig] {
    override def encode(t: ApkSigningConfig): String = t match {
      case PlainSigningConfig(ks, sp, al, kp, st, v1, v2) =>
        s"PlainSigningConfig(${enc(ks)}, ${enc(sp)}, ${enc(al)}, ${enc(kp)}, ${enc(st)}, ${enc(v1)}, ${enc(v2)})"
      case PromptPasswordsSigningConfig(ks, al, st, v1, v2) =>
        s"PromptPasswords(${enc(ks)}, ${enc(al)}, ${enc(st)}, ${enc(v1)}, ${enc(v2)})"
    }
  }
  implicit val wrappedProjectLayoutEncoding: Encoder[ProjectLayout.Wrapped] = new Encoder[ProjectLayout.Wrapped] {
    def encode(p: ProjectLayout.Wrapped) =
      s"new ProjectLayout.Wrapped(${enc(p.wrapped)})"
  }
  implicit val intEncoder: Encoder[Int] = new Encoder[Int] {
    def encode(i: Int): String = i.toString
  }

  implicit val literalEncoder: Encoder[Literal] = new Encoder[Literal] {
    def encode(literal: Literal): String = literal.value
  }
  implicit def listEncoder[T : Encoder]: Encoder[List[T]] = new Encoder[List[T]] {
    def encode(l: List[T]): String = if (l.isEmpty) "Nil" else "List(\n    " + l.map(i => enc(i)).mkString(",\n    ") + ")"
  }
  implicit val resolverEncoder: Encoder[sbt.Resolver] = new Encoder[Resolver] {
    def encode(r: Resolver): String = r match {
      case maven: MavenRepository => enc(maven.name) + " at " + enc(maven.root)
      case _ => throw new UnsupportedOperationException("Cannot handle: " + r)
    }
  }
  implicit def tuple3Encoder[A : Encoder,B : Encoder,C : Encoder]: Encoder[(A, B, C)] = new Encoder[(A,B,C)] {
    override def encode(t: (A, B, C)) = s"(${enc(t._1)}, ${enc(t._2)}, ${enc(t._3)})"
  }
  implicit def tuple2Encoder[A : Encoder,B : Encoder]: Encoder[(A, B)] = new Encoder[(A,B)] {
    override def encode(t: (A, B)) = s"(${enc(t._1)}, ${enc(t._2)})"
  }
  implicit def mapEncoder[A : Encoder,B : Encoder]: Encoder[Map[A, B]] = new Encoder[Map[A,B]] {
    override def encode(t: Map[A, B]): String = if (t.isEmpty) "Map.empty" else s"Map(${t.toList.map(e => enc(e)).mkString(",\n      ")})"
  }
  implicit def attributedEncoder[T : Encoder]: Encoder[sbt.Attributed[T]] = new Encoder[Attributed[T]] {
    def encode(l: Attributed[T]) = s"Attributed.blank(${enc(l.data)})"
  }
  implicit val severityEncoder: Encoder[Severity] = new Encoder[Severity] {
    override def encode(t: Severity): String = "Severity." + t.toString
  }
  implicit val lintCliFlagsEncoder: Encoder[LintCliFlags] = new Encoder[LintCliFlags] {
    override def encode(t: LintCliFlags): String =
      s"""{
         |      import collection.JavaConverters._
         |      import com.android.tools.lint.detector.api.Severity
         |      val flags = lintFlags.value
         |      flags.getSuppressedIds.addAll(${enc(t.getSuppressedIds.asScala.toSet)}.asJava)
         |      flags.getEnabledIds.addAll(${enc(t.getEnabledIds.asScala.toSet)}.asJava)
         |      flags.setExactCheckedIds(${Option(t.getExactCheckedIds).map(c => enc(c.asScala.toSet) + ".asJava").orNull})
         |      flags.setFullPath(${enc(t.isFullPath)})
         |      flags.setShowSourceLines(${enc(t.isShowSourceLines)})
         |      flags.setQuiet(${enc(t.isQuiet)})
         |      flags.setCheckAllWarnings(${enc(t.isCheckAllWarnings)})
         |      flags.setIgnoreWarnings(${enc(t.isIgnoreWarnings)})
         |      flags.setWarningsAsErrors(${enc(t.isWarningsAsErrors)})
         |      flags.setExplainIssues(${enc(t.isExplainIssues)})
         |      flags.setShowEverything(${enc(t.isShowEverything)})
         |      flags.setDefaultConfiguration(${Option(t.getDefaultConfiguration).map(enc(_)).orNull})
         |      flags.setSeverityOverrides(${enc(t.getSeverityOverrides.asScala.toMap)}.asJava)
         |      flags
         |    }
         |""".stripMargin
  }

  sealed trait Op {
    def serialized: String
  }

  def serialize[T : Manifest, U : Encoder](k: SettingKey[T], op: String, value: U): String =
    key(k) + " " + op + " " + enc(value)
  def serialize[T : Manifest, U : Encoder](k: TaskKey[T], op: String, value: U): String =
    key(k) + " " + op + " " + enc(value)
  def config(s: Scope) =
    s.config.toOption.fold("")(c => s""" in ${c.name.capitalize}""")
  val COLLECTIONS: Regex = """scala\.collection\.(\w+)""".r
  val IMMUTABLE_COLLECTIONS: Regex = """scala\.collection\.immutable\.(\w+)""".r
  val SBT: Regex = """sbt\.(\w+)""".r
  val ANDROID_KEY: Regex = """android\.Keys\.(\w+)""".r
  val ANDROID: Regex = """android\.(\w+)""".r
  val JAVA: Regex = """java\.lang\.(\w+)""".r
  val SCALA: Regex = """scala\.(\w+)""".r
  val FILE                  = """java.io.File"""
  val TUPLES: Regex = """scala.(Tuple\d+)""".r
  // TODO handle tuple naming
  def typeName[T](implicit manifest: Manifest[T]): String = {
    def capitalized(m: Manifest[_]) = {
      if (m.runtimeClass.isPrimitive)
        m.runtimeClass.getName.capitalize
      else {
        m.runtimeClass.getName match {
          case COLLECTIONS(tpe)           => tpe
          case IMMUTABLE_COLLECTIONS(tpe) => tpe
          case SBT(tpe)                   => tpe
          case ANDROID_KEY(tpe)           => tpe
          case ANDROID(tpe)               => tpe
          case JAVA(tpe)                  => tpe
          case TUPLES(tuple)              => tuple
          case SCALA(tpe)                 => tpe
          case FILE                       => "File"
          case fullName                   => fullName
        }
      }
    }
    val typename = capitalized(manifest)
    val types = if (manifest.typeArguments.isEmpty) "" else {
      s"[${manifest.typeArguments.map(m => typeName(m)).mkString(",")}]"
    }
    if (typename.startsWith("Tuple")) {
     "(" + types.replace("$",".").drop(1).dropRight(1) + ")"
    } else
      (typename + types).replace("$",".") // replace $ hack, better solution?
  }
  def key[T : Manifest](k: SettingKey[T]): String = {
    s"""SettingKey[${typeName[T]}]("${k.key.label}")""" + config(k.scope)
    k.key.label + config(k.scope)
  }
  def key[T : Manifest](k: TaskKey[T]): String = {
    s"""TaskKey[${typeName[T]}]("${k.key.label}")""" + config(k.scope)
    k.key.label + config(k.scope)
  }
}

object GradleBuildSerializer {
  case class SbtFlavor(name: String, settings: List[SbtSetting]) {
    def serializedSettings: String = settings map (_.serialized) mkString ",\n    "

    def serialized: String =
      s"""
        |  flavors += ((${enc(name)}, List(
        |    $serializedSettings)))
        |""".stripMargin
  }
  case class SbtBuildType(name: String, settings: List[SbtSetting]) {
    def serializedSettings: String = settings map (_.serialized) mkString ",\n    "

    def serialized: String =
      s"""
         |  buildTypes += ((${enc(name)}, List(
         |    $serializedSettings)))
         |""".stripMargin
  }
  case class SbtProject(id: String, base: File, isApplication: Boolean,
                        dependencies: Set[String], buildTypes: Seq[SbtBuildType],
                        flavors: Seq[SbtFlavor], settings: Seq[SbtSetting]) {
    override def toString = s"SbtProject(id=$id, base=$base, dependencies=$dependencies)"
    def escaped(s: String): String = {
      val needEscape = s.zipWithIndex exists { case (c, i) =>
        (i == 0 && !Character.isJavaIdentifierStart(c)) || (i != 0 && !Character.isJavaIdentifierPart(c))
      }
      if (needEscape) {
        s"`$s`"
      } else s
    }
    lazy val serializedBuildTypes: String = {
      buildTypes.map(".settings(" + _.serialized + ")").mkString("")
    }
    lazy val serializedFlavors: String = {
      flavors.map(".settings(" + _.serialized + ")").mkString("")
    }
    def dependsOnProjects: String = {
      if (dependencies.nonEmpty) ".dependsOn(" + dependencies.map(escaped).mkString(",") + ")" else ""
    }
    def serialized: String =
      s"""
         |val ${escaped(id)} = project.in(
         |  ${enc(base)}
         |).enablePlugins(${if (isApplication) "AndroidApp" else "AndroidLib"}).settings(
         |  ${settings.map(_.serialized).mkString(",\n  ")}
         |)$serializedBuildTypes$serializedFlavors.withExtraProperties
         |$dependsOnProjects
         |""".stripMargin
  }

  def toposort(ps: List[SbtProject]): List[SbtProject] = {
    val projectMap = ps.map(p => (p.id.replace(":", ""), p)).toMap
    Dag.topologicalSort(ps)(_.dependencies flatMap projectMap.get)
  }

  import language.existentials
  case class SbtSetting(key: String, serialized: String)
  object Op {
    case object := extends Op {
      val serialized = ":="
      def apply[T : Encoder : Manifest](lhs: SettingKey[T], rhs: T) = SbtSetting(lhs.key.label, serialize(lhs, serialized, rhs))
      def apply[T : Encoder : Manifest](lhs: TaskKey[T], rhs: T) = SbtSetting(lhs.key.label, serialize(lhs, serialized, rhs))
      def apply[T : Manifest](lhs: SettingKey[T], rhs: Literal) = SbtSetting(lhs.key.label, serialize(lhs, serialized, rhs))
      def apply[T : Manifest](lhs: TaskKey[T], rhs: Literal) = SbtSetting(lhs.key.label, serialize(lhs, serialized, rhs))
    }
    case object += extends Op {
      val serialized = "+="
      def apply[T : Manifest,U : Encoder](lhs: SettingKey[T], rhs: U) = SbtSetting(lhs.key.label, serialize(lhs, serialized, rhs))
      def apply[T : Manifest,U : Encoder](lhs: TaskKey[T], rhs: U) = SbtSetting(lhs.key.label, serialize(lhs, serialized, rhs))
      def apply[T : Manifest](lhs: SettingKey[T], rhs: Literal) = SbtSetting(lhs.key.label, serialize(lhs, serialized, rhs))
      def apply[T : Manifest](lhs: TaskKey[T], rhs: Literal) = SbtSetting(lhs.key.label, serialize(lhs, serialized, rhs))
    }
    case object ++= extends Op {
      val serialized = "++="
      def apply[T : Manifest, U : Encoder](lhs: SettingKey[T], rhs: U) = SbtSetting(lhs.key.label, serialize(lhs, serialized, rhs))
      def apply[T : Manifest, U : Encoder](lhs: TaskKey[T], rhs: U) = SbtSetting(lhs.key.label, serialize(lhs, serialized, rhs))
      def apply[T : Manifest](lhs: SettingKey[T], rhs: Literal) = SbtSetting(lhs.key.label, serialize(lhs, serialized, rhs))
      def apply[T : Manifest](lhs: TaskKey[T], rhs: Literal) = SbtSetting(lhs.key.label, serialize(lhs, serialized, rhs))
    }
  }

  implicit class SerializableSettingKey[T : Encoder : Manifest](val k: SettingKey[T]) {
    def /:=(t: T): SbtSetting = Op := (k, t)
    def /:=(t: Literal): SbtSetting = Op := (k, t)
    def /+=[U : Encoder](u: U): SbtSetting = Op += (k, u)
    def /+=(u: Literal): SbtSetting = Op += (k, u)
    def /++=[U : Encoder](u: U): SbtSetting = Op ++= (k, u)
    def /++=(u: Literal): SbtSetting = Op ++= (k, u)
  }
  implicit class SerializableTaskKey[T : Encoder : Manifest](val k: TaskKey[T]) {
    def /:=(t: T): SbtSetting = Op := (k, t)
    def /:=(t: Literal): SbtSetting = Op := (k, t)
    def /+=[U : Encoder](u: U): SbtSetting = Op += (k, u)
    def /+=(u: Literal): SbtSetting = Op += (k, u)
    def /++=[U : Encoder](u: U): SbtSetting = Op ++= (k, u)
    def /++=(u: Literal): SbtSetting = Op ++= (k, u)
  }
}
