package sample.tigerapp;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import dagger.android.DaggerActivity;
import android.app.Activity;
import android.widget.TextView;

public class MainActivity extends DaggerActivity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    TextView textView = (TextView) findViewById(R.id.text);
    TigerApplication app = (TigerApplication) getApplication();
    textView.setText(app.planets.toString());
  }
}
