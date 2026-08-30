ThisBuild / organization := "dev.sprout"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.6"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Wunused:all")
ThisBuild / Test / fork := true

lazy val catsEffect = "org.typelevel" %% "cats-effect" % "3.6.3"
lazy val munit = "org.scalameta" %% "munit" % "1.1.1" % Test

lazy val core = project
  .in(file("modules/core"))
  .settings(name := "sprout-core", libraryDependencies ++= Seq(catsEffect, munit))

lazy val config = project
  .in(file("modules/config"))
  .dependsOn(core)
  .settings(
    name := "sprout-config",
    libraryDependencies ++= Seq("org.tomlj" % "tomlj" % "1.1.1", munit)
  )

lazy val dependencies = project
  .in(file("modules/dependencies"))
  .dependsOn(core)
  .settings(
    name := "sprout-dependencies",
    libraryDependencies ++= Seq(
      "io.get-coursier" % "coursier_2.13" % "2.1.25-M26",
      catsEffect,
      munit
    )
  )

lazy val compiler = project
  .in(file("modules/compiler"))
  .dependsOn(core)
  .settings(name := "sprout-compiler", libraryDependencies ++= Seq(catsEffect, munit))

lazy val runner = project
  .in(file("modules/runner"))
  .dependsOn(core, compiler)
  .settings(
    name := "sprout-runner",
    libraryDependencies ++= Seq("org.scala-sbt" % "test-interface" % "1.0", catsEffect, munit)
  )

lazy val bsp = project
  .in(file("modules/bsp"))
  .dependsOn(core, config, dependencies, compiler)
  .settings(
    name := "sprout-bsp",
    libraryDependencies ++= Seq("ch.epfl.scala" % "bsp4j" % "2.1.1", catsEffect, munit)
  )

lazy val cli = project
  .in(file("modules/cli"))
  .dependsOn(core, config, dependencies, compiler, runner, bsp)
  .settings(
    name := "sprout-cli",
    libraryDependencies ++= Seq(catsEffect, munit),
    Compile / mainClass := Some("sprout.cli.Main"),
    Compile / packageBin / mainClass := Some("sprout.cli.Main"),
    Compile / packageBin / packageOptions += Package.ManifestAttributes(
      "Implementation-Version" -> version.value
    ),
    assembly / assemblyJarName := "sprout.jar",
    assembly / assemblyOutputPath := (ThisBuild / baseDirectory).value / "target" / "sprout.jar",
    assembly / assemblyMergeStrategy := {
      val defaultStrategy: String => sbtassembly.MergeStrategy =
        (assembly / assemblyMergeStrategy).value
      (path: String) =>
        if (path.endsWith("module-info.class")) MergeStrategy.discard
        else defaultStrategy(path)
    }
  )

lazy val root = project
  .in(file("."))
  .aggregate(core, config, dependencies, compiler, runner, bsp, cli)
  .settings(name := "sprout", publish / skip := true)

addCommandAlias("check", ";scalafmtCheckAll;test")
