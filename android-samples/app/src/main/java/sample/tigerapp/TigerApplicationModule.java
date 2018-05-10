package sample.tigerapp;

import dagger.Module;
import dagger.android.ContributesAndroidInjector;
import sample.ActivityScoped;

/**
 * Created by freemanliu on 3/22/18.
 */
@Module
public interface TigerApplicationModule {

  @ContributesAndroidInjector
  @ActivityScoped
  MainActivity getMainActivity();
}
