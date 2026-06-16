ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.14"

val chiselVersion = "7.0.0-M2"

lazy val root = (project in file("./chisel"))
  .settings(
    name := "scpu",
    Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "../zcu102-runtime/chisel/src/main/scala",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "edu.berkeley.cs" %% "chiseltest" % "0.6.2" % "test"
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-Ymacro-annotations",
      "-Ytasty-reader",
      "-deprecation",
      "-feature",
      "-Xcheckinit"
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full)
  )
