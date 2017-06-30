package scala.scalanative.cli

import sbt.io._
import sbt.io.syntax._
import java.io.File
import scala.util.Try
import scala.sys.process.Process
import scalanative.nir
import scalanative.tools

sealed abstract class GarbageCollector(val name: String,
                                       val links: Seq[String] = Nil)
object GarbageCollector {
  object None  extends GarbageCollector("none")
  object Boehm extends GarbageCollector("boehm", Seq("gc"))
  object Immix extends GarbageCollector("immix")
}

private class Link(classpath: Seq[File], workdir: File, main: String) {

  implicit class RichFile(file: File) {
    def abs: String =
      file.getAbsolutePath
  }

  def discover(binaryName: String,
               binaryVersions: Seq[(String, String)]): File = {
    val docSetup =
      "http://www.scala-native.org/en/latest/user/setup.html"

    val envName =
      if (binaryName == "clang") "CLANG"
      else if (binaryName == "clang++") "CLANGPP"
      else binaryName

    sys.env.get(s"${envName}_PATH") match {
      case Some(path) => file(path)
      case None => {
        val binaryNames = binaryVersions.flatMap {
          case (major, minor) =>
            Seq(s"$binaryName$major$minor", s"$binaryName-$major.$minor")
        } :+ binaryName

        Process("which" +: binaryNames).lines_!
          .map(file(_))
          .headOption
          .getOrElse {
            throw new Exception(
              s"no ${binaryNames.mkString(", ")} found in $$PATH. Install clang ($docSetup)")
          }
      }
    }
  }

  val clangVersions =
    Seq(("4", "0"), ("3", "9"), ("3", "8"), ("3", "7"))

  lazy val clang: File = {
    val clang = discover("clang", clangVersions)
    clang
  }

  lazy val clangpp: File = {
    val clang = discover("clang++", clangVersions)
    clang
  }

  lazy val gc: GarbageCollector = GarbageCollector.Immix

  lazy val mode: tools.Mode = tools.Mode.Release

  lazy val compileOptions: Seq[String] = {
    val includes = {
      val includedir =
        Try(Process(Seq("llvm-config", "--includedir")).lines_!)
          .getOrElse(Seq.empty)
      ("/usr/local/include" +: includedir).map(s => s"-I$s")
    }
    includes :+ "-Qunused-arguments"
  }

  lazy val linkingOptions: Seq[String] = {
    val libs = {
      val libdir =
        Try(Process(Seq("llvm-config", "--libdir")).lines_!)
          .getOrElse(Seq.empty)
      ("/usr/local/lib" +: libdir).map(s => s"-L$s")
    }
    libs
  }

  lazy val target = {
    // Use non-standard extension to not include the ll file when linking (#639)
    val targetc  = workdir / "target" / "c.probe"
    val targetll = workdir / "target" / "ll.probe"
    val compilec =
      Seq(clang.abs,
          "-S",
          "-xc",
          "-emit-llvm",
          "-o",
          targetll.abs,
          targetc.abs)
    def fail =
      throw new Exception("Failed to detect native target.")

    IO.write(targetc, "int probe;")
    val exit = Process(compilec, workdir).!
    if (exit != 0) fail
    IO.readLines(targetll)
      .collectFirst {
        case line if line.startsWith("target triple") =>
          line.split("\"").apply(1)
      }
      .getOrElse(fail)
  }

  lazy val outfile: File = workdir / "out"

  lazy val config: tools.Config =
    tools.Config.empty
      .withEntry(nir.Global.Top(main + "$"))
      .withPaths(classpath)
      .withWorkdir(workdir)
      .withTarget(target)
      .withMode(mode)

  lazy val linkerReporter: tools.LinkerReporter =
    tools.LinkerReporter.empty

  lazy val optimizerDriver: tools.OptimizerDriver =
    tools.OptimizerDriver(config)

  lazy val optimizerReporter: tools.OptimizerReporter =
    tools.OptimizerReporter.empty

  lazy val linkNIR: tools.LinkerResult = {
    val result =
      tools.link(config, optimizerDriver, linkerReporter)
    if (result.unresolved.nonEmpty) {
      val sigs = result.unresolved.map(_.show).sorted.mkString(", ")
      throw new Exception("unable to link: " + sigs)
    }
    result
  }

  lazy val optimizeNIR: Seq[nir.Defn] =
    tools.optimize(config, optimizerDriver, linkNIR.defns,
                   linkNIR.dyns, optimizerReporter)

  lazy val generateLL: Seq[File] = {
    tools.codegen(config, optimizeNIR)
    (workdir ** "*.ll").get.toSeq
  }

  lazy val compileLL: Seq[File] =
    generateLL.par
      .map { ll =>
        val apppath = ll.abs
        val outpath = apppath + ".o"
        val compile = Seq(clangpp.abs, "-c", apppath, "-o", outpath) ++ compileOptions
        Process(compile, workdir).!
        file(outpath)
      }
      .seq
      .toSeq

  lazy val unpackLib: File = {
    val lib = workdir / "lib"
    val jar =
      classpath
        .map(_.abs)
        .collectFirst {
          case p if p.contains("scala-native") && p.contains("nativelib") =>
            file(p)
        }
        .get
    val jarhash     = Hash(jar).toSeq
    val jarhashfile = lib / "jarhash"
    def unpacked =
      lib.exists &&
        jarhashfile.exists &&
        jarhash == IO.readBytes(jarhashfile).toSeq

    if (!unpacked) {
      IO.delete(lib)
      IO.unzip(jar, lib)
      IO.write(jarhashfile, Hash(jar))
    }

    lib
  }

  lazy val compileLib: File = {
    val linked    = linkNIR
    val opts      = compileOptions ++ Seq("-O2")
    val nativelib = unpackLib
    val cpaths    = (workdir ** "*.c").get.map(_.abs)
    val cpppaths  = (workdir ** "*.cpp").get.map(_.abs)
    val paths     = cpaths ++ cpppaths

    // predicate to check if given file path shall be compiled
    // we only include sources of the current gc and exclude
    // all optional dependencies if they are not necessary
    def include(path: String) = {
      val sep = File.separator

      if (path.contains(sep + "optional" + sep)) {
        val name = file(path).getName.split("\\.").head
        linked.links.map(_.name).contains(name)
      } else if (path.contains(sep + "gc" + sep)) {
        path.contains("gc" + sep + gc)
      } else {
        true
      }
    }

    // delete .o files for all excluded source files
    paths.foreach { path =>
      if (!include(path)) {
        val ofile = file(path + ".o")
        if (ofile.exists) {
          IO.delete(ofile)
        }
      }
    }

    // generate .o files for all included source files in parallel
    paths.par.foreach {
      path =>
        val opath = path + ".o"
        if (include(path) && !file(opath).exists) {
          val isCpp    = path.endsWith(".cpp")
          val compiler = if (isCpp) clangpp.abs else clang.abs
          val flags    = (if (isCpp) Seq("-std=c++11") else Seq()) ++ opts
          val compilec = Seq(compiler) ++ flags ++ Seq("-c",
                                                       path,
                                                       "-o",
                                                       opath)

          val result = Process(compilec, workdir).!
          if (result != 0) {
            sys.error("Failed to compile native library runtime code.")
          }
        }
    }

    nativelib
  }

  lazy val linkLL: File = {
    val linked      = linkNIR
    val apppaths    = compileLL
    val nativelib   = compileLib
    val linkingOpts = linkingOptions

    val links = {
      val os   = Option(sys props "os.name").getOrElse("")
      val arch = target.split("-").head
      // we need re2 to link the re2 c wrapper (cre2.h)
      val librt = os match {
        case "Linux" => Seq("rt")
        case _       => Seq.empty
      }
      val libunwind = os match {
        case "Mac OS X" => Seq.empty
        case _          => Seq("unwind", "unwind-" + arch)
      }
      librt ++ libunwind ++ linked.links
        .map(_.name) ++ gc.links
    }
    val linkopts  = links.map("-l" + _) ++ linkingOpts
    val targetopt = Seq("-target", target)
    val flags     = Seq("-o", outfile.abs) ++ linkopts ++ targetopt
    val opaths    = (nativelib ** "*.o").get.map(_.abs)
    val paths     = apppaths.map(_.abs) ++ opaths
    val compile   = clangpp.abs +: (flags ++ paths)

    Process(compile, workdir).!

    outfile
  }

  lazy val link: File = {
    linkNIR
    optimizeNIR
    generateLL
    compileLL
    compileLib
    linkLL

    outfile
  }
}

object Link {
  def link(classpath: Seq[File], workdir: File, main: String): File =
    (new Link(classpath, workdir, main)).link
}
