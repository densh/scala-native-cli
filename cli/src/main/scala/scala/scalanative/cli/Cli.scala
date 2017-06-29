package scala.scalanative.cli

import java.io.ByteArrayInputStream
import java.io.File
import java.util.Properties
import scala.io.Source
import scopt._

sealed abstract class Action
object Action {
  case class Get(org: String, repo: String) extends Action
  object Get {
    private val OrgRepo = "([^/]*)/(.*)".r
    def apply(arg: String): Either[String, Get] = arg match {
      case OrgRepo(org, repo) => Right(Get(org, repo))
      case els                => Left(s"Invalid repo '$els'. Expected format 'org/repo'.")
    }
  }
  case object Help extends Action
  case object Version extends Action
}

case class CliConfig(
    action: Option[Action] = None,
    errors: List[String] = Nil
) {
  def withError(msg: String): CliConfig = copy(errors = msg :: errors)
}

case class BinaryProperties(
    name: String,
    org: String,
    artifact: String,
    version: String,
    main: String,
    nativeVersion: String,
    scalaVersion: String
) {
  private def binaryVersion(version: String): String =
    version.split("\\.").take(2).mkString(".")
  def mavenArtifact =
    s"${artifact}_native${binaryVersion(nativeVersion)}_${binaryVersion(scalaVersion)}"
}

object Cli {
  val version = "0.1"
  val parser = new OptionParser[CliConfig]("scala-native") {
    head("scala-native", Cli.version)
    cmd("get")
      .text("Install a command line tool.")
      .children(
        arg[String]("repo")
          .unbounded()
          .minOccurs(1)
          .maxOccurs(1)
          .text("The GitHub org/repo to install.")
          .action((repo, c) =>
            Action.Get(repo) match {
              case Right(get) => c.copy(action = Some(get))
              case Left(err)  => c.withError(s"[get] $err")
          })
      )
    cmd("help")
      .text("Print this help message.")
      .action((_, c) => c.copy(action = Some(Action.Help)))
    cmd("version")
      .text("Print version.")
      .action((_, c) => c.copy(action = Some(Action.Help)))
  }

  def fetchProperties(get: Action.Get): BinaryProperties = {
    val url =
      s"https://raw.githubusercontent.com/${get.org}/${get.repo}/master/.scalanative"
    val propsString = Source.fromURL(url).mkString
    val props = new Properties()
    props.load(new ByteArrayInputStream(propsString.getBytes))
    def getProperty(id: String): String = {
      val value = props.getProperty(id)
      if (value.eq(null)) {
        sys.error(s"missing key '$id' in $url!")
      } else {
        value
      }
    }
    BinaryProperties(
      name = getProperty("name"),
      org = getProperty("org"),
      artifact = getProperty("artifact"),
      version = getProperty("version"),
      main = getProperty("main"),
      nativeVersion = getProperty("nativeVersion"),
      scalaVersion = getProperty("scalaVersion")
    )
  }

  def fetchJars(props: BinaryProperties): List[File] = {
    import coursier._
    val start = Resolution(
      Set(
        Dependency(Module(props.org, props.mavenArtifact), props.version)
      ))
    val repositories = Seq(
      Cache.ivy2Local,
      MavenRepository("https://repo1.maven.org/maven2")
    )
    val fetch = Fetch.from(repositories, Cache.fetch())
    val resolution = start.process.run(fetch).unsafePerformSync
    val errors: Seq[(Dependency, Seq[String])] = resolution.errors
    if (errors.nonEmpty) {
      sys.error(errors.mkString("\n"))
    }
    val localArtifacts = scalaz.concurrent.Task
      .gatherUnordered(
        resolution.artifacts.map(Cache.file(_).run)
      )
      .unsafePerformSync
      .map(_.toEither)
    val failures = localArtifacts.collect { case Left(e) => e }
    if (failures.nonEmpty) {
      sys.error(failures.mkString("\n"))
    } else {
      val jars = localArtifacts.collect {
        case Right(file) if file.getName.endsWith(".jar") =>
          file
      }
      jars
    }
  }

  def run(get: Action.Get): Unit = {
    val props = fetchProperties(get)
    val jars = fetchJars(props)
    println(s"Fetched jars:\n  ${jars.mkString("\n  ")}")
  }

  def main(args: Array[String]): Unit = {
    parser.parse(args, CliConfig()) match {
      case Some(config) =>
        if (config.errors.nonEmpty) {
          config.errors.foreach(Console.err.println)
          parser.showUsageAsError()
        } else {
          config.action match {
            case Some(Action.Version) => println(Cli.version)
            case Some(Action.Help)    => println(parser.usage)
            case Some(get @ Action.Get(org, repo)) =>
              println(s"Installing $org/$repo...")
              run(get)
            case None =>
              Console.err.println("Missing command.")
              parser.showUsageAsError()
          }
        }
      case _ =>
    }
  }
}
