package de.tobiasroeser.mill.jbake

import scala.util.{Success, Try}

import mill._
import mill.api.{Ctx, IO}
import mill.define.{Command, Sources, TaskModule, Worker}
import os.{Path, Shellable}

trait JBakeModule extends Module with TaskModule {

  import JBakeModule._

  override def defaultCommandName(): String = "jbake"

  /**
   * The JBake version to be used.
   *
   * It will be used to automatically download the JBake distribution.
   */
  def jbakeVersion: T[String]

  /**
   * The JBake Binary Distribution ZIP archive.
   *
   * Defaults to downloading the distribution file from Bintray.
   */
  def jbakeDistributionZip: T[PathRef] = T.persistent {
    val version = jbakeVersion()
    val url = s"https://github.com/jbake-org/jbake/releases/download/v${version}/jbake-${version}-bin.zip"
    val target = T.ctx().dest / s"jbake-${version}-bin.zip"
    if (!os.exists(target)) {
      T.ctx().log.debug(s"Downloading ${url}")
      val tmpfile = os.temp(dir = T.ctx().dest, deleteOnExit = false)
      os.remove(tmpfile)
      mill.modules.Util.download(url, os.rel / tmpfile.last)
      os.move(tmpfile, target)
    }
    PathRef(target)
  }

  /**
   * The unpacked JBake Distribution.
   *
   * Defaults to the unpacked content of the [[jbakeDistributionZip]].
   */
  def jbakeDistributionDir: T[PathRef] = T {
    T.ctx().log.debug(s"Unpacking ${jbakeDistributionZip().path}")
    parseVersion(jbakeVersion()) match {
      case Success(Array(2, 0 | 1 | 2 | 3 | 4 | 5, _)) =>
        PathRef(IO.unpackZip(jbakeDistributionZip().path).path / s"jbake-${jbakeVersion()}")
      case _ =>
        PathRef(IO.unpackZip(jbakeDistributionZip().path).path / s"jbake-${jbakeVersion()}-bin")
    }
  }

  /** Extract the major, minor and micro version parts of the given version string. */
  private def parseVersion(version: String): Try[Array[Int]] = Try {
    version
      .split("[-]", 2)(0)
      .split("[.]", 4)
      .take(3)
      .map(_.toInt)
  }

  /**
   * The classpath used to run the JBake site generator.
   */
  def jbakeClasspath: T[Seq[PathRef]] = T {
    parseVersion(jbakeVersion()) match {
      case Success(Array(2, 0 | 1 | 2 | 3 | 4 | 5, _)) =>
        (Seq(jbakeDistributionDir().path / "jbake-core.jar") ++
          os.list(jbakeDistributionDir().path / 'lib)).map(PathRef(_))
      case _ =>
        os.list(jbakeDistributionDir().path / 'lib).map(PathRef(_))
    }
  }

  /**
   * The directory containing the JBake source files (`assets`, `content`, `templates`).
   *
   * Defaults to `src`.
   */
  def sources: Sources = T.sources {
    millSourcePath / 'src
  }

  /**
   * Bake the site with JBake.
   */
  def jbake: T[PathRef] = T {
    val targetDir = T.ctx().dest
    jbakeWorker().runJbakeMain(targetDir, sources().head.path, targetDir)
    PathRef(targetDir)
  }

  /**
   * Starts a local Webserver to serve the content created with [[jbake]].
   *
   * FIXME: This doesn't work for JBake versions since 2.6.2.
   */
  def jbakeServe(): Command[Unit] = T.command {
    jbakeWorker().runJbakeMain(T.ctx().dest, "-s", jbake().path)
  }

  /**
   * Initialized the sources for a new project.
   */
  def jbakeInit() = T.command {
    val src = sources().head.path
    if (os.exists(src) && !os.walk(src).isEmpty) {
      throw new RuntimeException(s"Source directory ${src} is not empty. Aborting initialization of fresh JBake project")
    } else {
      os.makeDir.all(src)
      jbakeWorker().runJbakeMain(T.ctx().dest, "-i", src)
      //      val baseZip = ???
      //      IO.unpackZip(baseZip, )
    }
  }

  /**
   * The worker encapsulates the process runner of the JBake tool.
   */
  def jbakeWorker: Worker[JBakeWorker] = T.worker {
    jbakeProcessMode match {
      case SubProcess =>
        T.ctx().log.debug("Creating SubProcess JBakeWorker")
        new JBakeWorkerSubProcessImpl(jbakeClasspath().map(_.path))
      case ClassLoader =>
        T.ctx().log.debug("Creating ClassLoader JBakeWorker")
        new JBakeWorkerClassloaderImpl(jbakeClasspath().map(_.path))
    }
  }

  /**
   * Just calls the jbake tool with the given arguments.
   */
  def jbakeRun(args: String*): Command[Unit] = T.command {
    jbakeWorker().runJbakeMain(T.ctx().home, args)
  }

  /**
   * Specify how the JBake tool should be executed.
   */
  def jbakeProcessMode: ProcessMode = ClassLoader

}

object JBakeModule {

  /** Mode how the JBake tool should be executed. */
  sealed trait ProcessMode

  /** Execute JBake as sub process. */
  final case object SubProcess extends ProcessMode

  /** Execute JBake as Java Library in a separate ClassLoader. EXPERIMENTAL! */
  final case object ClassLoader extends ProcessMode

}

