package sample.tigerapp;

import dagger.android.AndroidInjector;
import dagger.android.DaggerApplication;
import java.util.Map;
import javax.inject.Inject;
import sample.ApplicationModule;
import sample.Main;
import sample.Planet;

/**
 * Created by freemanliu on 3/22/18.
 */
public class TigerApplication extends DaggerApplication {

  // @Override
  // public void onCreate() {
  //   super.onCreate();
  //   // DaggerApplicationComponent.create()
  //   //     .inject(this);
  // }

  @Inject
  Map<String, Planet> planets;

  @java.lang.Override
  protected AndroidInjector<? extends DaggerApplication> applicationInjector() {
    return DaggerTigerApplicationComponent.builder()
        .setApplicationModule(new ApplicationModule(new Main()))
        .create(this);
  }
}
