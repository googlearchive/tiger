package tiger;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic.Kind;

/** Created by freemanliu on 4/14/18. */
public class Logger {
  private final Messager messager;
  private Kind lowestKind;

  public Logger(Messager messager, Kind kind) {
    this.messager = messager;
    this.lowestKind = kind;
  }

  public void e(String fmt, Object... args) {
    l(Kind.ERROR, fmt, args);
  }

  public void w(String fmt, Object... args) {
    l(Kind.WARNING, fmt, args);
  }

  public void n(String fmt, Object... args) {
    l(Kind.NOTE, fmt, args);
  }

  public void l(Kind kind, String fmt, Object... args) {
    if (getPriority(kind) < getPriority(lowestKind)) {
      return;
    }
    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
    StackTraceElement stackTraceElement = stackTrace[3];

    String className = stackTraceElement.getClassName();
    String methodName = stackTraceElement.getMethodName();
    int lineNumber = stackTraceElement.getLineNumber();
    messager.printMessage(kind, className + "." + methodName + " @" + lineNumber + ": " + String.format(fmt, args));
  }

  /** The bigger, the higher. */
  private int getPriority(Kind kind) {
    int result = 0;
    switch (kind) {
      case ERROR:
        result = 600;
        break;
      case WARNING:
        result = 500;
        break;
      case MANDATORY_WARNING:
        result = 400;
        break;
      case NOTE:
        result = 300;
        break;
      case OTHER:
        result = 200;
        break;
    }
    return result;
  }
}
