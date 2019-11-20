package tofu
package logging
package logback

import java.time.Instant

import cats.syntax.monoid._
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.CoreConstants.{LINE_SEPARATOR => EOL}
import tofu.data.PArray
import impl.ContextMarker
import syntax.logRenderer._
import tofu.logging.ELKLayout.{
  ExceptionField,
  LevelField,
  LoggerNameField,
  MarkersField,
  MessageField,
  StackTraceField,
  ThreadNameField,
  TimeStampField
}

trait EventLoggable extends DictLoggable[ILoggingEvent] with ToStringLoggable[ILoggingEvent]

class EventBuiltInLoggable extends EventLoggable {
  def fields[I, V, R, S](evt: ILoggingEvent, i: I)(implicit r: LogRenderer[I, V, R, S]): R =
    r.addString(TimeStampField, Instant.ofEpochMilli(evt.getTimeStamp).toString, i) |+|
      r.addString(LoggerNameField, evt.getLoggerName, i) |+|
      r.addString(ThreadNameField, evt.getThreadName, i) |+|
      r.addString(LevelField, evt.getLevel.toString, i) |+|
      r.addString(MessageField, evt.getFormattedMessage.trim, i)
}

class EventMarkerLoggable extends EventLoggable {
  def fields[I, V, R, S](evt: ILoggingEvent, i: I)(implicit rec: LogRenderer[I, V, R, S]): R =
    evt.getMarker match {
      case null                         => i.noop
      case ContextMarker(marker, Seq()) => marker.logFields(i)
      case ContextMarker(marker, rest) =>
        import PArray.arrInstance // scala 2.11
        val restArr = PArray.fromColl(rest)
        marker.logFields(i) |+|
          i.sub(MarkersField)((v: V) => v.foldable(restArr)(_ putString _.getName))
      case marker => i.sub(MarkersField)((v: V) => v.list(1)((v, _) => v.putString(marker.getName)))
    }
}

class EventArgumentsLoggable extends EventLoggable {
  def fields[I, V, R, S](evt: ILoggingEvent, i: I)(implicit r: LogRenderer[I, V, R, S]): R =
    evt.getArgumentArray match {
      case null => i.noop
      case array =>
        array.foldLeft(i.noop)(
          (acc, arg) =>
            acc |+| (arg match {
              case lv: LoggedValue => lv.logFields(i)
              case _               => i.noop
            })
        )
    }
}

class EventExceptionLoggable extends EventLoggable {
  def fields[I, V, R, S](evt: ILoggingEvent, i: I)(implicit rec: LogRenderer[I, V, R, S]): R =
    evt.getThrowableProxy match {
      case null => i.noop
      case throwableProxy =>
        i.addString(ExceptionField, s"${throwableProxy.getClassName}: ${throwableProxy.getMessage}") |+| {
          val stackTrace = throwableProxy.getStackTraceElementProxyArray
          if (stackTrace.isEmpty) i.noop else i.addString(StackTraceField, stackTrace.mkString(EOL))
        }
    }
}

object EventLoggable {
  val buildin: Loggable[ILoggingEvent]    = new EventBuiltInLoggable
  val fromMarker: Loggable[ILoggingEvent] = new EventMarkerLoggable
  val arguments: Loggable[ILoggingEvent]  = new EventArgumentsLoggable
  val exception: Loggable[ILoggingEvent]  = new EventExceptionLoggable

  val default: Loggable[ILoggingEvent] = buildin + fromMarker + arguments + exception
}
