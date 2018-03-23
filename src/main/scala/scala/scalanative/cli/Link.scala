package scala.scalanative.cli

import java.nio.file.{Path, Paths}
import scalanative.build.{Build, Config, Discover, GC, Mode}

object Link {
  def link(classpath: Seq[Path], workdir: Path, main: String): Path = {
    val clang     = Discover.clang()
    val clangpp   = Discover.clangpp()
    val linkopts  = Discover.linkingOptions()
    val compopts  = Discover.compileOptions()
    val triple    = Discover.targetTriple(clang, workdir)
    val nativelib = Discover.nativelib(classpath).get
    val outpath   = workdir.resolve("out")
    val config =
      Config.empty
        .withGC(GC.default)
        .withMode(Mode.default)
        .withClang(clang)
        .withClangpp(clangpp)
        .withLinkingOptions(linkopts)
        .withCompileOptions(compopts)
        .withTargetTriple(triple)
        .withNativelib(nativelib)
        .withEntry(main)
        .withClasspath(classpath)
        .withLinkStubs(true)
        .withWorkdir(workdir)

    Build.build(config, outpath)

    outpath
  }
}
