val scala3Version = "3.3.0"

lazy val root = project
  .in(file("."))
  .settings(
    name := "http-zio",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.0.16",
      "dev.zio" %% "zio-json" % "0.6.1",
      "dev.zio" %% "zio-http" % "3.0.0-RC2",
      "io.getquill" %% "quill-zio" % "4.6.0",
      "io.getquill" %% "quill-jdbc-zio" % "4.6.0",
      "com.h2database" % "h2" % "2.2.222",
      "org.scalameta" %% "munit" % "0.7.29" % Test,
    )
  )
