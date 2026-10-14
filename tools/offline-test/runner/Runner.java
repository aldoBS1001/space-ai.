import java.lang.reflect.*; import java.util.*; import java.nio.file.*;
public class Runner {
  public static void main(String[] a) throws Exception {
    int pass=0, fail=0; List<String> failures=new ArrayList<>();
    try (var s = Files.walk(Paths.get(a[0]))) {
      for (Path p : (Iterable<Path>) s.filter(x->x.toString().endsWith(".class"))::iterator) {
        String cn = Paths.get(a[0]).relativize(p).toString().replace('/', '.').replaceAll("\\.class$","");
        if (cn.contains("$")) continue;
        Class<?> c = Class.forName(cn);
        Method[] ms = c.getDeclaredMethods(); Arrays.sort(ms, Comparator.comparing(Method::getName));
        boolean header=false;
        for (Method m : ms) {
          org.junit.Test t = m.getAnnotation(org.junit.Test.class); if (t==null) continue;
          if(!header){ System.out.println(c.getSimpleName()); header=true; }
          Object inst = c.getDeclaredConstructor().newInstance();
          Throwable err=null; long t0=System.nanoTime();
          try { m.invoke(inst); } catch (InvocationTargetException e){ err=e.getCause(); }
          boolean ok = t.expected()==org.junit.Test.None.class ? err==null : (err!=null && t.expected().isInstance(err));
          if (ok) { pass++; System.out.printf("  ✓ %s (%d ms)%n", m.getName(), (System.nanoTime()-t0)/1000000); }
          else { fail++; String msg = err==null? "no lanzó "+t.expected().getSimpleName() : err.toString(); failures.add(c.getSimpleName()+"."+m.getName()+": "+msg); System.out.println("  ✗ "+m.getName()+" — "+msg); }
        }
      }
    }
    System.out.printf("%nRESULTADO: %d aprobadas, %d fallidas%n", pass, fail);
    failures.forEach(f->System.out.println("FALLA "+f));
    System.exit(fail==0?0:1);
  }
}
