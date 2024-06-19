package lib.log

import chisel3.PrintableHelper
import lib.log.Log.Level

/**
 * Chisel logging helper
 *
 * Facilitates logging at configurable levels and modules during simulation.
 */
object Log {
  sealed abstract class Level(val short: String, val order: Int) extends Ordered[Level] {
    def compare(that: Level): Int = this.order - that.order
  }

  object Level {
    case object Silent extends Level("", -1)
    case object Critical extends Level("CRT", 0)
    case object Error extends Level("ERR", 1)
    case object Warning extends Level("WRN", 2)
    case object Info extends Level("INF", 3)
    case object Debug extends Level("DBG", 4)
  }

  private var defaultLevel: Level = Level.Critical
  private val levels = collection.mutable.Map[String, Level]()

  protected[log] def getLevel(module: String): Level = {
    levels.getOrElse(module, defaultLevel)
  }

  def setDefaultLevel(level: Level): Unit = defaultLevel = level
  def setModuleLevel(module: String, level: Level): Unit = levels.put(module, level)
}

object Logger {
  def apply(module: String): Logger = new Logger(module)

  def log(level: Level, module: String, log: chisel3.Printable): Unit = {
    if (level <= Log.getLevel(module)) {
      chisel3.printf(cf"[${level.short}][${module}] " + log + cf"\n")
    }
  }
}

class Logger(module: String) {
  def crit(log: chisel3.Printable): Unit = Logger.log(Log.Level.Critical, module, log)
  def error(log: chisel3.Printable): Unit = Logger.log(Log.Level.Error, module, log)
  def warn(log: chisel3.Printable): Unit = Logger.log(Log.Level.Warning, module, log)
  def info(log: chisel3.Printable): Unit = Logger.log(Log.Level.Info, module, log)
  def debug(log: chisel3.Printable): Unit = Logger.log(Log.Level.Debug, module, log)
}
