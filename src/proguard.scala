package android

import java.io.{File, FileInputStream, FileOutputStream}
import java.util.jar.{JarInputStream, JarOutputStream}

import com.android.builder.core.AndroidBuilder
import sbt._
import sbt.classpath.ClasspathUtilities

import scala.language.postfixOps
import scala.util.Try

case class ProguardInputs(injars: Seq[Attributed[File]],
                          libraryjars: Seq[File],
                          proguardCache: Option[File] = None)

object ProguardUtil {
  // write to output jar directly, no intermediate unpacking
  def createCacheJar(proguardJar: File, outJar: File,
                     rules: Seq[String], log: Logger): Unit = {
    log.info("Creating proguard cache: " + outJar.getName)
    // not going to use 'Using' here because it's so ugly!
    val jin = new JarInputStream(new FileInputStream(proguardJar))
    val jout = new JarOutputStream(new FileOutputStream(outJar))
    try {
      val buf = Array.ofDim[Byte](32768)
      Stream.continually(jin.getNextJarEntry) takeWhile (_ != null) filter { entry =>
        inPackages(entry.getName, rules) && !entry.getName.matches(".*/R\\W+.*class")
        } foreach {
        entry =>
          jout.putNextEntry(entry)
          Stream.continually(jin.read(buf, 0, 32768)) takeWhile (_ != -1) foreach { r =>
            jout.write(buf, 0, r)
          }
      }
    } finally {
      jin.close()
      jout.close()
    }
  }

  def startsWithAny(s: String, ss: Seq[String]): Boolean = ss exists s.startsWith
  def inPackages(s: String, pkgs: Seq[String]): Boolean =
    startsWithAny(s.replace('/','.'), pkgs map (_ + "."))

  def listjar(jarfile: Attributed[File]): List[String] = {
    if (!jarfile.data.isFile) Nil
    else {
      Using.fileInputStream(jarfile.data)(Using.jarInputStream(_) { jin =>
        val classes = Iterator.continually(jin.getNextJarEntry) takeWhile (
          _ != null) map (_.getName) filter { n =>
          // R.class (and variants) are irrelevant
          n.endsWith(".class") && !n.matches(".*/R\\W+.*class")
        } toList

        classes
      })
    }
  }
}

object Proguard {
  import ProguardUtil._

  def collectInjars(l: Seq[File], d: sbt.Def.Classpath, s: Boolean, c: File): Seq[Attributed[File]] = d.filter { a =>
    val in = a.data
    (s || !in.getName.startsWith("scala-library")) &&
        !l.exists { i => i.getName == in.getName} &&
        in.isFile
  }.distinct :+ Attributed.blank(c)

  def libraryJars(p: String, x: Seq[String], l: Seq[File]) = {
    def extras = x map file
    file(p) +: (extras ++ l)
  }

  def proguardInputs(u: Boolean, pgOptions: Seq[String], pgConfig: Seq[String],
                     l: Seq[File], d: sbt.Def.Classpath, p: String,
                     x: Seq[String], c: File, s: Boolean, pc: Seq[String],
                     debug: Boolean, st: sbt.Keys.TaskStreams) = {

    val cacheDir = st.cacheDirectory
    if (u) {
      val injars = collectInjars(l, d, s, c)
      val extras = x map (f => file(f))

      if (debug && pc.nonEmpty) {
        st.log.debug("Proguard cache rules: " + pc)
        val deps = cacheDir / "proguard_deps"
        val out = cacheDir / "proguard_cache"

        deps.mkdirs()
        out.mkdirs()

        // TODO cache resutls of jar listing
        val cacheJars = injars filter (listjar(_) exists (inPackages(_, pc))) toSet
        val filtered = injars filterNot cacheJars

        val indeps = filtered map {
          f => deps / (f.data.getName + "-" +
            Hash.toHex(Hash(f.data.getAbsolutePath)))
        }

        val todep = indeps zip filtered filter { case (dep,j) =>
          !dep.exists || dep.lastModified < j.data.lastModified
        }
        todep foreach { case (dep,j) =>
          st.log.info("Finding dependency references for: " +
            (j.get(sbt.Keys.moduleID.key) getOrElse j.data.getName))
          IO.write(dep, ReferenceFinder(j.data, pc.map(_.replace('.','/'))) mkString "\n")
        }

        val alldeps = (indeps flatMap {
          dep => IO.readLines(dep) }).sortWith(_>_).distinct.mkString("\n")

        val allhash = Hash.toHex(Hash((pgConfig ++ pgOptions).mkString("\n") +
          "\n" + pc.mkString(":") + "\n" + alldeps))

        val cacheJar = out / ("proguard-cache-" + allhash + ".jar")
        FileFunction.cached(st.cacheDirectory / s"cacheJar-$allhash", FilesInfo.hash) { in =>
          cacheJar.delete()
          in
        }(cacheJars map (_.data))

        ProguardInputs(injars, libraryJars(p, x, l), Some(cacheJar))
      } else ProguardInputs(injars, libraryJars(p, x, l))
    } else
      ProguardInputs(Seq.empty,Seq.empty)
  }

  def proguard(a: Aggregate.Proguard, bldr: AndroidBuilder, l: Boolean,
               inputs: ProguardInputs, debug: Boolean, b: File,
               ra: Aggregate.Retrolambda, s: sbt.Keys.TaskStreams) = {
    val cp = a.managedClasspath
    val p = a.useProguard
    val d = a.useProguardInDebug
    val c = a.proguardConfig
    val o = a.proguardOptions
    val pc = a.proguardCache
    val re = ra.enable

    if (inputs.proguardCache exists (_.exists)) {
      s.log.info("[debug] cache hit, skipping proguard!")
      None
    } else if ((p && !debug && !l) || ((d && debug) && !l)) {
      val pjars = inputs.injars map (_.data)
      val jars = if (re && RetrolambdaSupport.isAvailable)
        RetrolambdaSupport(b, pjars, ra.classpath, ra.bootClasspath, s) else pjars
      val t = b / "classes.proguard.jar"

      val cfg = buildFullConfig(a, ra, inputs, b, s)
      val ruleCache = s.cacheDirectory / "proguard-rules.hash"
      val cacheHash = Try(IO.read(ruleCache)).toOption getOrElse ""
      val rulesHash = Hash.toHex(Hash(cfg mkString "\n"))

      if (jars.exists( _.lastModified > t.lastModified ) || cacheHash != rulesHash) {
        cfg foreach (l => s.log.debug(l))
        IO.write(s.cacheDirectory / "proguard-rules.hash", rulesHash)
        runProguard(cp, cfg)
      } else {
        s.log.info(t.getName + " is up-to-date")
      }
      inputs.proguardCache foreach {
        ProguardUtil.createCacheJar(t, _, pc, s.log)
      }
      Option(t)
    } else None
  }

  def runProguard(classpath: Def.Classpath, cfg: Seq[String]): Unit = {
    import language.reflectiveCalls
    val cl = ClasspathUtilities.toLoader(classpath map (_.data))
    type ProG = {
      def execute(): Unit
    }
    val cfgClass = cl.loadClass("proguard.Configuration")
    val pgcfg    = cfgClass.newInstance().asInstanceOf[AnyRef]
    val pgClass  = cl.loadClass("proguard.ProGuard")
    val cpClass  = cl.loadClass("proguard.ConfigurationParser")
    val cpCtor   = cpClass.getConstructor(classOf[Array[String]], classOf[java.util.Properties])
    val cpMethod = cpClass.getDeclaredMethod("parse", cfgClass)
    cpMethod.setAccessible(true)
    val pgCtor   = pgClass.getConstructor(cfgClass)

    val cparser = cpCtor.newInstance(cfg.toArray[String], new java.util.Properties)
    cpMethod.invoke(cparser, pgcfg)
    val pg = pgCtor.newInstance(pgcfg).asInstanceOf[ProG]
    pg.execute()
  }

  def buildFullConfig(a: Aggregate.Proguard, ra: Aggregate.Retrolambda,
                      inputs: ProguardInputs, b: File,
                      s: sbt.Keys.TaskStreams): Seq[String] = {
    val libjars = inputs.libraryjars
    val jars = jarsClasspath(inputs, b, ra, s)
    val t = b / "classes.proguard.jar"

    val libraryjars = for {
      j <- libjars
      a <- Seq("-libraryjars", j.getAbsolutePath)
    } yield a
    val injars = jars map {
      "-injars " + _.getPath + "(!META-INF/**,!rootdoc.txt)"
    }
    val outjars = "-outjars " + t.getAbsolutePath
    val printmappings = Seq("-printmapping", mappingsFile(b))

    a.proguardConfig ++ a.proguardOptions ++ libraryjars ++ printmappings ++ injars :+ outjars
  }

  def jarsClasspath(inputs: ProguardInputs, b: File, ra: Aggregate.Retrolambda, s: sbt.Keys.TaskStreams) = {
    val pjars = inputs.injars map (_.data)
    if (ra.enable && RetrolambdaSupport.isAvailable)
      RetrolambdaSupport(b, pjars, ra.classpath, ra.bootClasspath, s)
    else pjars
  }

  def mappingsFile(b: File) = (b / "mappings.txt").getAbsolutePath
}