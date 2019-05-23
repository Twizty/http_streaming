name := "http_streaming"

version := "0.1"

scalaVersion := "2.12.7"

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.8")

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-core" % "1.6.0",
  "org.typelevel" %% "cats-effect" % "1.3.0",
  "org.tpolecat" %% "doobie-core"      % "0.6.0",
  "org.tpolecat" %% "doobie-postgres"  % "0.6.0",
  "com.zaxxer" % "HikariCP" % "3.3.1",
  "org.tpolecat" %% "doobie-hikari"    % "0.6.0",
  "co.fs2" %% "fs2-core" % "1.0.4",
  "co.fs2" %% "fs2-io" % "1.0.4",
  "com.typesafe" % "config" % "1.3.4",
  "io.chrisdavenport" %% "log4cats-core"    % "0.3.0-M2",
  "io.chrisdavenport" %% "log4cats-slf4j"   % "0.3.0-M2",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "org.codehaus.janino" % "janino" % "3.0.6",
  "net.logstash.logback" % "logstash-logback-encoder" % "4.11",
  "org.http4s"     %% "http4s-blaze-server" % "0.19.0",
  "org.http4s" %% "http4s-blaze-client" % "0.19.0",
  "org.http4s"     %% "http4s-circe"        % "0.19.0",
  "org.http4s"     %% "http4s-dsl"          % "0.19.0",
  "com.typesafe" % "config" % "1.3.2",
)