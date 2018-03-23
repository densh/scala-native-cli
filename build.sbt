scalaVersion := "2.10.6"

libraryDependencies ++= List(
  "org.scala-native" %% "tools" % "0.3.7-SNAPSHOT",
  "com.github.scopt" %% "scopt" % "3.6.0",
  "io.get-coursier" %% "coursier" % "1.0.0-RC6",
  "io.get-coursier" %% "coursier-cache" % "1.0.0-RC6",
  "org.scala-sbt" %% "io" % "1.0.0-M11"
)
