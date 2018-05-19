package tiger;

import com.google.common.collect.Lists;
import java.util.HashSet;
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic.Kind;

/**
 * Created by freemanliu on 5/18/18.
 */
public class SetWrapper<T> extends HashSet<T> {
  private final Logger logger;
  private boolean debugEnabled;

  public SetWrapper(Messager messager) {
    logger = new Logger(messager, Kind.NOTE);
  }

  public void setDebugEnabled(boolean v) {
    debugEnabled = v;
  }

  @Override
  public boolean add(T t) {
    boolean result = super.add(t);
    boolean toDebug = t.toString().contains("UriUtil");
    if (debugEnabled && toDebug) {
      logger.w(
          "added %s ? %s\nstack: %s",
          t, result, Lists.newArrayList(new RuntimeException().getStackTrace()));
    }
    return result;
  }

}
