lazy val root = (project in file("."))
  .settings(
    scalaVersion := "2.13.4",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "2.3.1",
      "org.typelevel" %% "cats-effect" % "3.0.0-M5",
    ),
  )
