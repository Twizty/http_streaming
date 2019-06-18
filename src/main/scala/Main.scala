import java.io.{File, StringReader}
import java.sql.DriverManager

import cats.data.OptionT
import cats.effect._
import cats.implicits._
import com.typesafe.config.ConfigFactory
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.postgres.{PFCM, PHC}
import doobie.postgres.free.Embedded.CopyManager
import fs2.Stream
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.GZip
import org.postgresql.core.BaseConnection

import scala.concurrent.duration._

object Main extends IOApp {
  type RequestEff[A] = OptionT[IO, A]

  private val config = ConfigFactory.load()
  private val postgresUrlWithoutDbName = config.getString("postgres.url")
  private val postgresUrlParams = config.getString("postgres.url_params")
  private val username = config.getString("postgres.username")
  private val password = config.getString("postgres.password")

  private val port = config.getInt("app.port")
  private val host = config.getString("app.host")
  private val batchSize = config.getInt("app.batch_size")

  def startPool(dbName: String,
                threadsCount: Int = 4): Resource[IO, HikariTransactor[IO]] =
    for {
      ce <- ExecutionContexts.fixedThreadPool[IO](threadsCount) // our connect EC
      te <- ExecutionContexts.cachedThreadPool[IO] // our transaction EC
      xa <- HikariTransactor.newHikariTransactor[IO](
        "org.postgresql.Driver",
        s"$postgresUrlWithoutDbName$dbName?$postgresUrlParams",
        username,
        password,
        ce,
        te
      )
    } yield xa

  override def run(args: List[String]): IO[ExitCode] = {
    val fooPool = startPool("foo")
    val barPool = startPool("bar")

    Slf4jLogger.create[IO].flatMap { logger =>
      prepareDb >>
        fooPool
          .use { fooTransactor =>
            barPool
              .use { barTransactor =>
                val routes = HttpRoutes.of[IO] {
                  case GET -> Root / "dbs" / "foo" / "tables" / "source" =>
                    Ok(streamData("source", fooTransactor))
                  case GET -> Root / "dbs" / "bar" / "tables" / "dest" =>
                    Ok(streamData("dest", barTransactor))
                }

                logger.info("preparing database...") >>
                  prepareTables(fooTransactor, barTransactor) >>
                  logger.info("database prepared, web server started") >>
                  BlazeServerBuilder[IO]
                    .withIdleTimeout(10.minutes)
                    .bindHttp(port, host)
                    .withHttpApp(GZip[RequestEff, IO](routes).orNotFound)
                    .serve
                    .compile
                    .drain
              }
              .as(ExitCode.Success)
          }
    }
  }

  def streamData(tableName: String,
                 transactor: Transactor[IO]): Stream[IO, String] = {
    val query = sql"select * from " ++ Fragment.const(tableName)

    query
      .query[(Int, Int, Int)]
      .stream
      .transact(transactor)
      .map { case (a, b, c) => s"$a,$b,$c\n" }
      .chunkN(batchSize, true) // I tried to find how to make chunk bigger by configuring Blaze Server,
      .map(_.mkString_("")) // but it just makes a chunk from each element of the stream
  }

  def prepareDb: IO[Unit] = {
    val res = Resource.make(IO {
      Class.forName("org.postgresql.Driver")

      DriverManager.getConnection(
        s"${postgresUrlWithoutDbName}postgres?$postgresUrlParams",
        username,
        password)
    })(conn => IO { conn.close() })

    res.use { conn =>
      IO {
        val stat = conn.createStatement()
        stat.execute(
          "drop database if exists foo;\ncreate database foo;\ndrop database if exists bar;\ncreate database bar;")
        conn.close()
      }
    }
  }

  def prepareTables(fooTransactor: Transactor[IO],
                    barTransactor: Transactor[IO]): IO[Unit] = {
    val createFooTable =
      sql"create table if not exists source (a int, b int, c int);".update.run
    val truncateFooTable = sql"truncate table source;".update.run

    val createBarTable =
      sql"create table if not exists dest (a int, b int, c int);".update.run
    val truncateBarTable = sql"truncate table dest;".update.run

    val insertsFooTable =
      sql"""insert into source (a, b, c)
            select generate_series, generate_series % 3, generate_series % 5
            from generate_series(1, 1000000);""".update.run

    val prepareFoo =
      (createFooTable >> truncateFooTable >> insertsFooTable)
        .transact(fooTransactor)
    val prepareBar =
      (createBarTable >> truncateBarTable).transact(barTransactor)

    val output = new java.io.ByteArrayOutputStream(64 * 1024)

    val copyOut = PHC
      .pgGetCopyAPI(
        PFCM.copyOut("COPY source TO STDOUT DELIMITER ',' CSV HEADER", output))
      .transact(fooTransactor)

    for {
      _ <- prepareFoo
      _ <- prepareBar
      _ <- copyOut
      input = new java.io.ByteArrayInputStream(output.toByteArray)
      copyIn = PHC.pgGetCopyAPI(
        PFCM.copyIn("COPY dest FROM STDIN DELIMITER ',' CSV HEADER", input))
      _ <- copyIn.transact(barTransactor)
    } yield ()
  }
}
