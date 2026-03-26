import java.lang.reflect.Method;
import java.lang.annotation.Annotation;

public class AriaInspect {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java -cp . AriaInspect <ClassName>");
            return;
        }
        Class<?> cls = Class.forName(args[0]);
        System.out.println("Inspecting: " + cls.getName());
        for (Method m : cls.getDeclaredMethods()) {
            Annotation[] anns = m.getDeclaredAnnotations();
            if (anns.length == 0) continue;
            System.out.println("  " + m.getName() + "()");
            for (Annotation a : anns) {
                System.out.println("    " + a);
            }
        }
    }
}
