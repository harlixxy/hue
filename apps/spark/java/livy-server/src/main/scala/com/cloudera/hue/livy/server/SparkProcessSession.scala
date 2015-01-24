package com.cloudera.hue.livy.server

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.io.Source

object SparkProcessSession {
  val LIVY_HOME = System.getenv("LIVY_HOME")
  val SPARK_SHELL = LIVY_HOME + "/spark-shell"

  def create(id: String): Session = {
    val (process, port) = startProcess()
    new SparkProcessSession(id, process, port)
  }

  // Loop until we've started a process with a valid port.
  private def startProcess(): (Process, Int) = {
    val regex = """Starting livy-repl on port (\d+)""".r

    @tailrec
    def parsePort(lines: Iterator[String]): Option[Int] = {
      if (lines.hasNext) {
        val line = lines.next()
        line match {
          case regex(port_) => Some(port_.toInt)
          case _ => parsePort(lines)
        }
      } else {
        None
      }
    }

    def startProcess(): (Process, Int) = {
      val pb = new ProcessBuilder(SPARK_SHELL)
      pb.environment().put("PORT", "0")
      val process = pb.start()

      val source = Source.fromInputStream(process.getInputStream)
      val lines = source.getLines()

      parsePort(lines) match {
        case Some(port) => {
          source.close()
          process.getInputStream.close()
          (process, port)
        }
        case None =>
          // Make sure to reap the process.
          process.waitFor()
          throw new Exception("Couldn't start livy-repl")
      }
    }

    startProcess()
  }
}

private class SparkProcessSession(id: String, process: Process, port: Int) extends SparkWebSession(id, "localhost", port) {

  override def close(): Future[Unit] = {
    super.close() andThen { case r =>
      // Make sure the process is reaped.
      process.waitFor()

      r
    }
  }
}