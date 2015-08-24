package android

import java.io.{File, FileOutputStream, FileInputStream}
import java.util.jar.{JarOutputStream, JarInputStream}

import com.android.SdkConstants
import com.android.builder.core.{DexOptions, AndroidBuilder}
import com.android.sdklib.SdkVersionInfo
import com.google.common.base.Charsets
import com.google.common.hash.Hashing
import proguard.{Configuration => PgConfig, ProGuard, ConfigurationParser}
import sbt._

import scala.util.Try
import language.postfixOps

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

  def proguardInputs(u: Boolean, pgOptions: Seq[String], pgConfig: Seq[String],
                     l: Seq[File], d: sbt.Def.Classpath, p: String,
                     x: Seq[String], c: File, s: Boolean, pc: Seq[String],
                     debug: Boolean, st: sbt.Keys.TaskStreams) = {

    val cacheDir = st.cacheDirectory
    if (u) {
      val injars = d.filter { a =>
        val in = a.data
        (s || !in.getName.startsWith("scala-library")) &&
          !l.exists { i => i.getName == in.getName} &&
          in.isFile
      }.distinct :+ Attributed.blank(c)
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
          IO.write(dep, ReferenceFinder(j.data, pc) mkString "\n")
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

        ProguardInputs(injars, file(p) +: (extras ++ l), Some(cacheJar))
      } else ProguardInputs(injars, file(p) +: (extras ++ l))
    } else
      ProguardInputs(Seq.empty,Seq.empty)
  }

  def proguard(a: Aggregate.Proguard, bldr: AndroidBuilder, l: Boolean,
               inputs: ProguardInputs, debug: Boolean, b: File,
               ra: Aggregate.Retrolambda, s: sbt.Keys.TaskStreams) = {
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
      val libjars = inputs.libraryjars
      val pjars = inputs.injars map (_.data)
      val jars = if (re && RetrolambdaSupport.isAvailable)
        RetrolambdaSupport(b, pjars, ra.classpath, bldr, s) else pjars
      val t = b / "classes.proguard.jar"

      val libraryjars = for {
        j <- libjars
        a <- Seq("-libraryjars", j.getAbsolutePath)
      } yield a
      val injars = "-injars " + (jars map {
        _.getPath + "(!META-INF/**,!rootdoc.txt)"
      } mkString File.pathSeparator)
      val outjars = "-outjars " + t.getAbsolutePath
      val printmappings = Seq("-printmapping",
        (b / "mappings.txt").getAbsolutePath)
      val cfg = c ++ o ++ libraryjars ++ printmappings :+ injars :+ outjars
      val ruleCache = s.cacheDirectory / "proguard-rules.hash"
      val cacheHash = Try(IO.read(ruleCache)).toOption getOrElse ""
      val rulesHash = Hash.toHex(Hash(cfg mkString "\n"))

      if (jars.exists( _.lastModified > t.lastModified ) || cacheHash != rulesHash) {
        cfg foreach (l => s.log.debug(l))
        val config = new PgConfig
        import java.util.Properties
        val parser: ConfigurationParser = new ConfigurationParser(
          cfg.toArray[String], new Properties)
        IO.write(s.cacheDirectory / "proguard-rules.hash", rulesHash)
        parser.parse(config)
        new ProGuard(config).execute()
      } else {
        s.log.info(t.getName + " is up-to-date")
      }
      inputs.proguardCache foreach {
        ProguardUtil.createCacheJar(t, _, pc, s.log)
      }
      Option(t)
    } else None
  }
}

object Dex {
  import ProguardUtil._
  def dexInputs(progOut: Option[File], in: ProguardInputs,
                pa: Aggregate.Proguard, ra: Aggregate.Retrolambda,
                multiDex: Boolean, b: File, deps: sbt.Keys.Classpath,
                classJar: File, debug: Boolean, s: sbt.Keys.TaskStreams) = {
    val re = ra.enable
    val bldr = ra.builder
    val progCache = pa.proguardCache
    val proguardRelease = pa.useProguard
    val proguardDebug = pa.useProguardInDebug

    val proguarding = (proguardDebug && debug) || (proguardRelease && !debug)
    // TODO use getIncremental in DexOptions instead
    val proguardedDexMarker = b / ".proguarded-dex"

    val jarsToDex = progOut map { obfuscatedJar =>
      IO.touch(proguardedDexMarker, setModified = false)
      Seq(obfuscatedJar)
    } getOrElse {
      proguardedDexMarker.delete()
      // TODO cache the jar file listing
      def dexingDeps = deps filter (_.data.isFile) filterNot (file =>
        progCache.nonEmpty && proguarding && (listjar(file) exists (inPackages(_, progCache))))
      val inputs = dexingDeps.collect {
        case x if x.data.getName.startsWith("scala-library") && (!proguarding || multiDex) =>
          x.data.getCanonicalFile
        case x if x.data.getName.endsWith(".jar") =>
          x.data.getCanonicalFile
      } ++ in.proguardCache :+ classJar
      // TODO may fail badly in the presence of proguard-cache?
      if (re && RetrolambdaSupport.isAvailable)
        RetrolambdaSupport(b, inputs, ra.classpath, bldr, s)
      else inputs
    }

    val incrementalDex = debug && (progCache.isEmpty || !proguardedDexMarker.exists)
    incrementalDex -> jarsToDex
  }
  def dex(bldr: AndroidBuilder, dexOpts: Aggregate.Dex, pd: Seq[(File,File)],
          pg: Option[File], classes: File, minSdk: String, lib: Boolean,
          bin: File, debug: Boolean, s: sbt.Keys.TaskStreams) = {
    import collection.JavaConverters._
    val xmx = dexOpts.maxHeap
    val (incr, inputs) = dexOpts.inputs
    val multiDex = dexOpts.multi
    val mainDexListTxt = dexOpts.mainFileClassesConfig
    val minMainDex = dexOpts.minimizeMainFile
    val additionalParams = dexOpts.additionalParams
    val incremental = incr && !multiDex
    val dexIn = (inputs filter (_.isFile)) filterNot (pd map (_._1) contains _)
    val dexes = (bin ** "*.dex").get
//    if (dexes.isEmpty || dexIn.exists(i => dexes exists(_.lastModified <= i.lastModified))) {
    FileFunction.cached(s.cacheDirectory / "dex", FilesInfo.lastModified) { in =>
      s.log.debug("Invalidated cache because: " + dexIn.filter(i => dexes.exists(_.lastModified <= i.lastModified)))
      if (!incremental && inputs.exists(_.getName.startsWith("proguard-cache"))) {
        s.log.debug("Cleaning dex files for proguard cache and incremental dex")
        (bin * "*.dex" get) foreach (_.delete())
      }
      val options = new DexOptions {
        override def getIncremental = incr

        override def getJavaMaxHeapSize = xmx

        override def getPreDexLibraries = false

        override def getJumboMode = false

        override def getThreadCount = java.lang.Runtime.getRuntime.availableProcessors()
      }
      s.log.info(s"Generating dex, incremental=$incremental, multidex=$multiDex")
      s.log.debug("Dex inputs: " + inputs)

      val tmp = s.cacheDirectory / "dex"
      tmp.mkdirs()

      def minimalMainDexParam = if (minMainDex) "--minimal-main-dex" else ""
      val additionalDexParams = (additionalParams.toList :+ minimalMainDexParam).distinct.filterNot(_.isEmpty)

      val predex2 = pd flatMap (_._2 * "*.dex" get)
      s.log.debug("DEX IN: " + dexIn)
      s.log.debug("PRE-DEXED: " + predex2)
      bldr.convertByteCode(dexIn.asJava, predex2.asJava, bin,
        multiDex, mainDexListTxt,
        options, additionalDexParams.asJava, tmp, incremental, !debug)
      s.log.info("dex method count: " + ((bin * "*.dex" get) map (dexMethodCount(_, s.log))).sum)
      (bin ** "*.dex").get.toSet
    }(dexIn.toSet)

    bin
  }

  def predexFileOutput(binPath: File, inFile: File) = {
    val n = inFile.getName
    val pos = n.lastIndexOf('.')

    val name = if (pos != -1) n.substring(0, pos) else n

    // add a hash of the original file path.
    val input = inFile.getAbsolutePath
    val hashFunction = Hashing.sha1
    val hashCode = hashFunction.hashString(input, Charsets.UTF_16LE)

    val f = new File(binPath / "predex-libraries", name + "-" + hashCode.toString + SdkConstants.DOT_JAR)
    f.mkdirs()
    f
  }

  def predex(opts: Aggregate.Dex, inputs: Seq[File], multiDex: Boolean,
             minSdk: String, classes: File, pg: Option[File],
             bldr: AndroidBuilder, bin: File, s: sbt.Keys.TaskStreams) = {
    val minLevel = Try(minSdk.toInt).toOption getOrElse
      SdkVersionInfo.getApiByBuildCode(minSdk, true)
    val options = new DexOptions {
      override def getIncremental = false
      override def getJavaMaxHeapSize = opts.maxHeap
      override def getPreDexLibraries = false
      override def getJumboMode = false
      override def getThreadCount = java.lang.Runtime.getRuntime.availableProcessors()
    }
    if (minLevel >= 21 && multiDex) {
      inputs filterNot (i => i == classes || pg.exists(_ == i)) map { i =>
        val out = predexFileOutput(bin, i)
        val predexed = out * "*.dex" get

        if (predexed.isEmpty || predexed.exists (_.lastModified < i.lastModified)) {
          s.log.info("Pre-dexing: " + i.getName)
          bldr.preDexLibrary(i, out, multiDex, options)
        }
        (i,out)
      }
    } else Nil
  }

  // see https://source.android.com/devices/tech/dalvik/dex-format.html
  def dexMethodCount(dexFile: File, log: Logger): Int = {
    import java.nio.{ByteBuffer,ByteOrder}
    val header_size = 0x70
    val endian_constant = 0x12345678
    val reverse_endian_constant = 0x78563412
    val dex_magic = Array(0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x35, 0x00) map (_.toByte)
    val buf = Array.ofDim[Byte](header_size)
    Using.fileInputStream(dexFile) { fin =>
      fin.read(buf)
      val header = ByteBuffer.wrap(buf)
      val isDex = (dex_magic zip buf) forall { case (a, b) => a == b }
      if (isDex) {
        header.order(ByteOrder.LITTLE_ENDIAN)
        header.position(40) // endian_tag
        val endianness = header.getInt
        val isBE = endianness == reverse_endian_constant
        if (isBE)
          header.order(ByteOrder.BIG_ENDIAN)
        else if (endianness != endian_constant) {
          log.warn(dexFile.getName + " does not define endianness properly")
        }

        header.position(88) // method_ids_size
        val methodIds = header.getInt
        methodIds
      } else {
        log.info(dexFile.getName + " is not a valid dex file")
        0
      }
    }
  }
}
