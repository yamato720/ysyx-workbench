ThisBuild / version := "0.2.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.14"

val chiselVersion = "7.0.0-M2"

def chiselSettings = Seq(
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

lazy val ip = (project in file("./chisel/ip-interface"))
  .settings(
    Compile / scalaSource := baseDirectory.value / "scala",
    Compile / resourceDirectory := baseDirectory.value / "resources",
    Test / scalaSource := baseDirectory.value / "test"
  )
  .settings(chiselSettings)
  .settings(name := "npc-ip")

lazy val root = (project in file("./chisel"))
  .dependsOn(ip)
  .settings(
    name := "npc",
    Compile / scalaSource := baseDirectory.value / "rv-core/main/scala",
    Compile / resourceDirectory := baseDirectory.value / "rv-core/main/resources",
    Test / scalaSource := baseDirectory.value / "rv-core/test/scala",
    Test / resourceDirectory := baseDirectory.value / "rv-core/test/resources",
    Compile / unmanagedSourceDirectories ++= Seq(
      baseDirectory.value / "accelerators/spmv/main/scala",
      baseDirectory.value / "configs/parameters",
      baseDirectory.value / "configs/common",
      baseDirectory.value / "configs/nemu",
      baseDirectory.value / "configs/npc",
      baseDirectory.value / "configs/spmv",
      baseDirectory.value / "ysyxSoC/rocket-chip/dependencies/cde/cde/src",
      (ThisBuild / baseDirectory).value / "chisel/ysyxSoC/rocket-chip/dependencies/hardfloat/hardfloat/src/main/scala"
    ),
    Compile / unmanagedResourceDirectories += baseDirectory.value / "configs/resources",
    Test / unmanagedSourceDirectories += baseDirectory.value / "accelerators/spmv/test/scala"
  )
  .settings(chiselSettings)

// FPGA 终端与 ysyxSoC 共用完整的 CDE 图，因此统一由 ysyxSoC 的 Mill 编译边界构造。
// 普通 root 构建仍只带仿真/DPI 组件，不会引入 Rocket、Diplomacy 或板卡逻辑。
