ThisBuild / version := "0.2.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.14"

val chiselVersion = "7.0.0-M2"

lazy val root = (project in file("./chisel"))
  .settings(
    name := "scpu",
    Compile / scalaSource := baseDirectory.value / "rv-core/main/scala",
    Compile / resourceDirectory := baseDirectory.value / "rv-core/main/resources",
    Test / scalaSource := baseDirectory.value / "rv-core/test/scala",
    Test / resourceDirectory := baseDirectory.value / "rv-core/test/resources",
    Compile / unmanagedSourceDirectories ++= Seq(
      baseDirectory.value / "configs/parameters",
      baseDirectory.value / "configs/npc",
      (ThisBuild / baseDirectory).value / "chisel/ysyxSoC/rocket-chip/dependencies/hardfloat/hardfloat/src/main/scala"
    ),
    Compile / unmanagedResourceDirectories += baseDirectory.value / "configs/resources",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
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

// FPGA 是可选平台组件。普通 root 构建只带仿真/DPI 组件；板级生成显式运行 fpga 子项目。
lazy val fpga = (project in file("./chisel-fpga"))
  .dependsOn(root)
  .settings(
    name := "scpu-fpga",
    Compile / unmanagedSourceDirectories ++= Seq(
      (ThisBuild / baseDirectory).value / "chisel/configs/fpga",
      (ThisBuild / baseDirectory).value / "chisel/fpga-harness/src/common",
      (ThisBuild / baseDirectory).value / "chisel/fpga-harness/src/rv-core",
      (ThisBuild / baseDirectory).value / "chisel/ysyxSoC/rocket-chip/dependencies/cde/cde/src"
    ),
    Compile / unmanagedSources / excludeFilter :=
      new sbt.io.SimpleFileFilter(_.getName.endsWith("SocConfig.scala")),
    Test / unmanagedSourceDirectories +=
      (ThisBuild / baseDirectory).value / "chisel/fpga-harness/test",
    libraryDependencies +=
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
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
