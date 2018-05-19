package tiger;

import com.google.common.collect.Sets;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;

/**
 * Delays processing until all conditions meet while keeping the processing from finishing prematurely.
 */
public abstract class TailChaserProcesssor extends AbstractProcessor{
  private static final String TAG = "TailChaserProcesssor";

  protected Elements elements;
  protected Types types;
  protected Messager messager;
  protected Utils utils;
  protected RoundEnvironment roundEnvironment;
  protected Logger logger;
  private TailChaser tailChaser;
  private int round;

  @Override
  public synchronized void init(ProcessingEnvironment env) {
    super.init(env);

    elements = env.getElementUtils();
    types = env.getTypeUtils();
    messager = env.getMessager();
    logger = new Logger(messager, Kind.WARNING);
    tailChaser = new TailChaser(env.getFiler());
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
    roundEnvironment = env;
    utils = new Utils(processingEnv, roundEnvironment);
    round++;
    logger.n(
        ".process() round: %s, processingOver: %s", round, env.processingOver());
    // if (done) {
    //   return false;
    // }

    Set<? extends TypeElement> annotationsExceptTail =
        Sets.filter(
            annotations, a -> a.getQualifiedName().contentEquals(Tail.class.getCanonicalName()));
    if (!handle(annotationsExceptTail)) {
      tailChaser.chase();
      return false;
    }

    // done = true;
    return false;
  }

  /**
   * Returns if done. It could includes multiple rounds of processing and only return done after
   * the last round.
   */
  protected abstract boolean handle(Set<? extends TypeElement> annotations);

  protected abstract Set<String> getAnnotationTypesToChase();

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    HashSet<String> annotations = Sets.newHashSet(getAnnotationTypesToChase());
    annotations.add(Tail.class.getCanonicalName());
    return annotations;
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }
}
