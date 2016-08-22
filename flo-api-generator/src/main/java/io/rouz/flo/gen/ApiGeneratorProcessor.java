package io.rouz.flo.gen;

import org.trimou.engine.MustacheEngine;
import org.trimou.engine.MustacheEngineBuilder;
import org.trimou.engine.locator.ClassPathTemplateLocator;
import org.trimou.engine.resolver.MapResolver;
import org.trimou.engine.resolver.ReflectionResolver;
import org.trimou.util.ImmutableMap;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;

import static javax.tools.Diagnostic.Kind.ERROR;
import static javax.tools.Diagnostic.Kind.NOTE;

/**
 * TODO: document.
 */
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class ApiGeneratorProcessor extends AbstractProcessor {

  static final String ANNOTATION = "@" + GenerateTaskBuilder.class.getSimpleName();

  private Types types;
  private Elements elements;
  private Filer filer;
  private Messager messager;

  private MustacheEngine engine;
  private Element processingElement;

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    types = processingEnv.getTypeUtils();
    elements = processingEnv.getElementUtils();
    filer = processingEnv.getFiler();
    messager = processingEnv.getMessager();

    engine = MustacheEngineBuilder
        .newBuilder()
        .addResolver(new MapResolver())
        .addResolver(new ReflectionResolver())
        .addTemplateLocator(ClassPathTemplateLocator.builder(1)
                                .setClassLoader(this.getClass().getClassLoader())
                                .setSuffix("mustache").build())
        .build();

    messager.printMessage(NOTE, ApiGeneratorProcessor.class.getSimpleName() + " loaded");
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    final Set<String> annotationTypes = new LinkedHashSet<>();
    annotationTypes.add(GenerateTaskBuilder.class.getCanonicalName());
    return annotationTypes;
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    for (TypeElement annotation : annotations) {
      for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
        if (element.getKind() != ElementKind.INTERFACE) {
          messager.printMessage(ERROR, "Only interfaces can be annotated with " + ANNOTATION, element);
          return true;
        }
        processingElement = element;

        final GenerateTaskBuilder genTaskBuilder = element.getAnnotation(GenerateTaskBuilder.class);
        final TypeElement templateElement = (TypeElement) element;

        try {
          final Name packageName = elements.getPackageOf(templateElement).getQualifiedName();
          final String interfaceName = templateElement.getSimpleName().toString().replaceAll("Template$", "");

          writeApiInterface(genTaskBuilder, packageName, interfaceName);
          writeApiImplementation(genTaskBuilder, packageName, interfaceName);
        } catch (IOException e) {
          messager.printMessage(ERROR, "Failed to write source for " + ANNOTATION + " bindings: " + e);
        } catch (RuntimeException e) {
          e.printStackTrace();
          messager.printMessage(ERROR, "Error during " + ANNOTATION + " binding generation");
        }
      }
    }

    return true;
  }

  private void writeApiInterface(
      GenerateTaskBuilder genTaskBuilder,
      Name packageName,
      String interfaceName) throws IOException {

    final Map<String, Object> data = new HashMap<>();
    data.put("packageName", packageName);
    data.put("interfaceName", interfaceName);
    data.put("genFn", IntStream.rangeClosed(0, genTaskBuilder.upTo())
        .mapToObj(this::fn).toArray());
    data.put("genBuilder", IntStream.range(1, genTaskBuilder.upTo())
        .mapToObj(this::builder).toArray());
    final String output = engine.getMustache("TaskBuilder").render(data);

    final String fileName = packageName + "." + interfaceName;
    final JavaFileObject filerSourceFile = filer.createSourceFile(fileName, processingElement);
    try (final Writer writer = filerSourceFile.openWriter()) {
      writer.write(output);
    }
  }

  private void writeApiImplementation(
      GenerateTaskBuilder genTaskBuilder,
      Name packageName,
      String interfaceName) throws IOException {

    final String implClassName = interfaceName + "Impl";

    final Map<String, Object> data = new HashMap<>();
    data.put("packageName", packageName);
    data.put("interfaceName", interfaceName);
    data.put("implClassName", implClassName);
//    data.put("genFn", IntStream.rangeClosed(0, genTaskBuilder.upTo())
//        .mapToObj(this::fn).toArray());
//    data.put("genBuilder", IntStream.range(1, genTaskBuilder.upTo())
//        .mapToObj(this::builder).toArray());
    final String output = engine.getMustache("TaskBuilderImpl").render(data);

    final String fileName = packageName + "." + implClassName;
    final JavaFileObject filerSourceFile = filer.createSourceFile(fileName, processingElement);
    try (final Writer writer = filerSourceFile.openWriter()) {
      writer.write(output);
    }
  }

  private Map<String, Object> builder(int n) {
    return ImmutableMap.of(
        "arity", n,
        "arityPlus", n + 1,
        "nextArg", letters(n + 1).skip(n).findFirst().get(),
        "typeArgs", typeArgs(n),
        "typeArgsMinus", typeArgs(n - 1)
    );
  }

  private Map<String, Object> fn(int n) {
    return ImmutableMap.of(
        "arity", n,
        "typeArgs", typeArgs(n),
        "jdkInterface", jdkInterface(n),
        "parameters", parameters(n)
    );
  }

  private Stream<String> letters(int n) {
    return IntStream.range(0, n)
        .mapToObj(i -> Character.toString((char) ('A' + i)));
  }

  private String typeArgs(int n) {
    return letters(n)
               .collect(Collectors.joining(", "))
           + (n > 0 ? ", " : "");
  }

  private String parameters(int n) {
    return letters(n)
        .map(l -> l + " " + l.toLowerCase())
        .collect(Collectors.joining(", "));
  }

  private String jdkInterface(int n) {
    switch (n) {
      case 0: return "Supplier<Z>, ";
      case 1: return "Function<A, Z>, ";
      case 2: return "BiFunction<A, B, Z>, ";
      default: return "";
    }
  }
}
