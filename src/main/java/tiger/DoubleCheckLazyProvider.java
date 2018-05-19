package tiger;

import dagger.Lazy;
import javax.inject.Provider;

/** Created by freemanliu on 5/12/18. */
public class DoubleCheckLazyProvider<T> implements Lazy<T>, Provider<T> {
  private final Provider<T> provider;

  @SuppressWarnings("unchecked")
  public static <T> DoubleCheckLazyProvider<T> create(Provider<T> provider) {
    if (provider instanceof DoubleCheckLazyProvider) {
      return (DoubleCheckLazyProvider<T>) provider;
    } else {
      return new DoubleCheckLazyProvider<>(provider);
    }
  }

  private DoubleCheckLazyProvider(Provider<T> provider) {
    this.provider = provider;
  }

  private T value;

  @Override
  public T get() {
    T result = value;
    if (result == null) {
      synchronized (this) {
        if (result == null) {
          value = result = provider.get();
        }
      }
    }
    return result;
  }
}
