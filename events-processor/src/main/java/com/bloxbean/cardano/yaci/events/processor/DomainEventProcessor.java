package com.bloxbean.cardano.yaci.events.processor;

import com.bloxbean.cardano.yaci.events.api.DomainEventListener;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.annotation.processing.FilerException;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.*;

@SupportedAnnotationTypes("com.bloxbean.cardano.yaci.events.api.DomainEventListener")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class DomainEventProcessor extends AbstractProcessor {
    private Filer filer;
    private Messager messager;
    private Elements elements;
    private Types types;

    private static final String BINDINGS_IFACE = "com.bloxbean.cardano.yaci.events.api.support.DomainEventBindings";
    private static final String SERVICE_FILE = "META-INF/services/" + BINDINGS_IFACE;

    private final Set<String> generated = new LinkedHashSet<>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
        elements = processingEnv.getElementUtils();
        types = processingEnv.getTypeUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            // Write service file once with all generated classes
            if (!generated.isEmpty()) writeServiceFile();
            return false;
        }

        TypeElement annType = elements.getTypeElement("com.bloxbean.cardano.yaci.events.api.DomainEventListener");
        if (annType == null) return false;

        // Collect annotated methods per type for this round
        Map<TypeElement, List<ListenerMethod>> byType = new HashMap<>();
        for (Element e : roundEnv.getElementsAnnotatedWith(annType)) {
            if (e.getKind() != ElementKind.METHOD) continue;
            ExecutableElement method = (ExecutableElement) e;
            TypeElement enclosing = (TypeElement) method.getEnclosingElement();

            if (method.getParameters().size() != 1) {
                messager.printMessage(Diagnostic.Kind.ERROR, "@DomainEventListener methods must have exactly one parameter", method);
                continue;
            }
            VariableElement param = method.getParameters().get(0);

            // Determine event type
            TypeMirror eventType = resolveEventType(param.asType());
            if (eventType == null) {
                messager.printMessage(Diagnostic.Kind.ERROR, "Could not resolve event type for parameter", method);
                continue;
            }

            DomainEventListener ann = method.getAnnotation(DomainEventListener.class);
            int order = ann.order();
            boolean async = ann.async();
            boolean ctxStyle = isEventContext(param.asType());

            byType.computeIfAbsent(enclosing, k -> new ArrayList<>())
                  .add(new ListenerMethod(method, eventType, order, async, ctxStyle));
        }

        for (Map.Entry<TypeElement, List<ListenerMethod>> entry : byType.entrySet()) {
            generateBinder(entry.getKey(), entry.getValue());
        }

        return false;
    }

    private boolean isEventContext(TypeMirror type) {
        if (!(type instanceof DeclaredType dt)) return false;
        String raw = ((TypeElement) dt.asElement()).getQualifiedName().toString();
        return raw.equals("com.bloxbean.cardano.yaci.events.api.EventContext");
    }

    private TypeMirror resolveEventType(TypeMirror parameterType) {
        if (parameterType instanceof DeclaredType dt) {
            String raw = ((TypeElement) dt.asElement()).getQualifiedName().toString();
            if (raw.equals("com.bloxbean.cardano.yaci.events.api.EventContext")) {
                List<? extends TypeMirror> args = dt.getTypeArguments();
                if (args.size() == 1) {
                    return args.get(0);
                } else return null;
            } else {
                return parameterType;
            }
        }
        return null;
    }

    private void generateBinder(TypeElement type, List<ListenerMethod> methods) {
        methods.sort(Comparator.comparingInt(m -> m.order));
        String pkg = elements.getPackageOf(type).getQualifiedName().toString();
        String simpleName = type.getSimpleName().toString();
        String binderName = simpleName + "_EventBindings";
        String fqn = pkg.isEmpty() ? binderName : pkg + "." + binderName;
        if (generated.contains(fqn)) return; // already created in a previous round
        try {
            Writer w = filer.createSourceFile(fqn, type).openWriter();
            try {
                w.write("package " + pkg + ";\n\n");
                w.write("public final class " + binderName + " implements " + BINDINGS_IFACE + " {\n");
                w.write("  @Override public java.lang.Class<?> targetType() { return " + type.getQualifiedName() + ".class; }\n");
                w.write("  @Override public java.util.List<com.bloxbean.cardano.yaci.events.api.SubscriptionHandle> register(" +
                        "com.bloxbean.cardano.yaci.events.api.EventBus bus, java.lang.Object instance, " +
                        "com.bloxbean.cardano.yaci.events.api.SubscriptionOptions defaults) {\n");
                w.write("    java.util.List<com.bloxbean.cardano.yaci.events.api.SubscriptionHandle> hs = new java.util.ArrayList<>();\n");
                w.write("    " + type.getQualifiedName() + " target = (" + type.getQualifiedName() + ") instance;\n");

                int idx = 0;
                for (ListenerMethod lm : methods) {
                    String optsVar = "opts" + (idx++);
                    String execExpr = lm.async
                            ? "(defaults.executor() != null ? defaults.executor() : com.bloxbean.cardano.yaci.events.api.support.EventsExecutors.virtual())"
                            : "null";

                    w.write("    com.bloxbean.cardano.yaci.events.api.SubscriptionOptions " + optsVar + " = " +
                            "com.bloxbean.cardano.yaci.events.api.SubscriptionOptions.builder()" +
                            ".bufferSize(defaults.bufferSize())" +
                            ".overflow(defaults.overflow())" +
                            ".executor(" + execExpr + ")" +
                            ".filter(defaults.filter())" +
                            ".priority(" + lm.order + ")" +
                            ".build();\n");

                    String eventType = lm.eventType.toString();
                    String handler;
                    if (lm.ctxStyle) {
                        handler = "target::" + lm.method.getSimpleName();
                    } else {
                        handler = "ctx -> target." + lm.method.getSimpleName() + "(ctx.event())";
                    }

                    w.write("    hs.add(bus.subscribe(" + eventType + ".class, " + handler + ", " + optsVar + "));\n");
                }
                w.write("    return hs;\n");
                w.write("  }\n");
                w.write("}\n");
            } finally {
                w.close();
            }
            generated.add(fqn);
        } catch (FilerException fe) {
            // Another round already created this file; treat as generated
            generated.add(fqn);
        } catch (IOException ioe) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Failed to write binder: " + ioe.getMessage(), type);
        }
    }

    private void writeServiceFile() {
        try {
            FileObject fo = filer.createResource(StandardLocation.CLASS_OUTPUT, "", SERVICE_FILE);
            try (Writer w = fo.openWriter()) {
                for (String name : generated) {
                    w.write(name);
                    w.write("\n");
                }
            }
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.WARNING, "Failed to write service file: " + e.getMessage());
        }
    }

    @Override
    public Set<String> getSupportedOptions() {
        // Accept the common -Aproject option used by builds to suppress warnings
        return java.util.Set.of("project");
    }

    private static final class ListenerMethod {
        final ExecutableElement method;
        final TypeMirror eventType;
        final int order;
        final boolean async;
        final boolean ctxStyle;
        ListenerMethod(ExecutableElement m, TypeMirror et, int order, boolean async, boolean ctxStyle) {
            this.method = m; this.eventType = et; this.order = order; this.async = async; this.ctxStyle = ctxStyle;
        }
    }
}
