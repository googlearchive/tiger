package tiger;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import java.io.IOException;
import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;

/**
 * Created by freemanliu on 4/12/18.
 */
public class TailChaser {

  static private int round;
  private final Filer filer;

  public TailChaser(Filer filer) {
    this.filer = filer;
  }

  public void chase() {
      TypeSpec.Builder builder = TypeSpec.classBuilder("Tail" + round ++)
          .addModifiers(Modifier.PUBLIC)
          .addAnnotation(Tail.class);
      JavaFile javaFile = JavaFile.builder("tiger", builder.build()).build();
      try {
        javaFile.writeTo(filer);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
}
