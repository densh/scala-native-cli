package scala.scalanative.cli

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
            case Some(Action.Get(org, repo)) =>
              println(s"Installing $org/$repo!")
            case None =>
              Console.err.println("Missing command.")
              parser.showUsageAsError()
          }
        }
      case _ =>
    }
  }
}
