package sample.tigerapp;

import dagger.Component;
import dagger.android.AndroidInjectionModule;
import dagger.android.AndroidInjector;
import javax.inject.Singleton;
import sample.ApplicationModule;

/** Created by freemanliu on 3/22/18. */
@Singleton
@Component(
  modules = {
    AndroidInjectionModule.class,
    TigerApplicationModule.class,
    sample.ApplicationModule.class
  }
)
public interface TigerApplicationComponent extends AndroidInjector<TigerApplication> {

  @Component.Builder
  abstract class Builder extends AndroidInjector.Builder<TigerApplication> {
    abstract Builder setApplicationModule(ApplicationModule x);
  }
}
