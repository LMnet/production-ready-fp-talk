lazy val root = (project in file("."))
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "2.3.1",
      "org.typelevel" %% "cats-effect" % "2.3.1",
    ),
    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
    scalacOptions ++= Seq(
      "-language:higherKinds",
    ),
  )
